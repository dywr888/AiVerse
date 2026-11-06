package com.aiverse.feature.web

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web 发消息桥：把网页端 POST 的消息转发给「当前注册的 AIAgentViewModel」。
 *
 * AIAgentViewModel 是 Activity 作用域的 Hilt ViewModel，WebServerManager 是 Singleton，
 * 两者生命周期不同（Web 服务器可在 App 后台运行，ViewModel 只在界面存活）。
 * 因此 MainActivity 在 ViewModel 就绪后通过 [registerSender] 注册一个发送回调，
 * Web 路由调用 [submit] 时转发给最近的注册者，从而复用完整 Agent 链路
 * （落库、工具权限、流式输出、事件通知），无需在 Web 层复刻一遍执行逻辑。
 *
 * App 前台（或后台但 Activity 未销毁）时发送成功；无注册者时返回 false，
 * 由调用方告知用户「App 未在前台，无法发送」。
 */
@Singleton
class WebMessageBridge @Inject constructor() {

    /** 注册的发送回调列表。参数：目标会话 id、消息内容；返回是否已受理。 */
    private val senders = CopyOnWriteArrayList<(sessionId: String, content: String) -> Boolean>()

    /**
     * 注册发送回调（MainActivity 在 ViewModel 就绪后调用）。
     * 返回一个解绑句柄，Activity 销毁时调用。
     */
    fun registerSender(sender: (sessionId: String, content: String) -> Boolean): () -> Unit {
        senders.add(sender)
        return { senders.remove(sender) }
    }

    /**
     * Web 路由调用：把消息转发给最近注册的发送者。
     * @return true 已受理；false 无注册发送者（App 未在前台）。
     */
    fun submit(sessionId: String, content: String): Boolean {
        if (senders.isEmpty()) return false
        // 只投给最后一个注册者（通常只有 MainActivity 一个）；若它拒绝则尝试更早的。
        for (i in senders.indices.reversed()) {
            if (senders[i](sessionId, content)) return true
        }
        return false
    }
}
