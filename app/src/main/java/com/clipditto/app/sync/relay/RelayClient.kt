package com.clipditto.app.sync.relay

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 云端中继 WebSocket 客户端：单次连接的「鉴权握手 → 收发循环」。
 * 重连/退避/配置变更由外层 [RelaySyncManager] 的监督循环驱动。
 *
 * 协议与 PC 端 relay.rs 一致：
 * - WSS 建立后服务器先下一次性挑战 nonce，客户端 5 秒（这里放宽到 8 秒）内回 hello，
 *   携带 deviceId + 分组 ID + HMAC-SHA256(接入密钥, nonce+deviceId+ts)；
 * - 鉴权通过（welcome）后立即 pull {sinceSeq} 补拉离线条目；
 * - 每 25 秒发应用层 {"op":"ping"}，OkHttp 底层另有 WS ping（20s）双保险。
 */
class RelayClient(
    private val deviceId: () -> String,
    private val deviceName: () -> String
) {

    /** 连接结束的原因，外层据此决定重连策略 */
    enum class CloseReason {
        /** 网络错误/服务器断开：指数退避后重连 */
        NETWORK,
        /** 鉴权被服务器明确拒绝：停止重连，等配置变更 */
        AUTH_FAILED,
        /** 运行期间配置被修改：立即用新配置重连，不退避 */
        CONFIG_CHANGED
    }

    /** 同组在线设备（服务器 peers 广播） */
    data class Peer(val deviceId: String, val name: String)

    /** 事件回调（均在连接协程内触发；onClip 的入库等重活请另起协程） */
    interface Handler {
        fun onStatus(status: String)
        fun onClip(seq: Long, clip: JsonObject)
        fun onAcked(seq: Long)
        fun onPeers(peers: List<Peer>)
        /** 配置代际是否已变化（true = 立刻断开重连） */
        fun configStale(): Boolean
        /** 当前补拉游标 */
        fun lastSeq(): Long
    }

    /** 鉴权握手超时（服务器 5 秒强制断开，客户端略放宽，对齐 PC 端 AUTH_TIMEOUT） */
    private val authTimeoutMs = 8_000L

    /** 应用层心跳间隔（对齐 PC 端 PING_INTERVAL） */
    private val pingIntervalMs = 25_000L

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // WS 长连接必须关闭读超时，保活靠 pingInterval
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** 当前会话的 WebSocket（null = 未连接）；OkHttp 的 send 线程安全且内部排队 */
    @Volatile
    private var session: WebSocket? = null

    /**
     * 投递一条出站消息（已序列化的 JSON）。未连接或发送队列满时丢弃——
     * 本机剪贴板 push 是即发即弃语义，不做本地离线队列（服务器侧暂存已覆盖对方离线）。
     */
    fun trySend(msg: String) {
        session?.send(msg)
    }

    val connected: Boolean get() = session != null

    /**
     * 单次连接：握手 → 补拉 → 收发循环，直到断开。
     * 返回断开原因；本函数挂起期间持续消费服务器消息。
     */
    suspend fun run(url: String, group: String, accessKey: String, handler: Handler): CloseReason =
        withContext(Dispatchers.IO) {
            handler.onStatus("连接中…")
            val incoming = Channel<String>(Channel.UNLIMITED)
            val closed = CompletableDeferred<CloseReason>()

            val ws = http.newWebSocket(Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        incoming.trySend(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.d(TAG, "WS 失败：${t.message}（HTTP ${response?.code ?: "-"}）")
                        incoming.close()
                        closed.complete(CloseReason.NETWORK)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        incoming.close()
                        closed.complete(CloseReason.NETWORK)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                })

            try {
                // ---- 鉴权握手：challenge → hello → welcome ----
                val challengeText = withTimeoutOrNull(authTimeoutMs) { incoming.receiveCatching().getOrNull() }
                val nonce = challengeText?.let { parse(it) }
                    ?.takeIf { it.str("op") == "challenge" }
                    ?.str("nonce")
                if (nonce == null) {
                    handler.onStatus("鉴权失败：服务器未下发挑战（对方是 relay-server 吗？）")
                    return@withContext CloseReason.NETWORK
                }

                val myId = deviceId()
                val ts = System.currentTimeMillis()
                val hello = JsonObject().apply {
                    addProperty("op", "hello")
                    addProperty("deviceId", myId)
                    addProperty("name", deviceName())
                    addProperty("group", group)
                    addProperty("ts", ts)
                    addProperty("auth", RelayCrypto.hmacHex(accessKey, "$nonce$myId$ts"))
                }
                if (!ws.send(gson.toJson(hello))) {
                    handler.onStatus("连接失败：无法发送握手消息")
                    return@withContext CloseReason.NETWORK
                }

                val resp = withTimeoutOrNull(authTimeoutMs) { incoming.receiveCatching().getOrNull() }
                    ?.let { parse(it) }
                when (resp?.str("op")) {
                    "welcome" -> {}
                    "error" -> {
                        val code = resp.str("code") ?: ""
                        handler.onStatus(
                            if (code == "auth_failed") "鉴权失败：接入密钥错误或时间偏差过大"
                            else "鉴权被服务器拒绝（$code）"
                        )
                        return@withContext CloseReason.AUTH_FAILED
                    }
                    null -> {
                        handler.onStatus("鉴权失败：等待服务器响应超时")
                        return@withContext CloseReason.NETWORK
                    }
                    else -> {
                        handler.onStatus("鉴权失败：服务器响应异常")
                        return@withContext CloseReason.NETWORK
                    }
                }

                // ---- 鉴权通过：登记会话，补拉离线条目，进入收发循环 ----
                session = ws
                handler.onStatus("🟢 已连接")
                ws.send(gson.toJson(JsonObject().apply {
                    addProperty("op", "pull")
                    addProperty("sinceSeq", handler.lastSeq())
                }))

                var lastPingAt = System.currentTimeMillis()
                while (coroutineContext.isActive && !closed.isCompleted) {
                    // 1 秒粒度轮询：收消息 / 到点心跳 / 检查配置代际
                    val text = withTimeoutOrNull(1_000) { incoming.receiveCatching().getOrNull() }
                    if (text != null) handleServerMsg(text, handler)
                    if (closed.isCompleted) break

                    val now = System.currentTimeMillis()
                    if (now - lastPingAt >= pingIntervalMs) {
                        lastPingAt = now
                        if (handler.configStale()) {
                            return@withContext CloseReason.CONFIG_CHANGED
                        }
                        if (!ws.send("""{"op":"ping"}""")) break
                    }
                }
                // 循环退出：心跳发送失败 / 服务器已关闭 / 协程取消。
                // closed 未完成的场景（如 send 返回 false）按网络错误处理，不能干等
                if (closed.isCompleted) closed.await() else CloseReason.NETWORK
            } finally {
                session = null
                runCatching { ws.close(1000, null) }
            }
        }

    /** 分发服务器消息：clip / acked 推进游标，peers 更新在线名单，其余忽略 */
    private fun handleServerMsg(text: String, handler: Handler) {
        val v = parse(text) ?: return
        when (v.str("op")) {
            "clip" -> {
                val seq = v.lngOrNull("seq")
                val clip = v.getAsJsonObject("clip")
                if (seq != null && clip != null) handler.onClip(seq, clip)
            }
            // acked 也携带 seq：自己 push 的条目同样推进游标，避免补拉时拉回自己的
            "acked" -> v.lngOrNull("seq")?.let { handler.onAcked(it) }
            "peers" -> {
                val myId = deviceId()
                val list = v.getAsJsonArray("devices")?.mapNotNull { el ->
                    val d = el.asJsonObject
                    val id = d.str("deviceId") ?: return@mapNotNull null
                    if (id == myId) return@mapNotNull null // 名单里不显示自己
                    Peer(id, d.str("name") ?: "")
                }?.sortedBy { it.name } ?: emptyList()
                handler.onPeers(list)
            }
            // pong / error(not_implemented) 等暂不需要处理
        }
    }

    private fun parse(text: String): JsonObject? = runCatching {
        gson.fromJson(text, JsonObject::class.java)
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.lngOrNull(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) get(key).asLong else null

    companion object {
        private const val TAG = "RelayClient"
    }
}
