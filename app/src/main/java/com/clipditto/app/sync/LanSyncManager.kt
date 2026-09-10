package com.clipditto.app.sync

import android.content.Context
import android.util.Log
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 局域网同步总控：根据开关状态驱动服务启停、NSD 注册/扫描、自动同步循环。
 *
 * 去重策略（同步到本机时）：
 * 1. 环回防护：跳过 remoteDeviceId == 本机 deviceId 的记录（防止内容绕一圈又回来）；
 * 2. 文字：按内容查重，已有则只把时间戳顶到最前；
 * 3. 媒体：下载后按「类型 + 文件大小」查重，重复则丢弃下载文件。
 */
object LanSyncManager {

    private const val TAG = "LanSyncManager"
    private const val AUTO_SYNC_INTERVAL = 30_000L

    private lateinit var appContext: Context
    private lateinit var settings: LanSettings
    private lateinit var repo: ClipRepository
    private lateinit var server: LanSyncServer
    private lateinit var client: LanSyncClient
    lateinit var discovery: LanDiscovery
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var autoSyncStarted = false
    private var initialized = false

    /** 合并后的设备列表：配对设备（可能离线）+ 在线发现 */
    private val _devices = MutableStateFlow<List<LanDevice>>(emptyList())
    val devices: StateFlow<List<LanDevice>> = _devices

    /** 正在同步中的设备 id 集合（UI 显示转圈用） */
    private val _syncing = MutableStateFlow<Set<String>>(emptySet())
    val syncing: StateFlow<Set<String>> = _syncing

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = LanSettings(appContext)
        repo = ClipRepository(appContext)
        server = LanSyncServer(
            settings, repo,
            onAutoPair = { autoPaired ->
                // 对方配对成功后的反向自动配对：直接存为已配对设备
                settings.savePairedDevice(autoPaired)
                refreshDevices()
            },
            pairApproval = { requester ->
                // 设备页在前台时弹窗等待用户确认（最多 30 秒），否则返回 null
                pairApprovalUiHandler?.invoke(requester)
            },
            onUnpaired = { deviceId ->
                // 对方主动解除配对：本地同步解除配对。
                // 取消配对 ≠ 被拉黑：不记 blockedBy，双方之后仍可正常重新配对；
                // "被对方拉黑"走 /blocked 通知（onBlockedBy），不要扩散到这里
                settings.removePairedDevice(deviceId)
                refreshDevices()
            },
            onUnblocked = { deviceId ->
                settings.removeBlockedBy(deviceId)
                refreshDevices()
            },
            onBlockedBy = { deviceId ->
                // 经回连验证确实被对方拉黑：解除本地配对、记录 blockedBy，列表隐藏对方
                settings.removePairedDevice(deviceId)
                settings.addBlockedBy(deviceId)
                refreshDevices()
            }
        )
        client = LanSyncClient(settings) { if (server.isRunning) server.port else 0 }
        discovery = LanDiscovery(appContext, settings)
        applyState()
        watchDiscovery()
        watchNetwork()
        startAutoSyncLoop()
    }

    // ---------------- 开关状态 ----------------

    fun settings(): LanSettings = settings

    val serverRunning: Boolean get() = initialized && server.isRunning
    val serverPort: Int get() = if (initialized) server.port else 0

    /** 修改端口后调用：重启服务并按需重新注册广播 */
    fun restartServer() {
        if (!initialized) return
        val wasRunning = server.isRunning
        discovery.unregisterService()
        server.stop()
        if (wasRunning || settings.sharing || settings.discoverable) {
            applyState()
        }
    }

    /** 修改本机名称后调用：重新注册 NSD 广播（beacon 每 3 秒会自动带新名字） */
    fun refreshName() {
        if (!initialized) return
        if (settings.discoverable && server.isRunning) {
            discovery.unregisterService()
            discovery.registerService(server.port)
        }
    }

    /** 开关变化后调用：按需启停服务 / 注册 / 扫描 */
    fun applyState() {
        if (!initialized) return
        // 服务：共享或可被发现时都需要（发现后对方会请求 /info）
        if (settings.sharing || settings.discoverable) {
            server.start()
        } else {
            server.stop()
        }
        // 注册：仅"可被发现"开启时广播自己
        if (settings.discoverable && server.isRunning) {
            discovery.registerService(server.port)
        } else {
            discovery.unregisterService()
        }
        // 扫描：自动同步开启时保持扫描，否则由设备页在前台时临时开启
        if (settings.autoSync) {
            discovery.startDiscovery()
        } else if (!discoveryScreenActive) {
            discovery.stopDiscovery()
        }
    }

    /** 设备页进入/退出时调用，控制临时扫描 */
    var discoveryScreenActive = false
        set(v) {
            field = v
            if (v) discovery.startDiscovery()
            else if (!settings.autoSync) discovery.stopDiscovery()
        }

    // ---------------- 设备列表 ----------------

    private fun watchDiscovery() {
        scope.launch {
            discovery.discovered.collect { online ->
                mergeAndPublish(online)
            }
        }
    }

    /**
     * 网络变化自愈：WiFi 切换/断连重连后，NSD 会话、组播成员关系、
     * beacon socket 都可能静默失效（系统不一定回调），这里统一重建。
     */
    private fun watchNetwork() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        var lastHandle = 0L
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = onNetworkChanged()
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: android.net.NetworkCapabilities
            ) = onNetworkChanged()

            private fun onNetworkChanged() {
                val now = System.currentTimeMillis()
                if (now - lastHandle < 5_000) return  // 防抖：切换瞬间会连续回调
                lastHandle = now
                scope.launch {
                    delay(1_500)  // 等新网络稳定
                    Log.d(TAG, "网络变化，重建发现通道")
                    if (settings.discoverable && server.isRunning) {
                        discovery.unregisterService()
                        discovery.registerService(server.port)
                    }
                    if (settings.autoSync || discoveryScreenActive) {
                        discovery.restartDiscovery()
                    }
                }
            }
        })
    }

    private fun mergeAndPublish(online: List<LanDevice>) {
        val paired = settings.getPairedDevices()
        val blocked = settings.getBlockedDevices() + settings.getBlockedBy()
        val merged = mutableMapOf<String, LanDevice>()
        // 先放配对设备（默认离线）；黑名单设备不进列表
        paired.values.filter { it.deviceId !in blocked }.forEach { p ->
            merged[p.deviceId] = p.copy(
                online = false,
                lastSync = settings.getLastSync(p.deviceId)
            )
        }
        // 在线设备覆盖/补充；黑名单设备不进列表
        online.filter { it.deviceId !in blocked }.forEach { o ->
            val p = paired[o.deviceId]
            merged[o.deviceId] = if (p != null) {
                o.copy(paired = true, token = p.token,
                    lastSync = settings.getLastSync(o.deviceId))
            } else o
        }
        // 在线设备的最新信息回写到配对记录（DHCP 换 IP、对方改名后仍保持最新）
        online.forEach { o ->
            paired[o.deviceId]?.let { p ->
                if (p.host != o.host || p.port != o.port ||
                    p.sharing != o.sharing || p.name != o.name || p.model != o.model
                ) {
                    settings.savePairedDevice(
                        p.copy(host = o.host, port = o.port, sharing = o.sharing,
                            name = o.name, model = o.model)
                    )
                }
            }
        }
        _devices.value = merged.values.sortedWith(
            compareByDescending<LanDevice> { it.online }.thenBy { it.name }
        )
    }

    fun refreshDevices() = mergeAndPublish(discovery.discovered.value)

    fun unpair(deviceId: String) {
        val device = settings.getPairedDevices()[deviceId]
            ?: _devices.value.firstOrNull { it.deviceId == deviceId }
        // 取消配对 ≠ 拉黑：不进黑名单。对方若仍持旧配对码来拉取，
        // 服务端会以"未配对"拒绝（见 LanSyncServer.rejectIfUnpaired），
        // 对方收到后自动解除本地配对状态
        settings.removePairedDevice(deviceId)
        refreshDevices()
        // 主动通知对方立即解除配对（对方不在线则静默失败，被动流程兜底）
        device?.takeIf { it.host != null && it.token != null }?.let { d ->
            scope.launch { runCatching { client.notifyUnpair(d) } }
        }
    }

    /** 把设备加入黑名单（含未配对设备）：无法对它操作，它也无法扫描/操作本机 */
    fun blockDevice(device: LanDevice) {
        settings.removePairedDevice(device.deviceId)
        settings.blockDevice(
            device.deviceId, device.displayName, device.model,
            token = device.token, host = device.host, port = device.port
        )
        refreshDevices()
        // 通知对方：
        // - 有配对码（已配对过）→ /unpair 通知，让对方立即解除本地配对
        // - /blocked 通知（免配对码，对方回连验证后生效）→ 对方记录"被你拉黑"并隐藏本机
        val host = device.host
        if (host != null) {
            scope.launch {
                if (device.token != null) runCatching { client.notifyUnpair(device) }
                runCatching { client.notifyBlocked(host, device.port) }
            }
        }
    }

    /** 移出黑名单：本地解除，并尽力通知对方恢复（对方扫描时也会自愈） */
    fun unblockDevice(deviceId: String) {
        val info = settings.getBlocked()[deviceId]
        settings.unblockDevice(deviceId)
        refreshDevices()
        if (info?.token != null && info.host != null) {
            val d = LanDevice(
                deviceId = deviceId, name = info.name,
                host = info.host, port = info.port, token = info.token
            )
            scope.launch { runCatching { client.notifyUnblocked(d) } }
        }
    }

    fun getBlockedList(): Map<String, LanSettings.BlockedInfo> = settings.getBlocked()

    // ---------------- 同步 ----------------

    /** 同步结果统计 */
    data class SyncResult(val added: Int, val skipped: Int)

    /** 目标设备在本机黑名单中 */
    class BlockedException : Exception("blocked")

    /** 手动同步一台设备。需要设备已配对（持有配对码）且在线。 */
    suspend fun syncDevice(device: LanDevice): SyncResult = withContext(Dispatchers.IO) {
        if (device.deviceId in settings.getBlockedDevices()) throw BlockedException()
        if (device.deviceId in settings.getBlockedBy()) throw BlockedByException()
        if (device.host == null) error("设备离线")
        val token = device.token ?: throw NeedPairingException()
        _syncing.value = _syncing.value + device.deviceId
        try {
            // 用在线发现的最新地址
            val target = discovery.discovered.value
                .firstOrNull { it.deviceId == device.deviceId } ?: device
            val d = target.copy(token = token)

            val since = settings.getLastSync(device.deviceId)
            val clips = try {
                client.fetchClips(d, since)
            } catch (e: UnpairedException) {
                // 对方已取消与本机的配对：本地同步解除配对状态
                settings.removePairedDevice(device.deviceId)
                refreshDevices()
                throw e
            }
            var added = 0
            var skipped = 0
            var maxTs = since

            clips.forEach { el ->
                val obj = el.asJsonObject
                maxTs = maxOf(maxTs, obj.get("timestamp").asLong)
                when (importClip(obj, d)) {
                    true -> added++
                    false -> skipped++
                }
            }
            settings.setLastSync(device.deviceId, maxTs)
            refreshDevices()
            SyncResult(added, skipped)
        } finally {
            _syncing.value = _syncing.value - device.deviceId
        }
    }

    /** 手动同步多台设备，返回 deviceId → 结果（失败为异常信息） */
    suspend fun syncDevices(devices: List<LanDevice>): Map<String, Result<SyncResult>> {
        val out = mutableMapOf<String, Result<SyncResult>>()
        devices.forEach { d ->
            out[d.deviceId] = runCatching { syncDevice(d) }
        }
        return out
    }

    /** 导入一条远端记录；返回 true = 新增，false = 去重跳过 */
    private suspend fun importClip(obj: JsonObject, device: LanDevice): Boolean {
        // 环回防护：内容本来就来自本机
        if (obj.str("remoteDeviceId") == settings.deviceId) return false

        val type = obj.get("type").asInt
        val text = obj.str("text")
        val originDevice = obj.str("remoteDeviceId")
        val originId = obj.lng("remoteId")
        val timestamp = obj.get("timestamp").asLong

        if (type == ClipType.TEXT) {
            val content = text ?: return false
            val dup = repo.findDuplicateText(content)
            return if (dup != null) {
                repo.touch(dup.id)
                false
            } else {
                repo.insertRemote(
                    ClipItem(
                        type = ClipType.TEXT,
                        text = content,
                        sourceApp = obj.str("sourceApp"),
                        timestamp = timestamp,
                        remoteDeviceId = originDevice,
                        remoteId = originId
                    )
                )
                true
            }
        }

        // 媒体：先下载到临时文件，去重后再决定保留
        val fileName = obj.str("fileName") ?: return false
        val expectedSize = obj.lng("fileSize")
        val remoteId = obj.get("id").asLong
        val tmp = File(appContext.cacheDir, "lan_$remoteId.tmp")
        return try {
            client.downloadFile(device, remoteId, tmp)
            if (tmp.length() == 0L || (expectedSize > 0 && tmp.length() != expectedSize)) {
                tmp.delete()
                return false
            }
            val dup = repo.findDuplicateMedia(type, tmp)
            if (dup != null) {
                tmp.delete()
                repo.touch(dup.id)
                false
            } else {
                val ext = fileName.substringAfterLast('.', "bin")
                val dest = File(repo.mediaDir, "${timestamp}_lan.$ext")
                tmp.renameTo(dest)
                repo.insertRemote(
                    ClipItem(
                        type = type,
                        text = text,
                        filePath = dest.absolutePath,
                        mimeType = obj.str("mimeType"),
                        sourceApp = obj.str("sourceApp"),
                        timestamp = timestamp,
                        remoteDeviceId = originDevice,
                        remoteId = originId
                    )
                )
                true
            }
        } finally {
            tmp.delete()
        }
    }

    // ---------------- 自动同步 ----------------

    private fun startAutoSyncLoop() {
        if (autoSyncStarted) return
        autoSyncStarted = true
        scope.launch {
            while (isActive) {
                if (settings.autoSync) {
                    val targets = _devices.value.filter { it.online && it.paired && it.sharing }
                    targets.forEach { d ->
                        runCatching { syncDevice(d) }
                            .onFailure { Log.w(TAG, "自动同步 ${d.name} 失败: ${it.message}") }
                    }
                }
                delay(AUTO_SYNC_INTERVAL)
            }
        }
    }

    // ---------------- 配对 ----------------

    /**
     * 配对确认 UI 回调（由设备页在前台时注册）。
     * 在 HTTP 服务线程上调用，可阻塞等待用户选择。
     */
    @Volatile
    var pairApprovalUiHandler: ((LanDevice) -> Boolean)? = null

    /** 配对失败的具体原因 */
    sealed class PairError {
        object Offline : PairError()
        object BadToken : PairError()
        object SharingOff : PairError()
        object Rejected : PairError()
        object NeedConfirm : PairError()
        object Blocked : PairError()
        object BlockedBy : PairError()
        data class ConnectFail(val detail: String) : PairError()
    }

    /**
     * 用配对码配对一台设备：先验证配对码，再等待对方确认（除非对方开了自动同意）。
     * 成功返回 null，失败返回具体原因。
     */
    suspend fun pair(device: LanDevice, token: String): PairError? =
        withContext(Dispatchers.IO) {
            if (device.deviceId in settings.getBlockedDevices()) return@withContext PairError.Blocked
            if (device.deviceId in settings.getBlockedBy()) return@withContext PairError.BlockedBy
            // 用最新发现的设备信息（地址可能已变化）
            val fresh = discovery.discovered.value
                .firstOrNull { it.deviceId == device.deviceId } ?: device
            if (fresh.host == null) return@withContext PairError.Offline
            val d = fresh.copy(token = token)
            try {
                client.requestPair(d)
                settings.unblockDevice(d.deviceId)  // 重新配对成功：解除拉黑
                settings.savePairedDevice(d.copy(paired = true))
                refreshDevices()
                null
            } catch (e: NeedPairingException) {
                PairError.BadToken
            } catch (e: SharingOffException) {
                PairError.SharingOff
            } catch (e: PairRejectedException) {
                PairError.Rejected
            } catch (e: PairNeedConfirmException) {
                PairError.NeedConfirm
            } catch (e: Exception) {
                Log.w(TAG, "配对连接失败: ${e.message}")
                PairError.ConnectFail("${fresh.host}:${fresh.port} ${e.javaClass.simpleName}: ${e.message}")
            }
        }

    private fun JsonObject.str(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    private fun JsonObject.lng(key: String): Long =
        if (has(key) && !get(key).isJsonNull) get(key).asLong else 0L
}
