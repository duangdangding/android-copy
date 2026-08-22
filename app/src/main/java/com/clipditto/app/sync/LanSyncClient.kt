package com.clipditto.app.sync

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 配对码错误或缺失 */
class NeedPairingException : Exception("need_pairing")

/** 对方关闭了共享 */
class SharingOffException : Exception("sharing_off")

/** 对方拒绝了配对 */
class PairRejectedException : Exception("rejected")

/** 对方需要手动确认，但对方设备页不在前台 */
class PairNeedConfirmException : Exception("need_confirm")

/** 对方已取消与本机的配对（本机被拉黑） */
class UnpairedException : Exception("unpaired")

/** 对方把本机加入了黑名单。deviceId 已知时携带（/info 的 403 响应里有） */
class BlockedByException(val blockedByDeviceId: String? = null) : Exception("blocked")

/**
 * 局域网同步客户端：向其他设备的 [LanSyncServer] 拉取剪贴板内容。
 *
 * 请求里会带上本机的设备信息（X-Device-Id / X-Device-Name / X-My-Port），
 * 并在带配对码的请求中附上本机配对码（X-My-Token）——对方验证通过后
 * 即可自动反向配对，实现"一方配对，双方生效"。
 */
class LanSyncClient(
    private val settings: LanSettings,
    private val myServerPort: () -> Int = { 0 }
) {

    private val gson = Gson()

    /** 设备信息（无需配对码）。timeoutMs 用于深度扫描时的快速探测 */
    fun fetchInfo(host: String, port: Int, timeoutMs: Int = 5_000): JsonObject {
        val conn = connect(host, port, "/info", token = null, timeoutMs = timeoutMs)
        try {
            when (conn.responseCode) {
                200 -> return gson.fromJson(readText(conn), JsonObject::class.java)
                403 -> {
                    // /info 的 403 只可能是"被对方拉黑"，响应体带对方 deviceId
                    val body = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                            ?.let { gson.fromJson(it, JsonObject::class.java) }
                    }.getOrNull()
                    throw BlockedByException(body?.get("deviceId")?.asString)
                }
                else -> error("info http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 增量拉取记录 */
    fun fetchClips(device: LanDevice, since: Long): JsonArray {
        val conn = connect(
            device.host!!, device.port, "/clips?since=$since", token = device.token
        )
        try {
            when (conn.responseCode) {
                200 -> return gson.fromJson(readText(conn), JsonArray::class.java)
                401 -> throw NeedPairingException()
                403 -> throw forbidden(conn)
                else -> error("clips http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 发起配对请求。对方可能弹窗手动确认，耗时较长，读超时放宽到 35 秒。
     * 成功正常返回；失败抛对应异常。
     */
    fun requestPair(device: LanDevice) {
        val conn = connect(
            device.host!!, device.port, "/pair", token = device.token
        )
        conn.readTimeout = 35_000
        try {
            when (conn.responseCode) {
                200 -> return
                401 -> throw NeedPairingException()
                403 -> throw forbidden(conn)
                409 -> {
                    val body = runCatching {
                        conn.errorStream?.bufferedReader()?.use { it.readText() }
                            ?.let { gson.fromJson(it, JsonObject::class.java) }
                    }.getOrNull()
                    when (body?.get("error")?.asString) {
                        "rejected" -> throw PairRejectedException()
                        else -> throw PairNeedConfirmException()
                    }
                }
                else -> error("pair http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 下载媒体文件到本地 */
    fun downloadFile(device: LanDevice, remoteId: Long, out: File) {
        val conn = connect(
            device.host!!, device.port, "/file?id=$remoteId", token = device.token
        )
        try {
            when (conn.responseCode) {
                200 -> conn.inputStream.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                401 -> throw NeedPairingException()
                403 -> throw forbidden(conn)
                else -> error("file http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 主动通知对方：本机已取消配对。对方收到后应立即把本机标记为未配对。
     * 尽力而为：对方不在线时静默失败（对方下次同步会走被动流程）。
     */
    fun notifyUnpair(device: LanDevice) {
        val conn = connect(
            device.host!!, device.port, "/unpair", token = device.token
        )
        try {
            conn.responseCode  // 只关心送达，不关心结果
        } finally {
            conn.disconnect()
        }
    }

    /** 主动通知对方：本机已把你移出黑名单 */
    fun notifyUnblocked(device: LanDevice) {
        val conn = connect(
            device.host!!, device.port, "/unblocked", token = device.token
        )
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 主动通知对方"你已被本机拉黑"（无需配对码，供未配对设备使用）。
     * 对方会回连本机 /info 验证属实后才生效，防止伪造。
     */
    fun notifyBlocked(host: String, port: Int) {
        val conn = connect(host, port, "/blocked", token = null)
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun connect(
        host: String,
        port: Int,
        path: String,
        token: String?,
        timeoutMs: Int = 5_000
    ): HttpURLConnection {
        // IPv6 地址需要用方括号包裹才能拼进 URL
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val conn = URL("http://$h:$port$path").openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = if (timeoutMs < 5_000) timeoutMs else 30_000
        conn.setRequestProperty("X-Device-Id", settings.deviceId)
        conn.setRequestProperty(
            "X-Device-Name",
            java.net.URLEncoder.encode(settings.deviceName, "UTF-8")
        )
        conn.setRequestProperty(
            "X-Device-Model",
            java.net.URLEncoder.encode(settings.deviceModel, "UTF-8")
        )
        if (myServerPort() > 0) {
            conn.setRequestProperty("X-My-Port", myServerPort().toString())
        }
        token?.let {
            conn.setRequestProperty("X-Token", it)
            // 附上本机配对码，对方验证通过后可自动反向配对
            conn.setRequestProperty("X-My-Token", settings.pairingToken)
        }
        return conn
    }

    private fun readText(conn: HttpURLConnection): String =
        conn.inputStream.bufferedReader().use { it.readText() }

    /** 解析 403 响应体：区分"对方关了共享"/"对方取消了配对"/"对方拉黑了本机" */
    private fun forbidden(conn: HttpURLConnection): Exception {
        val err = runCatching {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
                ?.let { gson.fromJson(it, JsonObject::class.java) }
                ?.get("error")?.asString
        }.getOrNull()
        return when (err) {
            "unpaired" -> UnpairedException()
            "blocked" -> BlockedByException()
            else -> SharingOffException()
        }
    }
}
