package com.clipditto.app.sync.relay

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.clipditto.app.sync.LanSettings
import com.clipditto.app.util.MediaFiles
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * 云端中继同步总控（对应 PC 端 relay.rs，设计文档见 docs/android-relay-sync-design.md）。
 *
 * - 与 LAN 同步并行存在的第二条通道：本机剪贴板变化同时往两条通道发，
 *   收到的条目走与 LAN 相同的内容查重入库路径，先到先存、晚到自动判重。
 * - 监督循环：按配置连接，断线指数退避重连（3s → 60s 封顶）；
 *   配置修改（代际号 +1）自动断开用新配置重连；鉴权失败停止重连直到配置变更。
 * - 回环铁律：只有 [onLocalText] / [onLocalImage]（ClipboardService 本地新条目回调）
 *   会产生 push；[importClip] 入库的远程条目绝不外发。
 */
object RelaySyncManager {

    private const val TAG = "RelaySyncManager"
    private const val RECONNECT_MIN_MS = 3_000L
    private const val RECONNECT_MAX_MS = 60_000L

    private lateinit var appContext: Context
    private lateinit var settings: RelaySettings
    private lateinit var lanSettings: LanSettings
    private lateinit var repo: ClipRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private var initialized = false

    /** 配置代际：每次修改 +1，连接循环发现不一致即断开重连（对齐 PC 端 gen） */
    private val gen = AtomicLong(0)

    /** 补拉游标（内存实时推进，断开时统一落盘到 settings.lastSeq） */
    private val cursor = AtomicLong(0)

    private val client = RelayClient(
        deviceId = { lanSettings.deviceId },
        deviceName = { lanSettings.deviceName }
    )

    /** 连接状态文本（直接给 UI 展示） */
    private val _status = MutableStateFlow("⚪ 未启用")
    val status: StateFlow<String> = _status

    /** 分组内在线设备（服务器 peers 广播，已剔除本机） */
    private val _peers = MutableStateFlow<List<RelayClient.Peer>>(emptyList())
    val peers: StateFlow<List<RelayClient.Peer>> = _peers

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = RelaySettings(appContext)
        lanSettings = LanSettings(appContext)
        repo = ClipRepository(appContext)
        cursor.set(settings.lastSeq)
        scope.launch { supervise() }
    }

    fun settings(): RelaySettings = settings

    /** 配置修改后调用：代际 +1，连接循环感知后立即断开重连 */
    fun notifyChanged() {
        gen.incrementAndGet()
    }

    /** 设置页「重新连接」按钮 */
    fun reconnect() = notifyChanged()

    /** 设置页「清除游标并全量补拉」：游标归零 + 重连（重复条目由内容查重兜住） */
    fun resetCursor() {
        cursor.set(0)
        settings.lastSeq = 0
        notifyChanged()
    }

    // ---------------- 发送链路（本机剪贴板 → 中继） ----------------

    /**
     * 本机新文本剪贴板入库后由 ClipboardService 调用：组包发出（未连接/未启用时丢弃）。
     * @param item 刚入库的记录（id / timestamp 作为线上的 remoteId / timestamp，与 LAN 口径一致）
     */
    fun onLocalText(item: ClipItem) {
        if (!initialized || !settings.enabled) return
        // 铁律双保险：同步来的条目绝不外发（调用点本来就只传本地条目）
        if (item.remoteDeviceId != null) return
        val text = item.text?.takeIf { it.isNotEmpty() } ?: return
        val clip = buildClip(RelaySettings.WIRE_TEXT, item.id, item.timestamp, text.toByteArray(Charsets.UTF_8))
            ?: return // 加密失败静默丢弃（不降级发明文）
        push(clip)
    }

    /**
     * 本机新图片剪贴板入库后调用：仅「图片经中继」开关开启且 ≤4MB 时 push。
     * @param file 已落盘到私有目录的图片文件
     */
    fun onLocalImage(item: ClipItem, file: File) {
        if (!initialized || !settings.enabled || !settings.syncImage) return
        if (item.remoteDeviceId != null) return
        if (file.length() <= 0 || file.length() > RelaySettings.MAX_RELAY_IMAGE_BYTES) return
        scope.launch {
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@launch
            // 尺寸可选携带（PC 端 store_remote 的 w/h 参数），读不到就不带
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val clip = buildClip(RelaySettings.WIRE_IMAGE, item.id, item.timestamp, bytes,
                width = opts.outWidth.takeIf { it > 0 },
                height = opts.outHeight.takeIf { it > 0 }
            ) ?: return@launch
            push(clip)
        }
    }

    /**
     * 组 clip 负载：分组密钥非空时加密（密文放 data + enc:1），否则明文
     * （文本放 text，图片放 base64 data）——与 PC 端 push_local_text/push_local_image 一致。
     */
    private fun buildClip(
        wireType: Int,
        localId: Long,
        timestampMs: Long,
        content: ByteArray,
        width: Int? = null,
        height: Int? = null
    ): JsonObject? {
        val myId = lanSettings.deviceId
        val groupKey = settings.groupKey
        val clip = JsonObject()
        clip.addProperty("type", wireType)
        if (groupKey.isEmpty()) {
            if (wireType == RelaySettings.WIRE_TEXT) {
                clip.addProperty("text", String(content, Charsets.UTF_8))
            } else {
                clip.addProperty("data", String(Base64.getEncoder().encode(content), Charsets.US_ASCII))
            }
        } else {
            val key = RelayCrypto.deriveKey(groupKey, settings.groupId)
            val aad = RelayCrypto.aad(myId, localId, timestampMs, wireType)
            val data = RelayCrypto.encrypt(key, aad, content) ?: return null
            clip.addProperty("enc", 1)
            clip.addProperty("data", data)
        }
        width?.let { clip.addProperty("width", it) }
        height?.let { clip.addProperty("height", it) }
        clip.addProperty("timestamp", timestampMs)
        clip.addProperty("remoteDeviceId", myId)
        clip.addProperty("remoteId", localId)
        return clip
    }

    private fun push(clip: JsonObject) {
        val msg = JsonObject().apply {
            addProperty("op", "push")
            add("clip", clip)
        }
        client.trySend(gson.toJson(msg))
    }

    // ---------------- 接收链路（中继 → 入库） ----------------

    /** 推进补拉游标（只前进不后退）；落盘在连接断开时统一做 */
    private fun advanceCursor(seq: Long) {
        if (seq <= 0) return
        cursor.updateAndGet { maxOf(it, seq) }
    }

    /**
     * 处理服务器推来的 clip：环回防护 + 类型开关 + （必要时解密）+ 内容查重入库。
     * 与 LAN importClip 同一套去重语义：文字按内容查重 touch，图片按类型+大小查重。
     */
    private suspend fun importClip(clip: JsonObject) {
        // 环回防护：内容本来就来自本机
        val origin = clip.str("remoteDeviceId") ?: return
        if (origin == lanSettings.deviceId) return

        val type = clip.lngOrNull("type")?.toInt() ?: return
        when (type) {
            RelaySettings.WIRE_TEXT ->
                if (LanSettings.GROUP_TEXT !in lanSettings.syncTypeGroups) return
            RelaySettings.WIRE_IMAGE ->
                if (!settings.syncImage || LanSettings.GROUP_IMAGE !in lanSettings.syncTypeGroups) return
            else -> return // 文件/视频/音频仅局域网（设计文档 §7.3）
        }

        val timestampMs = clip.lngOrNull("timestamp") ?: 0L
        val remoteId = clip.lngOrNull("remoteId")

        // 取出内容字节：密文先解密（未配分组密钥或校验失败则跳过）
        val content: ByteArray = if (clip.lngOrNull("enc") == 1L) {
            val groupKey = settings.groupKey
            if (groupKey.isEmpty()) return
            val data = clip.str("data") ?: return
            // AAD 必须用线上自带的元数据（而不是入库时间），否则解密必然失败
            val aad = RelayCrypto.aad(origin, remoteId ?: 0, timestampMs, type)
            RelayCrypto.decrypt(RelayCrypto.deriveKey(groupKey, settings.groupId), aad, data)
                ?: return // 密钥不一致或数据被篡改
        } else if (type == RelaySettings.WIRE_TEXT) {
            clip.str("text")?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8) ?: return
        } else {
            val d = clip.str("data") ?: return
            runCatching { Base64.getDecoder().decode(d.trim()) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= RelaySettings.MAX_RELAY_IMAGE_BYTES }
                ?: return
        }

        // 时间锚定到到达时刻：列表按时间倒序，同步来的内容应出现在最前
        val now = System.currentTimeMillis()

        if (type == RelaySettings.WIRE_TEXT) {
            val text = String(content, Charsets.UTF_8)
            val dup = repo.findDuplicateText(text)
            if (dup != null) {
                repo.touch(dup.id)
            } else {
                repo.insertRemote(
                    ClipItem(
                        type = ClipType.TEXT,
                        text = text,
                        timestamp = now,
                        remoteDeviceId = origin,
                        remoteId = remoteId
                    )
                )
                Log.d(TAG, "中继文本入库：len=${text.length} from=${origin.take(8)}")
            }
            return
        }

        // 图片：写临时文件 → 查重 → 落盘到同步目录（SAF 自定义目录或系统 Download/ClipDitto）
        val tmp = File(appContext.cacheDir, "relay_$now.tmp")
        try {
            tmp.writeBytes(content)
            val dup = repo.findDuplicateMedia(ClipType.IMAGE, tmp)
            if (dup != null) {
                repo.touch(dup.id)
                return
            }
            val (ext, mime) = sniffImage(content)
            val name = "${now}_relay.$ext"
            val stored = lanSettings.syncDirUri?.let { tree ->
                MediaFiles.writeToTree(appContext, tree, name, mime, tmp)
            } ?: MediaFiles.writeToDefaultDir(appContext, name, mime, tmp)
            if (stored == null) {
                Log.w(TAG, "中继图片落盘失败（目录权限失效/空间不足？）: $name")
                return
            }
            repo.insertRemote(
                ClipItem(
                    type = ClipType.IMAGE,
                    filePath = stored,
                    mimeType = mime,
                    timestamp = now,
                    remoteDeviceId = origin,
                    remoteId = remoteId
                )
            )
            Log.d(TAG, "中继图片入库：${content.size} 字节 from=${origin.take(8)}")
        } finally {
            tmp.delete()
        }
    }

    /** 按魔数嗅探图片格式（中继线上只带裸字节不带 mime），默认按 PNG 处理 */
    private fun sniffImage(bytes: ByteArray): Pair<String, String> = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png" to "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg" to "image/jpeg"
        bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
            .let { it == "GIF87a" || it == "GIF89a" } -> "gif" to "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "webp" to "image/webp"
        else -> "png" to "image/png"
    }

    // ---------------- 监督循环 ----------------

    private val handler = object : RelayClient.Handler {
        override fun onStatus(status: String) {
            _status.value = status
        }

        override fun onClip(seq: Long, clip: JsonObject) {
            advanceCursor(seq)
            scope.launch { importClip(clip) }
        }

        override fun onAcked(seq: Long) = advanceCursor(seq)

        override fun onPeers(peers: List<RelayClient.Peer>) {
            _peers.value = peers
        }

        override fun configStale(): Boolean = false // 由 supervise 注入，见下方委托
        override fun lastSeq(): Long = cursor.get()
    }

    /** 中继模块主循环：按配置连接，断线指数退避重连；未启用/配置不全时空转等待 */
    private suspend fun supervise() {
        var backoff = RECONNECT_MIN_MS
        var lastGen = -1L // 强制首轮读取
        while (true) {
            val g = gen.get()
            if (!settings.enabled || !settings.isComplete()) {
                _status.value = if (settings.enabled) {
                    "⚪ 配置不完整（需服务器地址 / 分组 ID / 接入密钥）"
                } else {
                    "⚪ 未启用"
                }
                _peers.value = emptyList()
                backoff = RECONNECT_MIN_MS
                delay(2_000)
                continue
            }
            if (g != lastGen) {
                backoff = RECONNECT_MIN_MS
                lastGen = g
            }

            val reason = client.run(
                RelaySettings.normalizeUrl(settings.serverUrl),
                settings.groupId,
                settings.accessKey,
                object : RelayClient.Handler by handler {
                    // 捕获本次连接的代际快照：运行期间配置变了就断开重连
                    override fun configStale(): Boolean = gen.get() != g
                }
            )

            // 清理：游标落盘（内存中已实时推进），在线名单清空
            settings.lastSeq = cursor.get()
            _peers.value = emptyList()
            _status.value = "连接已断开"

            when (reason) {
                // 鉴权失败：密钥错了重连无意义（还会触发服务器防爆破封禁），等配置变更
                RelayClient.CloseReason.AUTH_FAILED -> {
                    while (gen.get() == g) delay(2_000)
                }
                // 配置在连接期间被改过 → 立即用新配置重连，不退避
                RelayClient.CloseReason.CONFIG_CHANGED -> continue
                RelayClient.CloseReason.NETWORK -> {
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
                }
            }
        }
    }

    private fun JsonObject.str(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.lngOrNull(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) get(key).asLong else null
}
