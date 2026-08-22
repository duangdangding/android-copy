package com.clipditto.app.sync

import android.util.Log
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 本机局域网 HTTP 服务，向其他设备提供剪贴板内容。
 *
 * 接口：
 * - GET /info           设备信息（无需配对码）
 * - GET /clips?since=   增量拉取记录（需配对码 + 共享开关开启）
 * - GET /file?id=       拉取媒体文件（需配对码 + 共享开关开启）
 *
 * 配对码通过 X-Token 请求头传递；请求方在 X-Device-Id 里带自己的 deviceId，
 * 服务端据此过滤掉"本来就来自该设备"的记录，避免内容在两台设备间循环复制。
 */
class LanSyncServer(
    private val settings: LanSettings,
    private val repo: ClipRepository,
    /** 对方通过鉴权后回调，用于自动反向配对（一方手动配对，双方生效） */
    private val onAutoPair: (LanDevice) -> Unit = {},
    /**
     * 配对确认回调：返回 null 表示当前无人可确认（设备页不在前台），
     * true/false 表示用户同意/拒绝。可能在 HTTP 线程上阻塞等待用户操作。
     */
    private val pairApproval: (LanDevice) -> Boolean? = { null },
    /** 收到对方的解除配对通知时回调（参数为对方 deviceId） */
    private val onUnpaired: (String) -> Unit = {},
    /** 收到对方"已把你移出黑名单"通知时回调（参数为对方 deviceId） */
    private val onUnblocked: (String) -> Unit = {},
    /** 收到"你已被对方拉黑"通知（通过回连验证后）回调 */
    private val onBlockedBy: (String) -> Unit = {}
) {
    private val gson = Gson()
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val socket = try {
            ServerSocket(settings.serverPort)
        } catch (e: Exception) {
            Log.w(TAG, "端口 ${settings.serverPort} 被占用，改用系统分配端口")
            ServerSocket(0)  // 端口被占用时让系统分配
        }
        serverSocket = socket
        running = true
        pool.execute { acceptLoop(socket) }
        Log.d(TAG, "局域网服务已启动，端口 ${socket.localPort}")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept 失败: ${e.message}")
                break
            }
            pool.execute { runCatching { handle(client) }.onFailure {
                Log.w(TAG, "处理请求失败: ${it.message}")
            } }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        client.use { c ->
            val input = BufferedInputStream(c.getInputStream())
            val output = c.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                respond(output, 405, "text/plain", "Method Not Allowed")
                return
            }
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val path = parts[1].substringBefore('?')
            val query = parseQuery(parts[1].substringAfter('?', ""))
            val clientIp = c.inetAddress?.hostAddress ?: ""

            when (path) {
                "/info" -> handleInfo(output, headers)
                "/pair" -> handlePair(output, headers, clientIp)
                "/unpair" -> handleUnpair(output, headers)
                "/unblocked" -> handleUnblocked(output, headers)
                "/blocked" -> handleBlockedNotice(output, headers, clientIp)
                "/clips" -> handleClips(output, headers, query, clientIp)
                "/file" -> handleFile(output, headers, query, clientIp)
                else -> respond(output, 404, "text/plain", "Not Found")
            }
        }
    }

    // ---------------- 路由处理 ----------------

    private fun handleInfo(output: OutputStream, headers: Map<String, String>) {
        // 黑名单设备连 /info 都拿不到：对方扫描时无法确认本机存在。
        // 响应里带上本机信息，让对方知道"是被拉黑了"，从而在其本地隐藏本机。
        val requester = headers["x-device-id"]
        if (requester != null && requester in settings.getBlockedDevices()) {
            val json = JsonObject().apply {
                addProperty("error", "blocked")
                addProperty("deviceId", settings.deviceId)
                addProperty("name", settings.deviceName)
                addProperty("model", settings.deviceModel)
            }
            respond(output, 403, "application/json", gson.toJson(json))
            return
        }
        val json = JsonObject().apply {
            addProperty("deviceId", settings.deviceId)
            addProperty("name", settings.deviceName)
            addProperty("model", settings.deviceModel)
            addProperty("sharing", settings.sharing)
            addProperty("version", PROTOCOL_VERSION)
        }
        respond(output, 200, "application/json", gson.toJson(json))
    }

    private fun handleClips(
        output: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>,
        clientIp: String
    ) {
        if (!authorize(output, headers)) return
        if (rejectIfBlocked(output, headers)) return
        if (rejectIfUnpaired(output, headers)) return
        maybeAutoPair(headers, clientIp)
        val since = query["since"]?.toLongOrNull() ?: 0L
        val requesterId = headers["x-device-id"] ?: ""
        val items = runBlocking { repo.getSince(since) }
            // 环回防护：不回传"本来就来自请求方"的记录
            .filter { it.remoteDeviceId == null || it.remoteDeviceId != requesterId }
        val list = items.map { it.toWire() }
        respond(output, 200, "application/json", gson.toJson(list))
    }

    private fun handleFile(
        output: OutputStream,
        headers: Map<String, String>,
        query: Map<String, String>,
        clientIp: String
    ) {
        if (!authorize(output, headers)) return
        if (rejectIfBlocked(output, headers)) return
        if (rejectIfUnpaired(output, headers)) return
        maybeAutoPair(headers, clientIp)
        val id = query["id"]?.toLongOrNull()
        val item = id?.let { runBlocking { repo.getById(it) } }
        val file = item?.filePath?.let { File(it) }
        if (file == null || !file.exists()) {
            respond(output, 404, "text/plain", "File Not Found")
            return
        }
        respondFile(output, file)
    }

    /**
     * 配对请求：校验配对码后，根据「自动同意配对」设置决定直接通过
     * 还是弹窗等用户手动确认。通过后自动反向配对。
     */
    private fun handlePair(output: OutputStream, headers: Map<String, String>, clientIp: String) {
        if (!authorize(output, headers)) return
        val requester = buildRequester(headers, clientIp) ?: run {
            respond(output, 400, "application/json", """{"error":"bad_request"}""")
            return
        }
        val blocked = requester.deviceId in settings.getBlockedDevices()
        when {
            // 被显式拉黑的设备必须手动同意（即使开了自动同意），防止绕过确认悄悄重连
            blocked -> when (pairApproval(requester)) {
                true -> {
                    settings.unblockDevice(requester.deviceId)
                    onAutoPair(requester)
                    respond(output, 200, "application/json", """{"result":"ok"}""")
                }
                false -> respond(output, 409, "application/json", """{"error":"rejected"}""")
                null -> respond(output, 409, "application/json", """{"error":"need_confirm"}""")
            }
            settings.autoAcceptPair -> {
                onAutoPair(requester)
                respond(output, 200, "application/json", """{"result":"ok"}""")
            }
            else -> when (pairApproval(requester)) {
                true -> {
                    onAutoPair(requester)
                    respond(output, 200, "application/json", """{"result":"ok"}""")
                }
                false -> respond(output, 409, "application/json", """{"error":"rejected"}""")
                null -> respond(output, 409, "application/json", """{"error":"need_confirm"}""")
            }
        }
    }

    /** 被本机拉黑的设备：拒绝其内容请求并明确告知（对方收到后自动解除本地配对） */
    private fun rejectIfBlocked(output: OutputStream, headers: Map<String, String>): Boolean {
        val deviceId = headers["x-device-id"] ?: return false
        if (deviceId !in settings.getBlockedDevices()) return false
        Log.d(TAG, "已拉黑设备请求被拒: $deviceId")
        respond(output, 403, "application/json", """{"error":"unpaired"}""")
        return true
    }

    /**
     * 未配对设备（含已取消配对但仍持有旧配对码的）不允许拉取内容：
     * 明确返回 unpaired，对方客户端收到后会自动解除本地配对状态。
     * 必须在 maybeAutoPair 之前检查，否则请求方会被自动重新登记为已配对。
     */
    private fun rejectIfUnpaired(output: OutputStream, headers: Map<String, String>): Boolean {
        val deviceId = headers["x-device-id"]
        if (deviceId != null && deviceId in settings.getPairedDevices()) return false
        Log.d(TAG, "未配对设备内容请求被拒: $deviceId")
        respond(output, 403, "application/json", """{"error":"unpaired"}""")
        return true
    }

    /**
     * 对方主动通知"已取消配对"：只校验配对码（共享开关不影响解除配对），
     * 通过则立即在本地解除与该设备的配对。取消配对 ≠ 拉黑，不记 blockedBy。
     */
    private fun handleUnpair(output: OutputStream, headers: Map<String, String>) {
        val token = headers["x-token"]
        if (token != settings.pairingToken) {
            respond(output, 401, "application/json", """{"error":"bad_token"}""")
            return
        }
        val deviceId = headers["x-device-id"]
        if (deviceId.isNullOrBlank() || deviceId == settings.deviceId) {
            respond(output, 400, "application/json", """{"error":"bad_request"}""")
            return
        }
        Log.d(TAG, "收到对方解除配对通知: $deviceId")
        onUnpaired(deviceId)
        respond(output, 200, "application/json", """{"result":"ok"}""")
    }

    /** 对方通知"已把你移出黑名单"：清除 blockedBy 记录，恢复可见/可操作 */
    private fun handleUnblocked(output: OutputStream, headers: Map<String, String>) {
        val token = headers["x-token"]
        if (token != settings.pairingToken) {
            respond(output, 401, "application/json", """{"error":"bad_token"}""")
            return
        }
        val deviceId = headers["x-device-id"]
        if (deviceId.isNullOrBlank() || deviceId == settings.deviceId) {
            respond(output, 400, "application/json", """{"error":"bad_request"}""")
            return
        }
        Log.d(TAG, "收到对方移出黑名单通知: $deviceId")
        onUnblocked(deviceId)
        respond(output, 200, "application/json", """{"result":"ok"}""")
    }

    /**
     * 对方（可能未配对）通知"你已被我拉黑"。无需配对码，但为防止伪造，
     * 回连对方 /info 验证确实被拒（403 blocked）后才生效。
     */
    private fun handleBlockedNotice(
        output: OutputStream,
        headers: Map<String, String>,
        clientIp: String
    ) {
        val deviceId = headers["x-device-id"]
        val port = headers["x-my-port"]?.toIntOrNull() ?: 0
        if (deviceId.isNullOrBlank() || deviceId == settings.deviceId || port <= 0) {
            respond(output, 400, "application/json", """{"error":"bad_request"}""")
            return
        }
        val verified = try {
            // 对方 /info 返回 200：说明对方没拉黑本机，此通知是伪造的
            LanSyncClient(settings).fetchInfo(clientIp, port, timeoutMs = 2_000)
            false
        } catch (e: BlockedByException) {
            true   // 403 blocked：确认属实
        } catch (e: Exception) {
            false  // 连不上对方，无法验证，保守忽略
        }
        if (verified) {
            Log.d(TAG, "确认被 $deviceId 拉黑（已回连验证）")
            onBlockedBy(deviceId)
        }
        respond(output, 200, "application/json", """{"result":"ok","verified":$verified}""")
    }

    /** 从请求头构造请求方设备信息；缺少必要字段返回 null */
    private fun buildRequester(headers: Map<String, String>, clientIp: String): LanDevice? {
        val deviceId = headers["x-device-id"] ?: return null
        if (deviceId == settings.deviceId) return null
        val theirToken = headers["x-my-token"] ?: return null
        val name = headers["x-device-name"]
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: "未知设备"
        val model = headers["x-device-model"]
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val port = headers["x-my-port"]?.toIntOrNull() ?: 0
        return LanDevice(
            deviceId = deviceId,
            name = name,
            model = model,
            host = clientIp,
            port = port,
            sharing = true,
            paired = true,
            token = theirToken,
            online = true
        )
    }

    /**
     * 自动反向配对：对方带着有效配对码请求时，会附上它自己的配对码和监听端口，
     * 这里把它存为已配对设备，本机无需再手动输入对方配对码。
     */
    private fun maybeAutoPair(headers: Map<String, String>, clientIp: String) {
        val requester = buildRequester(headers, clientIp) ?: return
        if (requester.deviceId in settings.getBlockedDevices()) return  // 已拉黑，不自动恢复
        val existing = settings.getPairedDevices()[requester.deviceId]
        // 已配对且配对码/名称/端口都没变：无需更新
        if (existing != null && existing.token == requester.token &&
            existing.name == requester.name && existing.port == requester.port
        ) return
        Log.d(TAG, "自动反向配对/更新: ${requester.name}(${requester.deviceId})")
        onAutoPair(requester)
    }

    /** 校验共享开关 + 配对码；失败时已写出错误响应并返回 false */
    private fun authorize(output: OutputStream, headers: Map<String, String>): Boolean {
        if (!settings.sharing) {
            Log.w(TAG, "鉴权失败：共享未开启")
            respond(output, 403, "application/json", """{"error":"sharing_off"}""")
            return false
        }
        val token = headers["x-token"]
        if (token != settings.pairingToken) {
            Log.w(TAG, "鉴权失败：token 头=${token != null} 长度=${token?.length}（期望 6）")
            respond(output, 401, "application/json", """{"error":"bad_token"}""")
            return false
        }
        return true
    }

    // ---------------- 响应写出 ----------------

    private fun respond(output: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code ${reason(code)}\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun respondFile(output: OutputStream, file: File) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        file.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
    }

    // ---------------- 工具 ----------------

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val ch = input.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) {
                return if (prev == '\r'.code) sb.substring(0, sb.length - 1) else sb.toString()
            }
            sb.append(ch.toChar())
            prev = ch
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isBlank()) return emptyMap()
        return q.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx <= 0) null
            else urlDecode(it.substring(0, idx)) to urlDecode(it.substring(idx + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String =
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())

    /** 记录 → 传输格式。媒体记录带文件名/大小，内容走 /file 单独下载 */
    private fun ClipItem.toWire(): JsonObject {
        val o = JsonObject()
        o.addProperty("id", id)
        o.addProperty("type", type)
        o.addProperty("text", text)
        o.addProperty("mimeType", mimeType)
        o.addProperty("sourceApp", sourceApp)
        o.addProperty("timestamp", timestamp)
        o.addProperty("remoteDeviceId", remoteDeviceId ?: settings.deviceId)
        o.addProperty("remoteId", remoteId ?: id)
        filePath?.let { p ->
            val f = File(p)
            if (f.exists()) {
                o.addProperty("fileName", f.name)
                o.addProperty("fileSize", f.length())
            }
        }
        return o
    }

    companion object {
        private const val TAG = "LanSyncServer"
        const val PROTOCOL_VERSION = 1
    }
}
