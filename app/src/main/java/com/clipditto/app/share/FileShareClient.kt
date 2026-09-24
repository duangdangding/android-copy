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

    /** /fs/send 路由不存在：对方是 PC/旧协议，降级 /recv 重试 */
    private class RouteNotFoundException : Exception()

    private enum class Protocol { FS, RECV, UNKNOWN }

    /**
     * 发送一个文件。成功正常返回，失败抛 [FsSendException]。
     * @param size 文件准确大小（固定长度传输，接收方据此校验完整性）
     * @param openStream 打开文件内容流的工厂（可能被调用两次：/fs/send 404 降级 /recv 时）
     */
    fun send(
        host: String,
        port: Int,
        fileName: String,
        mime: String?,
        size: Long,
        openStream: () -> InputStream?
    ) {
        when (probeProtocol(host, port)) {
            Protocol.RECV -> sendRecv(host, port, fileName, size, openStream)
            else -> try {
                sendFs(host, port, fileName, mime, size, openStream)
            } catch (e: RouteNotFoundException) {
                // 对方没有 /fs/send（PC 端/旧版）：降级走 /recv
                sendRecv(host, port, fileName, size, openStream)
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
        size: Long, openStream: () -> InputStream?
    ) {
        val conn = URL("http://${bracket(host)}:$port/fs/send")
            .openConnection() as HttpURLConnection
        // 对方手动确认最多 30 秒，读超时留足余量
        conn.readTimeout = 45_000
        conn.setRequestProperty("X-File-Name", URLEncoder.encode(fileName, "UTF-8"))
        mime?.let { conn.setRequestProperty("X-Mime", URLEncoder.encode(it, "UTF-8")) }
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
        size: Long, openStream: () -> InputStream?
    ) {
        val q = "name=${URLEncoder.encode(fileName, "UTF-8")}&size=$size"
        val conn = URL("http://${bracket(host)}:$port/recv?$q")
            .openConnection() as HttpURLConnection
        // PC 端弹窗手动确认最多 60 秒，读超时必须大于它
        conn.readTimeout = 75_000
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
        try {
            conn.outputStream.use { out -> input.use { it.copyTo(out) } }
        } catch (e: SocketTimeoutException) {
            throw FsSendException("对方无响应")
        } catch (e: IOException) {
            throw FsSendException("连接失败：${e.message}")
        }

        return try {
            conn.responseCode
        } catch (e: SocketTimeoutException) {
            throw FsSendException("对方无响应（等待确认超时）")
        } catch (e: IOException) {
            throw FsSendException("连接失败：${e.message}")
        }
    }
}
