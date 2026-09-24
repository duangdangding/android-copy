package com.clipditto.app.sync

import android.content.Context
import android.util.Log
import com.clipditto.app.App
import com.clipditto.app.R
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.clipditto.app.util.MediaFiles
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

    /** PC 端（copy-pc）/clips 的单次响应上限：返回条数达到它说明可能只拿到第一页 */
    private const val PC_PAGE_SIZE = 500

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
            appContext, settings, repo,
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

    /** 注册/注销文件共享接收处理器（由 FileShareManager 调用；null = 注销） */
    fun setFsHandler(handler: FsReceiveHandler?) {
        if (!initialized) return
        server.fsHandler = handler
    }

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

    /** 开关变化后调用：按需启停注册 / 扫描；服务本身常开 */
    fun applyState() {
        if (!initialized) return
        // 服务无条件常开：文件共享接收常驻（POST /fs/send 不走配对鉴权），
        // 即使共享/可被发现都关闭，也要能被其他设备发送文件；
        // 同步的 /clips、/file 等内容路由仍各自校验开关与配对码，不受影响
        server.start()
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

    /**
     * 手动删除设备（主要用于清理列表里残留的离线设备）：
     * 本地解除配对 + 清理增量同步水位，彻底从列表移除；
     * 同步到本机的历史记录保留（如需删除走「删除该设备同步来的记录」）。
     * 对方若恰好在线，尽力通知其解除配对（不在线则静默失败，对端靠被动流程兜底）。
     */
    fun removeDevice(device: LanDevice) {
        settings.removePairedDevice(device.deviceId)
        settings.removeLastSync(device.deviceId)
        refreshDevices()
        device.takeIf { it.host != null && it.token != null }?.let { d ->
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

    /**
     * 同步结果统计。
     * @param added 新增入库
     * @param skipped 去重跳过（内容已有）
     * @param skippedStoreFail 媒体落盘失败（目录权限失效/空间不足等）
     * @param skippedTooBig 超过大小上限而跳过的媒体数
     * @param skippedType 类型未勾选而跳过的条数
     */
    data class SyncResult(
        val added: Int,
        val skipped: Int,
        val skippedStoreFail: Int = 0,
        val skippedTooBig: Int = 0,
        val skippedType: Int = 0
    )

    /**
     * 手动同步的范围选择（自动同步始终走增量，不涉及）。
     * 注意：范围同步（最近N条/某天）不推进 lastSync 增量水位，
     * 避免打乱自动同步的连续性；只有增量同步和全量同步推进水位。
     */
    sealed class SyncScope {
        /** 全部记录（since=0，同步后推进水位） */
        object All : SyncScope()

        /** 最近 N 条（服务端取最新 N 条） */
        data class Recent(val count: Int) : SyncScope()

        /** 某一天（dayStart 为当天 0 点的时间戳，本地时区） */
        data class Day(val dayStart: Long) : SyncScope()

        companion object {
            const val DAY_MS = 86_400_000L
        }
    }

    /** 目标设备在本机黑名单中 */
    class BlockedException : Exception("blocked")

    /**
     * 手动同步一台设备。需要设备已配对（持有配对码）且在线。
     * @param scope 同步范围；null = 增量（从上次水位之后），自动同步固定走增量
     */
    suspend fun syncDevice(device: LanDevice, scope: SyncScope? = null): SyncResult = withContext(Dispatchers.IO) {
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

            val since = when (scope) {
                null -> settings.getLastSync(device.deviceId)
                // DAO 的 getSince 是严格大于，某天的起始边界要减 1ms 才能含当天 0 点整
                is SyncScope.Day -> scope.dayStart - 1
                else -> 0L
            }
            val until = (scope as? SyncScope.Day)?.let { it.dayStart + SyncScope.DAY_MS - 1 }
            val limit = (scope as? SyncScope.Recent)?.count ?: 0
            // 手动圈范围同步（最近N条/某天/全部）：用户明确要对方的数据原样拉取，
            // 包括"本来来自本机"的记录（本机可能已删除副本想恢复）；
            // 自动增量同步保持环回防护，防止内容在两台设备间绕圈放大
            val includeMine = scope != null
            // 翻页兼容：PC 端（copy-pc）/clips 单次最多回 500 条且从"最旧"的开始给
            // （不认 until/limit 参数）。若返回顶到页上限且服务端没按 limit 截断，
            // 说明只拿到了第一页——用本页最大时间戳当新 since 继续向后翻，直到尾页
            val raw = mutableListOf<JsonObject>()
            var cursor = since
            while (true) {
                val resp = try {
                    client.fetchClips(d, cursor, until, limit, includeMine)
                } catch (e: UnpairedException) {
                    // 对方已取消与本机的配对：本地同步解除配对状态
                    settings.removePairedDevice(device.deviceId)
                    refreshDevices()
                    throw e
                }
                val batch = resp.items.map { it.asJsonObject }
                raw.addAll(batch)
                // 服务端认识 limit（安卓对安卓）时直接返回目标页，无需翻页；
                // PC 端（copy-pc）不认 until/limit，只能靠翻页拿全后由下面的客户端兜底截取
                val honoredLimit = limit > 0 && batch.size <= limit
                val batchMax = batch.maxOfOrNull { it.get("timestamp").asLong } ?: cursor
                // PC 的 created_at 是秒级精度：游标回退 1 秒再翻页，
                // 防止页截断点落在同一秒中间时，该秒剩余记录被严格大于（>）的水位永久跳过；
                // 重叠拉回的重复记录由下面的 distinctBy(id) + 入库内容去重兜住
                val newCursor = batchMax - 999
                // newCursor <= cursor：游标没有推进（整页同时间戳/已到边界），防死循环
                if (honoredLimit || batch.size < PC_PAGE_SIZE || newCursor <= cursor) break
                cursor = newCursor
                Log.d(TAG, "对方分页返回（旧 PC 端？），向后翻页：已累积 ${raw.size} 条")
            }
            // 翻页重叠区会把同一批记录重复拉回，先按记录 id 去重
            val distinct = raw.distinctBy { it.get("id").asLong }
            // 对方 App 可能是旧版本（服务端不认 until/limit，会全量返回）：
            // 客户端兜底再过滤一次，保证同步范围一定生效。
            // 注意这里只多传了元数据 JSON，媒体文件是按入库项逐个下载的，不会被多拉。
            // limit 同样显式按时间倒序取前 N 条再转回升序，不依赖返回顺序
            val clips = distinct
                .filter { until == null || it.get("timestamp").asLong <= until }
                .let { list ->
                    if (limit > 0 && list.size > limit) {
                        list.sortedByDescending { it.get("timestamp").asLong }
                            .take(limit)
                            .sortedBy { it.get("timestamp").asLong }
                    } else list
                }
            if (clips.size != distinct.size) {
                Log.d(TAG, "对方未按范围返回（旧版本？），客户端兜底过滤：${distinct.size} → ${clips.size} 条")
            }
            Log.d(
                TAG, "收到 ${clips.size} 条，时间范围 " +
                    "${clips.firstOrNull()?.get("timestamp")?.asLong}~" +
                    "${clips.lastOrNull()?.get("timestamp")?.asLong}"
            )
            var added = 0
            var skipped = 0
            var skippedStoreFail = 0
            var skippedTooBig = 0
            var skippedType = 0
            var maxTs = since

            val enabledGroups = settings.syncTypeGroups
            val maxBytes = settings.syncMaxSizeBytes
            // 本批记录的原始最大时间戳：入库时把时间锚定到"到达时刻"，
            // 保留批内相对间隔（最新一条 = 到达时刻，其余按原始间隔往前推），
            // 这样同步来的内容排在列表最前，且批内顺序不打乱
            val batchMaxTs = clips.maxOfOrNull { it.get("timestamp").asLong } ?: 0L

            clips.forEach { obj ->
                maxTs = maxOf(maxTs, obj.get("timestamp").asLong)
                // 接收方过滤（在下载媒体之前生效，被跳过的媒体不会传输文件内容）
                val type = obj.get("type").asInt
                if (settings.syncGroupOf(type) !in enabledGroups) {
                    skippedType++
                    return@forEach
                }
                if (type != ClipType.TEXT) {
                    // 超过大小上限（元数据里的 fileSize；下载后还会再校验一次实际大小）
                    if (obj.lng("fileSize") > maxBytes) { skippedTooBig++; return@forEach }
                }
                when (importClip(obj, d, batchMaxTs, includeMine)) {
                    ImportResult.Added -> added++
                    ImportResult.Duplicate -> skipped++
                    // 存储失败（目录权限失效/空间不足等）
                    ImportResult.StoreFailed -> skippedStoreFail++
                }
            }
            // 只有增量同步和全量同步推进水位；最近N条/某天是"点播"，不动水位
            if (scope == null || scope is SyncScope.All) {
                settings.setLastSync(device.deviceId, maxTs)
            }
            refreshDevices()
            SyncResult(added, skipped, skippedStoreFail, skippedTooBig, skippedType)
        } finally {
            _syncing.value = _syncing.value - device.deviceId
        }
    }

    /** 手动同步多台设备，返回 deviceId → 结果（失败为异常信息） */
    suspend fun syncDevices(
        devices: List<LanDevice>,
        scope: SyncScope? = null
    ): Map<String, Result<SyncResult>> {
        val out = mutableMapOf<String, Result<SyncResult>>()
        devices.forEach { d ->
            out[d.deviceId] = runCatching { syncDevice(d, scope) }
        }
        return out
    }

    /** 单条导入结果 */
    private enum class ImportResult { Added, Duplicate, StoreFailed }

    /**
     * 导入一条远端记录。
     * @param batchMaxTs 本批记录的原始最大时间戳，用于把入库时间锚定到到达时刻：
     *   displayTs = 现在 - (batchMaxTs - 原始时间)，批内相对顺序/间隔不变
     * @param includeMine 手动圈范围同步时为 true：不拦截"本来来自本机"的记录，
     *   交给内容去重兜底（本机还有副本则去重，已删除则恢复回来）
     */
    private suspend fun importClip(
        obj: JsonObject,
        device: LanDevice,
        batchMaxTs: Long,
        includeMine: Boolean = false
    ): ImportResult {
        // 环回防护：内容本来就来自本机（自动增量同步时拦截，避免绕圈放大）
        if (!includeMine && obj.str("remoteDeviceId") == settings.deviceId) {
            return ImportResult.Duplicate
        }

        val type = obj.get("type").asInt
        val text = obj.str("text")
        val originDevice = obj.str("remoteDeviceId")
        val originId = obj.lng("remoteId")
        val originalTs = obj.get("timestamp").asLong
        // 锚定到到达时刻：列表按时间倒序，同步来的内容应出现在最前；
        // 原始时间与批内最新时间的差值保留下来，整批相对顺序不乱
        val timestamp = System.currentTimeMillis() - (batchMaxTs - originalTs).coerceAtLeast(0L)

        if (type == ClipType.TEXT) {
            val content = text ?: return ImportResult.Duplicate
            val dup = repo.findDuplicateText(content)
            return if (dup != null) {
                repo.touch(dup.id)
                ImportResult.Duplicate
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
                ImportResult.Added
            }
        }

        // 媒体：先下载到临时文件，去重后再决定保留
        val fileName = obj.str("fileName") ?: return ImportResult.Duplicate
        val expectedSize = obj.lng("fileSize")
        val remoteId = obj.get("id").asLong
        val tmp = File(appContext.cacheDir, "lan_$remoteId.tmp")
        return try {
            client.downloadFile(device, remoteId, tmp)
            if (tmp.length() == 0L || (expectedSize > 0 && tmp.length() != expectedSize)) {
                tmp.delete()
                return ImportResult.Duplicate
            }
            // 实际大小再校验一次上限（元数据的 fileSize 可能缺失/不准）
            if (tmp.length() > settings.syncMaxSizeBytes) {
                Log.d(TAG, "媒体实际大小超过上限，丢弃: ${tmp.length()} 字节")
                return ImportResult.Duplicate
            }
            val dup = repo.findDuplicateMedia(type, tmp)
            if (dup != null) {
                tmp.delete()
                repo.touch(dup.id)
                ImportResult.Duplicate
            } else {
                // 存储位置：用户设置的自定义目录（SAF），未设置则用默认的系统 Download/ClipDitto
                val ext = fileName.substringAfterLast('.', "bin")
                val name = "${timestamp}_lan.$ext"
                val mime = obj.str("mimeType")
                val stored = settings.syncDirUri?.let { tree ->
                    MediaFiles.writeToTree(appContext, tree, name, mime, tmp)
                } ?: MediaFiles.writeToDefaultDir(appContext, name, mime, tmp)
                if (stored == null) {
                    Log.w(TAG, "媒体落盘失败（目录权限失效/空间不足？）: $name")
                    return ImportResult.StoreFailed
                }
                repo.insertRemote(
                    ClipItem(
                        type = type,
                        text = text,
                        filePath = stored,
                        mimeType = mime,
                        sourceApp = obj.str("sourceApp"),
                        timestamp = timestamp,
                        remoteDeviceId = originDevice,
                        remoteId = originId
                    )
                )
                ImportResult.Added
            }
        } finally {
            tmp.delete()
        }
    }

    // ---------------- 自动同步 ----------------

    /** 自动同步结果通知 id（固定，新结果覆盖旧的） */
    private val SYNC_NOTIFY_ID = 1002

    /** 失败通知节流：deviceId+原因 → 上次通知时间，10 分钟内同原因只提示一次 */
    private val lastFailNotifyAt = mutableMapOf<String, Long>()
    private val NOTIFY_FAIL_THROTTLE = 10 * 60_000L

    private fun startAutoSyncLoop() {
        if (autoSyncStarted) return
        autoSyncStarted = true
        scope.launch {
            while (isActive) {
                if (settings.autoSync) {
                    val targets = _devices.value.filter { it.online && it.paired && it.sharing }
                    targets.forEach { d ->
                        runCatching { syncDevice(d) }
                            .onSuccess { notifyAutoSync(d, it) }
                            .onFailure {
                                Log.w(TAG, "自动同步 ${d.name} 失败: ${it.message}")
                                notifyAutoSyncFailure(d, it)
                            }
                    }
                }
                // 每轮读取最新设置：修改间隔下一轮即生效
                delay(settings.autoSyncIntervalSec * 1000L)
            }
        }
    }

    /** 自动同步成功：有新增或有被过滤/存储失败的条目才通知；全是重复内容则静默 */
    private fun notifyAutoSync(d: LanDevice, res: SyncResult) {
        if (res.added == 0 && res.skippedStoreFail == 0 &&
            res.skippedTooBig == 0 && res.skippedType == 0
        ) return
        val text = buildString {
            append("新增 ${res.added} 条")
            if (res.skippedTooBig > 0) {
                append("，${res.skippedTooBig} 条超大小上限（${settings.syncMaxSizeMb}M）未同步")
            }
            if (res.skippedType > 0) append("，${res.skippedType} 条类型未勾选")
            if (res.skippedStoreFail > 0) append("，${res.skippedStoreFail} 条存储失败")
        }
        postSyncNotification("已从「${d.displayName}」同步", text)
    }

    /** 自动同步失败：通知原因，同设备同原因 10 分钟节流防刷屏 */
    private fun notifyAutoSyncFailure(d: LanDevice, e: Throwable) {
        val reason = when (e) {
            is EncryptionRequiredException -> "对方不支持加密传输（对端升级或关闭本机加密开关）"
            is NeedPairingException -> "需要重新配对"
            is SharingOffException -> "对方关闭了共享"
            is UnpairedException -> "对方已取消配对"
            is BlockedByException -> "对方已把本机加入黑名单"
            is BlockedException -> "该设备在本机黑名单中"
            else -> "连接失败：${e.message ?: e.javaClass.simpleName}"
        }
        val key = "${d.deviceId}|$reason"
        val now = System.currentTimeMillis()
        if (now - (lastFailNotifyAt[key] ?: 0L) < NOTIFY_FAIL_THROTTLE) return
        lastFailNotifyAt[key] = now
        postSyncNotification("自动同步「${d.displayName}」失败", reason)
    }

    /** 发同步结果通知：低打扰渠道，点击打开设备页；无通知权限时静默忽略 */
    private fun postSyncNotification(title: String, text: String) {
        val pi = android.app.PendingIntent.getActivity(
            appContext, 0,
            android.content.Intent(
                appContext, com.clipditto.app.ui.DevicesActivity::class.java
            ),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = androidx.core.app.NotificationCompat.Builder(appContext, App.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager).notify(SYNC_NOTIFY_ID, n)
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
