package com.aiverse.feature.web

import android.util.Log
import com.aiverse.feature.agent.data.local.dao.AgentMessageDao
import com.aiverse.feature.agent.data.local.dao.ChatSessionDao
import com.aiverse.feature.agent.data.repository.FavoriteRepository
import com.aiverse.feature.web.data.WebServerSettingsRepository
import com.aiverse.feature.web.routes.favoriteRoutes
import com.aiverse.feature.web.routes.sessionRoutes
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** 网页端服务器运行状态，供设置页展示。 */
data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = WebServerSettingsRepository.DEFAULT_PORT,
    val localhostOnly: Boolean = false,
    /** 局域网访问地址；仅本机访问时为 null。 */
    val lanAddress: String? = null,
    val error: String? = null
) {
    val localAddress: String get() = "http://localhost:$port"
}

/**
 * 网页端服务器：Ktor CIO，端口与监听范围由 [WebServerSettingsRepository] 配置（默认 7676、0.0.0.0）。
 * 托管 web-ui 静态资源（classpath "static"）与 REST API。启动/停止由设置页控制（App 启动不自动拉起）。
 * 身份验证（JWT）默认关闭，仅存储设置，实际鉴权后续接入。
 */
@Singleton
class WebServerManager @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: AgentMessageDao,
    private val favoriteRepository: FavoriteRepository,
    private val settingsRepository: WebServerSettingsRepository
) {
    companion object {
        private const val TAG = "WebServerManager"
    }

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** 局域网 IP：取第一个非回环 IPv4 地址；取不到返回 null。 */
    private fun lanIp(): String? = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    suspend fun start() {
        if (server != null) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        // Ktor 启动（bind + 插件初始化）与网络接口枚举都是阻塞 IO，切到 IO 线程避免冻结 Main/UI。
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val port = settingsRepository.currentPort()
                val localhostOnly = settingsRepository.currentLocalhostOnly()
                val host = if (localhostOnly) "127.0.0.1" else "0.0.0.0"
                val running = embeddedServer(CIO, port = port, host = host, module = {
                    install(Compression)
                    install(CORS) {
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowNonSimpleContentTypes = true
                        anyHost()
                        anyMethod()
                    }
                    install(SSE)
                    install(DefaultHeaders)
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                    }
                    routing {
                        staticResources("/", "static") {
                            default("index.html")
                            enableAutoHeadResponse()
                            singlePageApplication()
                        }
                        sessionRoutes(sessionDao, messageDao)
                        favoriteRoutes(favoriteRepository)
                    }
                })
                running.start(wait = false)
                server = running
                WebServerState(
                    isRunning = true,
                    isLoading = false,
                    port = port,
                    localhostOnly = localhostOnly,
                    lanAddress = if (localhostOnly) null else lanIp()?.let { "http://$it:$port" }
                )
            }
        }
        _state.value = result.getOrElse { t ->
            Log.e(TAG, "Failed to start web server", t)
            _state.value.copy(isLoading = false, error = t.message ?: t.javaClass.simpleName)
        }
        if (_state.value.isRunning) Log.i(TAG, "Web server started")
    }

    @Synchronized
    fun stop() {
        server?.stop(500, 1000)
        server = null
        _state.value = WebServerState(
            port = _state.value.port,
            localhostOnly = _state.value.localhostOnly
        )
    }
}
