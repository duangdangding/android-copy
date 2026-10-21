package com.clipditto.app.share

import com.clipditto.app.sync.LanSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * 文件共享发送客户端：向其他设备推送文件（传输端口与局域网同步共用，
 * 默认 [LanSettings.DEFAULT_PORT]）。全部方法为阻塞调用，必须在 IO 线程上使用。
 *
 * 协议自动选择（发送前 2 秒探测 GET /info）：
 * - 响应带 fs 字段 → 新协议 POST /fs/send（本 App 新版）
 * - 200 但无 fs 字段 → PC 端（copy-pc）/旧协议，走 POST /recv?name=&size=
 * - 探测连不上 → 先试 /fs/send，收到 404 自动降级 /recv（新旧设备都兼容）
 */
class FileShareClient(private val lanSettings: LanSettings) {

    private val gson = Gson()

    /** 发送失败原因，message 为可直接展示的中文 */
    class FsSendException(message: String) : Exception(message)

    /** 用户主动取消：置位后当前连接断开、后续文件不再发送 */
    @Volatile
    private var cancelled = false

    /** 正在传输的连接（取消时从 UI 线程断开它，中断阻塞中的读写） */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    /** 取消当前批次发送：阻塞中的连接会立即失败，已传完的文件不受影响 */
    fun cancel() {
        cancelled = true
        runCatching { activeConn?.disconnect() }
    }

    /** 新一批发送前重置取消标记 */
    fun resetCancel() {
        cancelled = false
    }

    private fun throwIfCancelled() {
        if (cancelled) throw FsSendException("已取消发送")
    }

    /** /fs/send 路由不存在：对方是 PC/旧协议，降级 /recv 重试 */
    private class RouteNotFoundException : Exception()

    /** 批量清单提议结果 */
    sealed class BatchPlan {
        /** 对方勾选的文件序号（空 = 全部拒绝） */
        class Accepted(val indices: List<Int>) : BatchPlan()

        /** 对方是旧版（无 /batch 端点），回退逐文件发送（逐个确认） */
        object Unsupported : BatchPlan()

        /** 失败（连接/超时/被拒等），message 可直接展示 */
        class Failed(val error: FsSendException) : BatchPlan()
    }

    /**
     * 先交批量清单（POST /batch）：对方在待接收列表逐个勾选后返回接受的文件序号。
     * 旧版对端返回 404 → [BatchPlan.Unsupported]；对方逐个勾选最多 120 秒，读超时留足余量。
     */
    fun proposeBatch(
        host: String, port: Int, batchId: String, files: List<Pair<String, Long>>
    ): BatchPlan {
        throwIfCancelled()
        val conn = URL("http://${bracket(host)}:$port/batch").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 150_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Device-Id", lanSettings.deviceId)
            conn.setRequestProperty(
                "X-Device-Name", URLEncoder.encode(lanSettings.deviceName, "UTF-8")
            )
            conn.setRequestProperty(
                "X-Device-Model", URLEncoder.encode(lanSettings.deviceModel, "UTF-8")
            )
            val body = gson.toJson(JsonObject().apply {
                addProperty("batchId", batchId)
                add("files", gson.toJsonTree(files.map {
                    JsonObject().apply {
                        addProperty("name", it.first)
                        addProperty("size", it.second)
                    }
                }))
            }).toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size.toLong())
            activeConn = conn
            conn.outputStream.use { it.write(body) }
            throwIfCancelled()
            when (val code = conn.responseCode) {
                200 -> {
                    val resp = gson.fromJson(
                        conn.inputStream.bufferedReader().use { it.readText() },
                        JsonObject::class.java
                    )
                    val arr = resp?.getAsJsonArray("accepted")
                    BatchPlan.Accepted(arr?.map { it.asInt } ?: emptyList())
                }
                404 -> BatchPlan.Unsupported
                409 -> BatchPlan.Failed(FsSendException("等待对方确认超时"))
                403 -> BatchPlan.Failed(FsSendException("对方拒绝接收"))
                else -> BatchPlan.Failed(FsSendException("批量请求失败（HTTP $code）"))
            }
        } catch (e: SocketTimeoutException) {
            BatchPlan.Failed(FsSendException("对方无响应（等待确认超时）"))
        } catch (e: IOException) {
            if (cancelled) BatchPlan.Failed(FsSendException("已取消发送"))
            else BatchPlan.Failed(FsSendException("连接失败：${e.message}"))
        } finally {
            if (activeConn === conn) activeConn = null
            conn.disconnect()
        }
    }

    private enum class Protocol { FS, RECV, UNKNOWN }

    /**
     * 发送一个文件。成功正常返回，失败抛 [FsSendException]。
     * @param size 文件准确大小（固定长度传输，接收方据此校验完整性）
     * @param batchId 批量清单 id（对方已勾选，免确认）；null 表示逐文件发送
     * @param openStream 打开文件内容流的工厂（可能被调用两次：/fs/send 404 降级 /recv 时）
     */
    fun send(
        host: String,
        port: Int,
        fileName: String,
        mime: String?,
        size: Long,
        batchId: String? = null,
        openStream: () -> InputStream?
    ) {
        when (probeProtocol(host, port)) {
            Protocol.RECV -> sendRecv(host, port, fileName, size, batchId, openStream)
            else -> try {
                sendFs(host, port, fileName, mime, size, batchId, openStream)
            } catch (e: RouteNotFoundException) {
                // 对方没有 /fs/send（PC 端/旧版）：降级走 /recv
                sendRecv(host, port, fileName, size, batchId, openStream)
            }
        }
    }

    // ---------------- 协议探测 ----------------

    /**
     * 发送前探测 GET /info：有 fs 字段 → 新协议（fs=0 说明未开启接收，直接失败）；
     * 无 fs 字段的 200 → PC/旧协议；连不上/解析失败 → UNKNOWN（不阻断发送）。
     */
    private fun probeProtocol(host: String, port: Int): Protocol {
        val conn = URL("http://${bracket(host)}:$port/info").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = gson.fromJson(body, JsonObject::class.java)
            if (json?.has("fs") == true) {
                if (json.get("fs").asInt == 0) throw FsSendException("对方未开启文件接收")
                Protocol.FS
            } else {
                Protocol.RECV
            }
        } catch (e: FsSendException) {
            throw e
        } catch (e: Exception) {
            Protocol.UNKNOWN  // 探测失败照常发送，由真实路由响应兜底
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 新协议：POST /fs/send ----------------

    private fun sendFs(
        host: String, port: Int, fileName: String, mime: String?,
        size: Long, batchId: String?, openStream: () -> InputStream?
    ) {
        val conn = URL("http://${bracket(host)}:$port/fs/send")
            .openConnection() as HttpURLConnection
        // 对方手动确认最多 30 秒，读超时留足余量
        conn.readTimeout = 45_000
        conn.setRequestProperty("X-File-Name", URLEncoder.encode(fileName, "UTF-8"))
        mime?.let { conn.setRequestProperty("X-Mime", URLEncoder.encode(it, "UTF-8")) }
        batchId?.let { conn.setRequestProperty("X-Batch-Id", it) }
        try {
            when (val code = postFile(conn, size, openStream)) {
                200 -> return
                403 -> throw FsSendException("对方未开启接收")
                404 -> throw RouteNotFoundException()
                409 -> throw FsSendException("对方拒收")
                411 -> throw FsSendException("对方不接受该文件（缺少长度信息）")
                413 -> throw FsSendException("文件过大，对方不接受")
                500 -> throw FsSendException("对方保存文件失败")
                else -> throw FsSendException("发送失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- PC/旧协议：POST /recv ----------------

    private fun sendRecv(
        host: String, port: Int, fileName: String,
        size: Long, batchId: String?, openStream: () -> InputStream?
    ) {
        val q = "name=${URLEncoder.encode(fileName, "UTF-8")}&size=$size"
        val conn = URL("http://${bracket(host)}:$port/recv?$q")
            .openConnection() as HttpURLConnection
        // PC 端弹窗手动确认最多 60 秒，读超时必须大于它
        conn.readTimeout = 75_000
        batchId?.let { conn.setRequestProperty("X-Batch-Id", it) }
        try {
            when (val code = postFile(conn, size, openStream)) {
                200 -> return
                400 -> throw FsSendException("请求格式错误")
                403 -> throw FsSendException("对方拒收")
                409 -> throw FsSendException("对方确认超时（60 秒未操作）")
                500 -> throw FsSendException("对方保存文件失败")
                else -> throw FsSendException("发送失败（HTTP $code）")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 公共 ----------------

    /** IPv6 地址需要用方括号包裹才能拼进 URL */
    private fun bracket(host: String) =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** 公共 POST 发文件：固定长度流式写 body，返回 HTTP 状态码 */
    private fun postFile(
        conn: HttpURLConnection,
        size: Long,
        openStream: () -> InputStream?
    ): Int {
        throwIfCancelled()
        conn.connectTimeout = 5_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(size)
        conn.setRequestProperty("X-Device-Id", lanSettings.deviceId)
        conn.setRequestProperty(
            "X-Device-Name", URLEncoder.encode(lanSettings.deviceName, "UTF-8")
        )
        conn.setRequestProperty(
            "X-Device-Model", URLEncoder.encode(lanSettings.deviceModel, "UTF-8")
        )
        conn.setRequestProperty("X-File-Size", size.toString())

        val input = openStream() ?: throw FsSendException("无法读取文件内容")
        activeConn = conn
        try {
            conn.outputStream.use { out ->
                input.use { ins ->
                    // 分块拷贝：每块检查一次取消标记，大块传输也能及时停下
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        throwIfCancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            throw FsSendException("对方无响应")
        } catch (e: IOException) {
            // 取消时连接被主动断开，表现为 IOException，优先按取消处理
            if (cancelled) throw FsSendException("已取消发送")
            throw FsSendException("连接失败：${e.message}")
        } finally {
            if (activeConn === conn) activeConn = null
        }

        return try {
            conn.responseCode
        } catch (e: SocketTimeoutException) {
            throw FsSendException("对方无响应（等待确认超时）")
        } catch (e: IOException) {
            if (cancelled) throw FsSendException("已取消发送")
            throw FsSendException("连接失败：${e.message}")
        }
    }
}
