package com.aiverse.feature.plugins.runtime

import com.aiverse.core.util.FileLogger
import com.aiverse.feature.agent.domain.container.CommandEngine
import com.aiverse.feature.agent.domain.container.ContainerInstaller
import com.aiverse.feature.agent.domain.container.ContainerProfile
import com.aiverse.feature.agent.domain.container.LinuxContainerEngine
import com.aiverse.feature.plugins.domain.PluginHostException
import com.aiverse.feature.plugins.domain.PluginHostPaths
import com.aiverse.feature.plugins.domain.PluginManager
import com.aiverse.feature.plugins.domain.newmodel.PluginRuntime
import com.aiverse.feature.plugins.overlay.AppForegroundMonitor
import com.aiverse.feature.plugins.overlay.PluginOverlayException
import com.aiverse.feature.plugins.overlay.PluginOverlayManager
import com.aiverse.feature.settings.data.repository.ContainerSettingsRepository
import com.aiverse.feature.settings.data.repository.ExecutionMode
import com.aiverse.feature.settings.data.repository.ExecutionModeHolder
import com.aiverse.feature.workspace.data.repository.WorkspaceRepository
import com.aiverse.feature.workspace.domain.FileAccessProvider
import com.aiverse.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 插件运行时的宿主门面：按插件版本管理容器内进程，把工具/hook 调用转成 JSON-RPC。
 *
 * 进程在本地容器中运行（SSH 模式下同样在手机端），cwd 是插件自己的安装目录，
 * 因此可以直接读取包内文件、`node_modules` 或虚拟环境。插件主动发来的 host API
 * 请求复用 [PluginHostPaths]，与页面桥的文件限制保持一致。
 *
 * host.* 分发按「核心+降级」决策：能映射到 AiVerse 宿主类的方法直接调用；
 * AiVerse 尚未实现的宿主能力抛 [PluginProcessException]（code=METHOD_UNSUPPORTED），不新建基础设施。
 */
@Singleton
class PluginRuntimeManager @Inject constructor(
    private val pluginManager: PluginManager,
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val eventBus: PluginEventBus,
    private val notifier: PluginNotifier,
    private val terminalRunner: PluginTerminalRunner,
    private val shizukuRunner: PluginShizukuRunner,
    private val tipManager: PluginTipManager,
    private val chatService: PluginChatService,
    private val modelService: PluginModelService,
    private val sessionSourceHostService: PluginSessionHostService,
    private val secretStore: PluginSecretStore,
    private val systemService: PluginSystemService,
    private val introspectionService: PluginIntrospectionService,
    private val toolService: PluginToolService,
    private val httpService: PluginHttpService,
    private val portService: PluginPortService,
    private val appForegroundMonitor: AppForegroundMonitor,
    private val overlayManager: PluginOverlayManager,
    private val providerService: PluginProviderService,
    private val permissionService: PluginPermissionService
) {

    private class Entry(
        val pluginId: String,
        val key: String,
        val transport: PluginProcessTransport,
        var lastUsed: Long,
        var resident: Boolean
    )

    private val processes = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 浮层页面向宿主发来的消息转回插件进程（handler = overlay.message）。
        overlayManager.setEventSink { pluginId, overlayId, payload ->
            scope.launch {
                val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
                    ?: return@launch
                val runtime = plugin.content?.runtime ?: return@launch
                runCatching {
                    invoke(
                        pluginId = pluginId,
                        version = plugin.record.version,
                        runtime = runtime,
                        handler = "overlay.message",
                        args = buildJsonObject {
                            put("overlayId", overlayId)
                            put("payload", payload)
                        },
                        method = "ui.invoke",
                        timeoutMs = OVERLAY_MESSAGE_TIMEOUT_MS
                    )
                }.onFailure {
                    FileLogger.w(TAG, "overlay message failed: $pluginId", it)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                pruneIdle()
            }
        }
    }

    suspend fun invoke(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject,
        method: String = "tool.invoke",
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): JsonElement {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        if (!isActive(pluginId)) {
            synchronized(processes) { processes.remove(entry.key, entry) }
            entry.transport.close()
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
        entry.lastUsed = System.currentTimeMillis()
        return try {
            entry.transport.request(
                method = method,
                params = buildJsonObject {
                    put("handler", handler)
                    put("args", args)
                },
                timeoutMs = timeoutMs
            )
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_EXITED" || (e.code == "PLUGIN_TIMEOUT" && !entry.resident)) {
                synchronized(processes) { processes.remove(entry.key, entry) }
                entry.transport.close()
            }
            throw e
        }
    }

    /** 插件版本替换后关闭旧版本进程，避免 resident 旧进程永远不回收。 */
    fun closeOtherVersions(pluginId: String, currentVersion: String) {
        val keepKey = "$pluginId@$currentVersion"
        synchronized(processes) {
            processes.entries
                .filter { (key, entry) -> entry.pluginId == pluginId && key != keepKey }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    /** 插件停用、卸载或版本替换时关闭它的进程。 */
    fun closePlugin(pluginId: String) {
        synchronized(processes) {
            processes.entries
                .filter { (_, entry) -> entry.pluginId == pluginId }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    private suspend fun getOrCreate(pluginId: String, version: String, runtime: PluginRuntime): Entry {
        val key = "$pluginId@$version"
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", it)
        }
        val dataDir = dataDirectory(pluginId)
        val wsRoot = workspaceRoot()
        // 上面的挂起调用期间可能已有并发调用创建了同一版本的进程。
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        var createdEntry: Entry? = null
        val transport = PluginProcessTransport(
            pluginId = pluginId,
            engine = engine,
            profile = profile,
            workingDir = containerDir(runtime.installDir),
            program = runtime.command.firstOrNull()
                ?: throw PluginProcessException("PLUGIN_COMMAND_MISSING", "[$pluginId] 运行时没有启动命令"),
            programArgs = runtime.command.drop(1),
            extraEnv = mapOf(
                "AICODE_PLUGIN_ID" to pluginId,
                "AICODE_PLUGIN_VERSION" to version,
                "AICODE_DATA_DIR" to dataDir,
                "AICODE_WORKSPACE" to wsRoot
            ),
            onRequest = { method, params -> hostRequest(pluginId, runtime, method, params) },
            onNotification = { method, params -> handleNotification(pluginId, method, params) },
            onExit = {
                createdEntry?.let { entry ->
                    synchronized(processes) { processes.remove(key, entry) }
                }
            },
            closeOnTimeout = !runtime.resident
        )
        val entry = Entry(pluginId, key, transport, System.currentTimeMillis(), runtime.resident)
        createdEntry = entry
        val existing = synchronized(processes) { processes.putIfAbsent(key, entry) }
        if (existing != null) {
            transport.close()
            return existing
        }
        return entry
    }

    private suspend fun hostRequest(
        pluginId: String,
        runtime: PluginRuntime,
        method: String,
        params: JsonObject
    ): JsonElement {
        val paths = PluginHostPaths(
            fileAccess = fileAccess,
            homeResolver = homeResolver,
            workspaceRoot = ::workspaceRoot,
            pluginId = pluginId
        )
        return when (method) {
            "host.fs.read" -> hostCall {
                val (offset, limit) = PluginHostPaths.readWindow(params)
                paths.read(PluginHostPaths.requireString(params, "path"), offset, limit)
            }
            "host.fs.write" -> hostCall {
                paths.write(
                    PluginHostPaths.requireString(params, "path"),
                    PluginHostPaths.requireString(params, "content")
                )
            }
            "host.fs.list" -> hostCall { paths.list(PluginHostPaths.requireString(params, "path")) }
            "host.fs.delete" -> hostCall { paths.delete(PluginHostPaths.requireString(params, "path")) }
            "host.fs.stat" -> hostCall { paths.stat(PluginHostPaths.requireString(params, "path")) }
            "host.fs.mkdir" -> hostCall { paths.mkdir(PluginHostPaths.requireString(params, "path")) }
            "host.fs.rename" -> hostCall {
                paths.rename(
                    PluginHostPaths.requireString(params, "from"),
                    PluginHostPaths.requireString(params, "to")
                )
            }
            "host.fs.copy" -> hostCall {
                paths.copy(
                    PluginHostPaths.requireString(params, "from"),
                    PluginHostPaths.requireString(params, "to"),
                    (params["overwrite"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }
            "host.fs.append" -> hostCall {
                paths.append(
                    PluginHostPaths.requireString(params, "path"),
                    PluginHostPaths.requireString(params, "content")
                )
            }
            "host.fs.readBinary" -> hostCall {
                paths.readBinary(PluginHostPaths.requireString(params, "path"))
            }
            "host.fs.writeBinary" -> hostCall {
                paths.writeBinary(
                    PluginHostPaths.requireString(params, "path"),
                    PluginHostPaths.requireString(params, "base64")
                )
            }
            "host.app.isForeground" -> JsonPrimitive(appForegroundMonitor.isForeground.value)
            "host.bash" -> bash(params)
            "host.notify" -> notifier.notify(pluginId, params)
            "host.shizuku.exec" -> shizukuExec(pluginId, params)
            "host.overlay.create" -> overlayCreate(pluginId, params)
            "host.overlay.close" -> overlayClose(pluginId, params)
            "host.overlay.postMessage" -> overlayPostMessage(pluginId, params)
            "host.overlay.update" -> overlayUpdate(pluginId, params)
            "host.tip" -> tip(pluginId, params)
            "host.terminal.run" -> terminalRun(pluginId, params)
            "host.terminal.poll" -> terminalPoll(pluginId, params)
            "host.terminal.stop" -> terminalStop(pluginId, params, close = false)
            "host.terminal.close" -> terminalStop(pluginId, params, close = true)
            "host.terminal.write" -> terminalWrite(pluginId, params)
            "host.terminal.list" -> terminalCall { terminalRunner.list(pluginId) }
            "host.chat.listSessions" -> hostCall { chatService.listSessions(pluginId, params) }
            "host.chat.createSession" -> hostCall { chatService.createSession(pluginId, params) }
            "host.chat.listMessages" -> hostCall { chatService.listMessages(pluginId, params) }
            "host.chat.sendMessage" -> hostCall { chatService.sendMessage(pluginId, params) }
            "host.chat.setDraft" -> hostCall { chatService.setDraft(pluginId, params) }
            "host.chat.stop" -> hostCall { chatService.stop(pluginId, params) }
            "host.chat.openSession" -> hostCall { chatService.openSession(pluginId, params) }
            "host.chat.getSession" -> hostCall { chatService.getSession(pluginId, params) }
            "host.chat.renameSession" -> hostCall { chatService.renameSession(pluginId, params) }
            "host.chat.pinSession" -> hostCall { chatService.pinSession(pluginId, params) }
            "host.chat.deleteSession" -> hostCall { chatService.deleteSession(pluginId, params) }
            "host.chat.getRunState" -> hostCall { chatService.getRunState(pluginId, params) }
            "host.chat.waitForRun" -> hostCall { chatService.waitForRun(pluginId, params) }
            "host.chat.listApprovals" -> hostCall { chatService.listApprovals(pluginId, params) }
            "host.chat.resolveToolApproval" -> hostCall { chatService.resolveToolApproval(pluginId, params) }
            "host.chat.resolveQuestion" -> hostCall { chatService.resolveQuestion(pluginId, params) }
            "host.chat.resolvePlanApproval" -> hostCall { chatService.resolvePlanApproval(pluginId, params) }
            "host.sessionSource.setSessions" -> hostCall { sessionSourceHostService.setSessions(pluginId, params) }
            "host.sessionSource.setLoading" -> hostCall { sessionSourceHostService.setLoading(pluginId, params) }
            "host.sessionSource.setError" -> hostCall { sessionSourceHostService.setError(pluginId, params) }
            "host.sessionSource.setThinking" -> hostCall { sessionSourceHostService.setThinking(pluginId, params) }
            "host.model.listProviders" -> hostCall { modelService.listProviders(pluginId) }
            "host.model.listModels" -> hostCall { modelService.listModels(pluginId, params) }
            "host.model.create" -> hostCall { modelService.create(pluginId, params) }
            "host.model.close" -> hostCall { modelService.close(pluginId, params) }
            "host.model.start" -> hostCall { modelService.start(pluginId, params, PROCESS_ORIGIN) }
            "host.model.read" -> hostCall { modelService.read(pluginId, params) }
            "host.model.cancel" -> hostCall { modelService.cancel(pluginId, params) }
            "host.sessionSource.setMessages" -> hostCall { sessionSourceHostService.setMessages(pluginId, params) }
            "host.sessionSource.pushEvent" -> hostCall { sessionSourceHostService.pushEvent(pluginId, params) }
            "host.providers.listModels" -> hostCall { providerService.listModels(pluginId, params) }
            "host.providers.addModel" -> hostCall { providerService.addModel(pluginId, params) }
            "host.providers.deleteModel" -> hostCall { providerService.deleteModel(pluginId, params) }
            "host.providers.setModelContext" -> hostCall { providerService.setModelContext(pluginId, params) }
            "host.providers.setApiKey" -> hostCall { providerService.setApiKey(pluginId, params) }
            "host.permissions.request" -> hostCall { permissionService.request(pluginId, params) }
            "host.permissions.cancel" -> hostCall { permissionService.cancel(pluginId, params) }
            "host.clipboard.read" -> hostCall { systemService.clipboardRead(pluginId) }
            "host.clipboard.write" -> hostCall {
                systemService.clipboardWrite(pluginId, PluginHostPaths.requireString(params, "text"))
            }
            "host.share.text" -> hostCall {
                systemService.shareText(
                    pluginId,
                    PluginHostPaths.requireString(params, "text"),
                    (params["title"] as? JsonPrimitive)?.contentOrNull
                )
            }
            "host.ui.openUrl" -> hostCall {
                systemService.openUrl(pluginId, PluginHostPaths.requireString(params, "url"))
            }
            "host.app.info" -> hostCall { systemService.appInfo(pluginId) }
            "host.log" -> hostCall {
                systemService.log(
                    pluginId,
                    PluginHostPaths.requireString(params, "level"),
                    PluginHostPaths.requireString(params, "message"),
                    (params["tag"] as? JsonPrimitive)?.contentOrNull
                )
            }
            "host.secrets.get" -> hostCall {
                secretStore.get(pluginId, PluginHostPaths.requireString(params, "key"))
            }
            "host.secrets.set" -> hostCall {
                secretStore.set(
                    pluginId,
                    PluginHostPaths.requireString(params, "key"),
                    PluginHostPaths.requireString(params, "value")
                )
            }
            "host.secrets.delete" -> hostCall {
                secretStore.delete(pluginId, PluginHostPaths.requireString(params, "key"))
            }
            "host.secrets.list" -> hostCall { secretStore.list(pluginId) }
            "host.tools.list" -> hostCall {
                introspectionService.listTools(
                    pluginId,
                    includeSchema = (params["includeSchema"] as? JsonPrimitive)?.booleanOrNull ?: false
                )
            }
            "host.tools.execute" -> hostCall { toolService.execute(pluginId, params) }
            "host.tools.pending" -> hostCall { toolService.pending(pluginId, params) }
            "host.tools.resolve" -> hostCall { toolService.resolve(pluginId, params) }
            "host.mcp.status" -> hostCall { introspectionService.mcpStatus(pluginId) }
            "host.http.request" -> hostCall { httpService.request(pluginId, params) }
            "host.ports.forward" -> hostCall { buildJsonObject { put("localPort", portService.addPortMapping(pluginId, params)) } }
            "host.ports.close" -> hostCall {
                buildJsonObject { put("closed", portService.removePortMapping(pluginId, params)) }
            }
            "host.ports.list" -> hostCall {
                buildJsonArray {
                    portService.listPorts(pluginId).forEach { mapping ->
                        add(buildJsonObject { mapping.forEach { (key, port) -> put(key, port) } })
                    }
                }
            }
            else -> throw PluginProcessException("METHOD_UNKNOWN", "unknown host method: $method")
        }
    }

    /** 常驻进程创建宿主托管的系统浮层；权限与页面声明由 OverlayManager 校验。 */
    private suspend fun overlayCreate(pluginId: String, params: JsonObject): JsonElement = try {
        val surface = (params["surface"] as? JsonPrimitive)?.contentOrNull
        overlayManager.create(pluginId, surface, params)
    } catch (e: PluginOverlayException) {
        throw PluginProcessException(e.code, e.message.orEmpty())
    }

    private fun overlayClose(pluginId: String, params: JsonObject): JsonElement = try {
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        when {
            !overlayId.isNullOrBlank() -> overlayManager.close(pluginId, overlayId)
            !surfaceId.isNullOrBlank() -> overlayManager.closeBySurface(pluginId, surfaceId)
            else -> throw PluginProcessException("PARAM_INVALID", "overlayId or surface required")
        }
    } catch (e: PluginOverlayException) {
        throw PluginProcessException(e.code, e.message.orEmpty())
    }

    private fun overlayPostMessage(pluginId: String, params: JsonObject): JsonElement = try {
        val overlayId = (params["overlayId"] as? JsonPrimitive)?.contentOrNull
        val surfaceId = (params["surface"] as? JsonPrimitive)?.contentOrNull
        val payload = params["payload"] ?: JsonNull
        when {
            !overlayId.isNullOrBlank() -> overlayManager.postMessage(pluginId, overlayId, payload)
            !surfaceId.isNullOrBlank() -> overlayManager.postMessageBySurface(pluginId, surfaceId, payload)
            else -> throw PluginProcessException("PARAM_INVALID", "overlayId or surface required")
        }
    } catch (e: PluginOverlayException) {
        throw PluginProcessException(e.code, e.message.orEmpty())
    }

    private fun overlayUpdate(pluginId: String, params: JsonObject): JsonElement = try {
        overlayManager.update(pluginId, PluginHostPaths.requireString(params, "overlayId"), params)
    } catch (e: PluginOverlayException) {
        throw PluginProcessException(e.code, e.message.orEmpty())
    }

    private fun tip(pluginId: String, params: JsonObject): JsonElement = try {
        tipManager.show(pluginId, params)
    } catch (e: PluginTipException) {
        throw PluginProcessException(e.code, e.message.orEmpty())
    }

    private suspend fun shizukuExec(pluginId: String, params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_SHIZUKU_TIMEOUT_SECONDS
        return try {
            val result = shizukuRunner.exec(pluginId, command, timeoutSeconds)
            buildJsonObject {
                put("output", result.output)
                put("exitCode", result.exitCode)
                put("truncated", result.truncated)
            }
        } catch (e: PluginShizukuException) {
            throw PluginProcessException(e.code, e.message.orEmpty())
        }
    }

    private suspend fun bash(params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command")
        if (command.isBlank()) throw PluginProcessException("PARAM_INVALID", "empty command")
        if (command.length > PluginHostPaths.MAX_COMMAND_CHARS) {
            throw PluginProcessException("PARAM_INVALID", "command too long")
        }
        val timeoutSeconds = (params["timeout"] as? JsonPrimitive)?.longOrNull
            ?: PluginHostPaths.DEFAULT_BASH_TIMEOUT_SECONDS
        val timeoutMs = timeoutSeconds.coerceIn(1L, PluginHostPaths.MAX_BASH_TIMEOUT_SECONDS) * 1000L
        val result = commandEngine.runCommandSyncWithExit(command, workspaceRepository.currentPath(), timeoutMs)
        return buildJsonObject {
            put("output", result.output)
            put("exitCode", result.exitCode)
            put("truncated", result.outputTruncated)
        }
    }

    private suspend fun terminalRun(pluginId: String, params: JsonObject): JsonElement {
        val command = PluginHostPaths.requireString(params, "command").trim()
        val title = (params["title"] as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TERMINAL_TITLE_CHARS)
        return try {
            val tabId = terminalRunner.start(pluginId, command, title)
            buildJsonObject {
                put("tabId", tabId)
                put("state", "running")
            }
        } catch (e: PluginTerminalException) {
            throw PluginProcessException(e.code, e.message)
        }
    }

    private suspend fun terminalPoll(pluginId: String, params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val offset = (params["offset"] as? JsonPrimitive)?.longOrNull ?: 0L
        return try {
            val poll = terminalRunner.poll(pluginId, tabId, offset)
            buildJsonObject {
                put("output", poll.output)
                put("offset", poll.offset)
                put("state", poll.state)
                poll.exitCode?.let { put("exitCode", it) }
                put("truncated", poll.truncated)
            }
        } catch (e: PluginTerminalException) {
            throw PluginProcessException(e.code, e.message)
        }
    }

    private suspend fun terminalStop(pluginId: String, params: JsonObject, close: Boolean): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        try {
            if (close) terminalRunner.close(pluginId, tabId) else terminalRunner.stop(pluginId, tabId)
        } catch (e: PluginTerminalException) {
            throw PluginProcessException(e.code, e.message)
        }
        return buildJsonObject { put("ok", true) }
    }

    private suspend fun <T> hostCall(block: suspend () -> T): T = try {
        block()
    } catch (e: PluginHostException) {
        throw PluginProcessException(e.code, e.message)
    }

    private suspend fun terminalCall(block: suspend () -> JsonElement): JsonElement = try {
        block()
    } catch (e: PluginTerminalException) {
        throw PluginProcessException(e.code, e.message)
    }

    private suspend fun terminalWrite(pluginId: String, params: JsonObject): JsonElement {
        val tabId = PluginHostPaths.requireString(params, "tabId")
        val text = PluginHostPaths.requireString(params, "text")
        val appendNewline = (params["appendNewline"] as? JsonPrimitive)?.booleanOrNull ?: true
        val written = try {
            terminalRunner.write(pluginId, tabId, text, appendNewline)
        } catch (e: PluginTerminalException) {
            throw PluginProcessException(e.code, e.message)
        }
        return buildJsonObject { put("ok", written) }
    }

    private fun isActive(pluginId: String): Boolean {
        pluginManager.ensureLoaded()
        return pluginManager.plugins.value.any { it.id == pluginId && it.isActive }
    }

    private fun requireActive(pluginId: String) {
        if (!isActive(pluginId)) {
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
    }

    private fun handleNotification(pluginId: String, method: String, params: JsonObject) {
        if (method != "plugin.event") {
            FileLogger.d(TAG, "[$pluginId] 忽略插件通知: $method")
            return
        }
        eventBus.publish(pluginId, params)
    }

    /** 本地默认容器；SSH 模式下插件进程仍跑在手机端，与 MCP stdio 一致。 */
    /**
     * 插件进程用哪个容器跑。
     *
     * 必须跟终端、工具链用同一个：读 [ContainerSettingsRepository.activeProfileFlow]（当前激活的容器）。
     * 之前读的是 defaultContainerIdFlow（远程模式的默认容器），用户把自定义镜像设成当前容器后，
     * 这里仍指向内置 Alpine，于是终端能进、插件却报「容器尚未初始化」。
     * 非本地 proot（远程 SSH 等）回退内置 Alpine，与旧行为一致。
     */
    private suspend fun runtimeProfile(): ContainerProfile {
        val active = containerSettingsRepository.activeProfileFlow.first()
        return if (active.mode == ExecutionMode.LOCAL_PROOT) active else ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 宿主文件路径映射到容器内路径：插件目录由 `.aiverse` 绑定挂载到 `/root/.aiverse`。
     *
     * TODO(适配): AiVerse [ContainerInstaller] 尚无 containerPathFor（P2c 补）；按 aiverseDir →
     * `/root/.aiverse` 的等价绑定映射实现，待 P2c 提供 containerPathFor 后改为委托调用。
     */
    private fun containerDir(file: File): String {
        val root = installer.aiverseDir.canonicalFile
        val relative = runCatching { file.canonicalFile.relativeTo(root) }.getOrNull()
            ?: return file.absolutePath
        return "/root/.aiverse/" + relative.invariantSeparatorsPath
    }

    /**
     * 插件数据目录在当前执行环境中的真实路径，并确保目录存在。本地落在
     * `/…/.aiverse/plugins/data/<id>`（与宿主 `aiverseDir/plugins/data/<id>` 同一份），远程落在服务器同名路径。
     */
    private suspend fun dataDirectory(pluginId: String): String {
        val path = PluginHostPaths.dataRoot(homeResolver, pluginId)
        fileAccess.mkdirs(path)
        return path
    }

    /** 当前工作区在插件可见路径空间里的根：远程用远端绝对路径，本地用容器内 `$HOME/workspace`。 */
    private fun workspaceRoot(): String =
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            workspaceRepository.currentPath()
        } else {
            homeResolver.home().trimEnd('/') + "/workspace"
        }

    private fun pruneIdle() {
        val now = System.currentTimeMillis()
        synchronized(processes) {
            processes.entries
                .filter { !it.value.resident && now - it.value.lastUsed > IDLE_TIMEOUT_MS }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    private companion object {
        const val TAG = "PluginRuntime"
        const val TOOL_TIMEOUT_MS = 120_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
        const val MAX_TERMINAL_TITLE_CHARS = 120
        const val DEFAULT_SHIZUKU_TIMEOUT_SECONDS = 120L
        const val OVERLAY_MESSAGE_TIMEOUT_MS = 30_000L
        const val PROCESS_ORIGIN = "process"
    }
}
