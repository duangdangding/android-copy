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

/** 本机要求加密传输，但对方不支持（旧版本）或响应被篡改 */
class EncryptionRequiredException : Exception("encryption_required")

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

    /** /clips 的拉取结果：记录列表 + 本次是否加密传输（对方是旧版本时为 false） */
    data class ClipsResult(val items: JsonArray, val encrypted: Boolean)

    /**
     * 拉取记录。
     * @param since 只取 timestamp 大于该值的记录（增量水位）
     * @param until 可选，只取 timestamp 不超过该值的记录（"同步某一天"用）
     * @param limit 可选，>0 时只取最新 N 条（"同步最近 N 条"用）
     * 本机开启加密传输时带临时公钥请求加密；对方不支持（旧版本）直接失败，不回退明文。
     */
    fun fetchClips(
        device: LanDevice,
        since: Long,
        until: Long? = null,
        limit: Int = 0,
        includeMine: Boolean = false
    ): ClipsResult {
        val qs = buildString {
            append("since=").append(since)
            if (until != null) append("&until=").append(until)
            if (limit > 0) append("&limit=").append(limit)
            if (includeMine) append("&includeMine=1")
        }
        val eph = if (settings.syncEncryption) SyncCrypto.generate() else null
        val conn = connect(
            device.host!!, device.port, "/clips?$qs", token = device.token, eph = eph
        )
        try {
            when (conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val encrypted = conn.getHeaderField(SyncCrypto.HEADER_ENCRYPTED) == "1"
                    // 要求加密而响应未加密：对方是旧版本或响应被篡改，拒绝接受
                    if (eph != null && !encrypted) throw EncryptionRequiredException()
                    val payload = if (encrypted) {
                        SyncCrypto.decrypt(responseKey(conn, eph), bytes)
                    } else bytes
                    return ClipsResult(
                        gson.fromJson(String(payload, Charsets.UTF_8), JsonArray::class.java),
                        encrypted
                    )
                }
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

    /** 下载媒体文件到本地（开启加密传输时要求对方必须加密，否则失败） */
    fun downloadFile(device: LanDevice, remoteId: Long, out: File) {
        val eph = if (settings.syncEncryption) SyncCrypto.generate() else null
        val conn = connect(
            device.host!!, device.port, "/file?id=$remoteId", token = device.token, eph = eph
        )
        try {
            when (conn.responseCode) {
                200 -> {
                    val encrypted = conn.getHeaderField(SyncCrypto.HEADER_ENCRYPTED) == "1"
                    // 要求加密而响应未加密：拒绝接受明文
                    if (eph != null && !encrypted) throw EncryptionRequiredException()
                    val input = if (encrypted) {
                        SyncCrypto.decryptStream(responseKey(conn, eph), conn.inputStream)
                    } else conn.inputStream
                    input.use { i -> out.outputStream().use { i.copyTo(it) } }
                }
                401 -> throw NeedPairingException()
                403 -> throw forbidden(conn)
                else -> error("file http ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 从加密响应头取对方公钥，与己方临时私钥算出共享密钥 */
    private fun responseKey(
        conn: HttpURLConnection,
        eph: SyncCrypto.Ephemeral?
    ): javax.crypto.SecretKey {
        requireNotNull(eph) { "收到加密响应但本机未发起密钥交换" }
        val peer = conn.getHeaderField(SyncCrypto.HEADER_PUB_KEY)
            ?: error("加密响应缺少公钥头")
        return SyncCrypto.deriveKey(eph, peer)
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
        timeoutMs: Int = 5_000,
        eph: SyncCrypto.Ephemeral? = null
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
        // 请求加密传输：带临时公钥，对方支持时响应体会用 ECDH 共享密钥加密
        eph?.let { conn.setRequestProperty(SyncCrypto.HEADER_PUB_KEY, it.publicB64) }
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
