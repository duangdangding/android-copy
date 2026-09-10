package com.clipditto.app.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * NSD（mDNS）发现与广播。
 *
 * - 「可被发现」开关 = 注册/注销本机服务；注销后局域网内立刻扫不到本机。
 * - 扫描方通过 [discovered] 拿到实时在线设备列表（不含本机）。
 *
 * 兼容性说明：部分 Android 机型在解析结果里拿不到 TXT 记录（attributes 为空），
 * 所以解析出 IP/端口后总是再请求一次对端的 HTTP /info 接口校准设备信息；
 * HTTP 不通时才退化为使用 TXT（都没有则忽略该服务）。
 */
class LanDiscovery(private val context: Context, private val settings: LanSettings) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val client = LanSyncClient(settings)
    private val executor = Executors.newCachedThreadPool()
    private var multicastLock: WifiManager.MulticastLock? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /** 正在解析中的服务名，避免重复 resolve */
    private val resolving = mutableSetOf<String>()

    /** 服务名 → deviceId，用于 onServiceLost 精确移除 */
    private val serviceNameToId = mutableMapOf<String, String>()

    /** 实时发现的在线设备，key = deviceId */
    private val onlineDevices = mutableMapOf<String, LanDevice>()

    private val _discovered = MutableStateFlow<List<LanDevice>>(emptyList())
    val discovered: StateFlow<List<LanDevice>> = _discovered

    /** 当前是否已注册（可被扫描到） */
    var registered = false
        private set

    /** 扫描是否进行中（UI 显示"扫描中"用） */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** 诊断：本次扫描 NSD 上报的原始服务数（含非本应用服务） */
    private val _rawFound = MutableStateFlow(0)
    val rawFound: StateFlow<Int> = _rawFound

    /** 诊断：本机服务最近一次注册失败原因（null = 正常/未注册） */
    private val _registerError = MutableStateFlow<String?>(null)
    val registerError: StateFlow<String?> = _registerError

    /** 诊断计数：解析成功 / 解析失败 / HTTP 不通 / 收到 beacon / 跳过本机 */
    data class DiagStats(
        val resolveOk: Int = 0,
        val resolveFail: Int = 0,
        val httpFail: Int = 0,
        val beaconRx: Int = 0,
        val skipSelf: Int = 0
    )

    private val _stats = MutableStateFlow(DiagStats())
    val stats: StateFlow<DiagStats> = _stats

    // ---------------- UDP 广播兜底通道 ----------------
    // mDNS 在部分路由器/机型上会被拦截；开启「可被发现」的设备每 3 秒
    // 向局域网广播一个 beacon，扫描方监听固定端口即可发现，与 NSD 并行。
    //
    // 通道自愈设计：
    // - 监听 socket 在「扫描中」或「可被发现」任一开启时保持运行，异常退出后
    //   自动重建（旧实现 socket 死一次就永久失效，是"长时间搜不到"的主因）；
    // - 扫描开始时主动发 query 包，在线设备收到后立即单播回应，不用等 3 秒周期；
    // - beacon 发现的设备 15 秒没收到新 beacon 会被剔除，避免"幽灵在线"。

    @Volatile private var beaconSenderRunning = false
    @Volatile private var beaconPort = 0
    private var sendSocket: DatagramSocket? = null

    /** 监听 socket 是否运行中（扫描或可被发现任一开启即为 true） */
    @Volatile private var listenRunning = false
    private val listenSockets = mutableListOf<DatagramSocket>()

    /** beacon 发现的设备最后收到 beacon 的时间，用于超时剔除 */
    private val lastBeaconSeen = mutableMapOf<String, Long>()
    @Volatile private var watchdogRunning = false

    // ---------------- 广播本机 ----------------

    /** 注册本机服务，让局域网内其他设备能扫描到 */
    fun registerService(port: Int) {
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME_PREFIX + settings.deviceName
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("deviceId", settings.deviceId)
            setAttribute("name", settings.deviceName)
            setAttribute("model", settings.deviceModel)
            setAttribute("sharing", if (settings.sharing) "1" else "0")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "NSD 注册失败: $errorCode")
                registered = false
                _registerError.value = "NSD 注册失败(code=$errorCode)"
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}

            override fun onServiceRegistered(info: NsdServiceInfo?) {
                registered = true
                _registerError.value = null
                Log.d(TAG, "NSD 已注册: ${info?.serviceName}")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo?) {
                registered = false
            }
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { _registerError.value = "注册异常: ${it.message}" }
        startBeaconSender(port)
    }

    /** 注销服务，局域网内立即不可见 */
    fun unregisterService() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        registered = false
        stopBeaconSender()
    }

    // ---------------- 扫描其他设备 ----------------

    /** 是否希望保持扫描（NSD 失败时用于自动重试） */
    @Volatile private var discoveryWanted = false
    @Volatile private var retryScheduled = false

    fun startDiscovery() {
        discoveryWanted = true
        acquireMulticastLock()
        updateBeaconListener()  // beacon 通道与 NSD 解耦，NSD 挂了也能发现
        sendQuery()             // 主动询问：在线设备立即回应，不用等 3 秒 beacon 周期
        startWatchdog()
        if (discoveryListener != null) return
        _rawFound.value = 0
        _stats.value = DiagStats()
        _scanning.value = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d(TAG, "NSD 扫描已开始")
            }

            override fun onServiceFound(info: NsdServiceInfo?) {
                info ?: return
                _rawFound.value += 1
                if (info.serviceType?.contains(SERVICE_TYPE.trimEnd('.')) != true) return
                synchronized(resolving) {
                    if (resolving.contains(info.serviceName)) return
                    resolving.add(info.serviceName)
                }
                runCatching { nsdManager.resolveService(info, makeResolveListener()) }
                    .onFailure { synchronized(resolving) { resolving.remove(info.serviceName) } }
            }

            override fun onServiceLost(info: NsdServiceInfo?) {
                info ?: return
                val key = serviceNameToId.remove(info.serviceName) ?: return
                onlineDevices.remove(key)
                publish()
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                discoveryListener = null
                _scanning.value = false
                scheduleRetry()  // 期望扫描时被停了：稍后自动重启
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "NSD 扫描启动失败: $errorCode")
                discoveryListener = null
                _scanning.value = false
                scheduleRetry()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "discoverServices 异常: ${it.message}")
            discoveryListener = null
            _scanning.value = false
            scheduleRetry()
        }
    }

    /** NSD 启动/运行失败时 5 秒后自动重试（beacon 通道不受影响） */
    private fun scheduleRetry() {
        if (!discoveryWanted || retryScheduled) return
        retryScheduled = true
        executor.execute {
            Thread.sleep(5_000)
            retryScheduled = false
            if (discoveryWanted && discoveryListener == null) {
                Log.d(TAG, "NSD 自动重试")
                startDiscovery()
            }
        }
    }

    /** 手动重新扫描：停掉当前扫描清空结果后重新开始 */
    fun restartDiscovery() {
        val wasWanted = discoveryWanted
        stopDiscovery()
        if (wasWanted) startDiscovery()
    }

    fun stopDiscovery() {
        discoveryWanted = false
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        _scanning.value = false
        releaseMulticastLock()
        updateBeaconListener()
        synchronized(lastBeaconSeen) { lastBeaconSeen.clear() }
        synchronized(resolving) { resolving.clear() }
        serviceNameToId.clear()
        onlineDevices.clear()
        publish()
    }

    // ---------------- 手动添加 / 网段深度扫描 ----------------

    private val _sweeping = MutableStateFlow(false)
    val sweeping: StateFlow<Boolean> = _sweeping

    /**
     * 手动添加一台设备：直接对指定 IP 请求 /info。
     * 端口依次尝试：本机配置的端口、默认 8765。成功返回设备，失败返回 null。
     */
    fun addManualHost(ip: String): LanDevice? {
        val ports = listOf(settings.serverPort, LanSettings.DEFAULT_PORT).distinct()
        ports.forEach { port ->
            runCatching { client.fetchInfo(ip, port, timeoutMs = 2_000) }
                .onFailure { e ->
                    // 被对方拉黑：记录 blockedBy（对方 /info 的 403 带 deviceId）
                    (e as? BlockedByException)?.blockedByDeviceId?.let { id ->
                        settings.addBlockedBy(id)
                        onlineDevices.remove(id)
                        publish()
                    }
                }
                .getOrNull()?.let { info ->
                    val deviceId = info.get("deviceId")?.asString ?: return@let
                    if (deviceId == settings.deviceId) return@let
                    healBlockedBy(deviceId)
                    val device = LanDevice(
                        deviceId = deviceId,
                        name = info.get("name")?.asString ?: ip,
                        model = info.get("model")?.asString ?: "",
                        host = ip,
                        port = port,
                        sharing = info.get("sharing")?.asBoolean == true,
                        online = true
                    )
                    addOrUpdate("manual-$deviceId", device)
                    return device
                }
        }
        return null
    }

    /**
     * 深度扫描：遍历本机所在 /24 网段所有地址，逐个探测 /info。
     * 完全不依赖 mDNS / 广播，只要对方服务在运行就能找到。
     */
    fun sweepSubnet() {
        if (_sweeping.value) return
        val myIp = localIp() ?: return
        _sweeping.value = true
        executor.execute {
            val parts = myIp.split(".")
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val myLast = parts[3].toIntOrNull() ?: -1
            val pool = Executors.newFixedThreadPool(48)
            for (i in 1..254) {
                if (i == myLast) continue
                val ip = "$prefix.$i"
                pool.execute {
                    addManualHost(ip)
                }
            }
            pool.shutdown()
            runCatching { pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS) }
            _sweeping.value = false
        }
    }

    /** 本机局域网 IPv4 地址 */
    fun localIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun makeResolveListener() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
            info ?: return
            Log.w(TAG, "解析失败: ${info.serviceName} code=$errorCode")
            synchronized(resolving) { resolving.remove(info.serviceName) }
            _stats.value = _stats.value.copy(resolveFail = _stats.value.resolveFail + 1)
        }

        override fun onServiceResolved(info: NsdServiceInfo?) {
            info ?: return
            synchronized(resolving) { resolving.remove(info.serviceName) }
            _stats.value = _stats.value.copy(resolveOk = _stats.value.resolveOk + 1)
            val host = if (Build.VERSION.SDK_INT >= 34) {
                // 优先 IPv4，避免拿到 fe80:: 开头的链路本地 IPv6
                info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
                    ?: info.hostAddresses.firstOrNull()?.hostAddress
            } else {
                @Suppress("DEPRECATION") info.host?.hostAddress
            }
            val port = info.port
            val serviceName = info.serviceName
            val txtId = info.attr("deviceId")
            Log.d(TAG, "解析成功: $serviceName -> $host:$port txtId=$txtId")

            if (host == null) {
                _stats.value = _stats.value.copy(httpFail = _stats.value.httpFail + 1)
                return
            }

            // 优先用 HTTP /info 校准（部分机型 TXT 拿不到）
            executor.execute {
                val result = runCatching { client.fetchInfo(host, port) }
                val blockedBy = result.exceptionOrNull() as? BlockedByException
                if (blockedBy != null) {
                    // 对方明确拒绝（我被拉黑）：记录 blockedBy，并从列表移除该设备
                    val id = blockedBy.blockedByDeviceId ?: txtId
                    if (id != null && id != settings.deviceId) {
                        settings.addBlockedBy(id)
                        onlineDevices.remove(id)
                        publish()
                    }
                    return@execute
                }
                val viaHttp = result.getOrNull()
                when {
                    viaHttp != null -> {
                        val deviceId = viaHttp.get("deviceId")?.asString ?: return@execute
                        if (deviceId == settings.deviceId) {  // 扫到自己
                            _stats.value = _stats.value.copy(skipSelf = _stats.value.skipSelf + 1)
                            return@execute
                        }
                        healBlockedBy(deviceId)  // /info 通了说明对方已不再拉黑本机
                        addOrUpdate(
                            serviceName, LanDevice(
                                deviceId = deviceId,
                                name = viaHttp.get("name")?.asString
                                    ?: serviceName.removePrefix(SERVICE_NAME_PREFIX),
                                model = viaHttp.get("model")?.asString ?: "",
                                host = host,
                                port = port,
                                sharing = viaHttp.get("sharing")?.asBoolean == true,
                                online = true
                            )
                        )
                    }
                    txtId != null && txtId != settings.deviceId -> {
                        // HTTP 不通但 TXT 可用：用 TXT 信息
                        addOrUpdate(
                            serviceName, LanDevice(
                                deviceId = txtId,
                                name = info.attr("name")
                                    ?: serviceName.removePrefix(SERVICE_NAME_PREFIX),
                                host = host,
                                port = port,
                                sharing = info.attr("sharing") == "1",
                                online = true
                            )
                        )
                    }
                    txtId == settings.deviceId -> {
                        _stats.value = _stats.value.copy(skipSelf = _stats.value.skipSelf + 1)
                    }
                    else -> {
                        Log.d(TAG, "服务 $serviceName 无 TXT 且 HTTP 不通，忽略")
                        _stats.value = _stats.value.copy(httpFail = _stats.value.httpFail + 1)
                    }
                }
            }
        }
    }

    private fun addOrUpdate(serviceName: String, device: LanDevice) {
        // 本机黑名单设备、以及"拉黑了本机"的设备都不出现在扫描结果里
        if (device.deviceId in settings.getBlockedDevices()) return
        if (device.deviceId in settings.getBlockedBy()) return
        serviceNameToId[serviceName] = device.deviceId
        onlineDevices[device.deviceId] = device
        publish()
    }

    /** 对方 /info 返回 200 说明已不再拉黑本机：自愈清除 blockedBy 记录 */
    private fun healBlockedBy(deviceId: String) {
        if (deviceId in settings.getBlockedBy()) {
            settings.removeBlockedBy(deviceId)
        }
    }

    private fun publish() {
        _discovered.value = onlineDevices.values.sortedBy { it.name }
    }

    private fun NsdServiceInfo.attr(key: String): String? =
        attributes[key]?.let { String(it, StandardCharsets.UTF_8) }

    // ---------------- UDP beacon 实现 ----------------

    /** 构造本机 beacon 报文 */
    private fun buildBeaconJson(): ByteArray = Gson().toJson(JsonObject().apply {
        addProperty("deviceId", settings.deviceId)
        addProperty("name", settings.deviceName)
        addProperty("model", settings.deviceModel)
        addProperty("port", beaconPort)
        addProperty("sharing", settings.sharing)
    }).toByteArray(StandardCharsets.UTF_8)

    /** 「可被发现」开启时：每 3 秒广播一次本机信息（广播 + 组播双发） */
    private fun startBeaconSender(port: Int) {
        beaconPort = port
        if (beaconSenderRunning) return
        beaconSenderRunning = true
        updateBeaconListener()  // 可被发现方也要监听：用于回应扫描方的 query
        executor.execute {
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrNull() ?: return@execute
            sendSocket = socket
            // 广播地址 + 组播组（有的网络拦广播但放行组播，反之亦然，双发兜底）
            while (beaconSenderRunning) {
                val targets = broadcastAddresses().map { it to BEACON_PORT } +
                    (InetAddress.getByName(MULTICAST_GROUP) to MULTICAST_PORT)
                val json = buildBeaconJson()
                targets.forEach { (addr, p) ->
                    runCatching {
                        socket.send(DatagramPacket(json, json.size, addr, p))
                    }
                }
                Thread.sleep(3_000)
            }
            runCatching { socket.close() }
            sendSocket = null
        }
    }

    private fun stopBeaconSender() {
        beaconSenderRunning = false
        updateBeaconListener()
    }

    /** 收到扫描方的 query：立即单播回一个 beacon，对方无需等 3 秒周期 */
    private fun replyBeacon(target: InetAddress) {
        val json = buildBeaconJson()
        val shared = sendSocket
        runCatching {
            val s = shared ?: DatagramSocket().apply { broadcast = true }
            s.send(DatagramPacket(json, json.size, target, BEACON_PORT))
            if (shared == null) s.close()
        }
    }

    /** 扫描开始时主动询问：广播 + 组播各发 3 次 query，提高到达率 */
    private fun sendQuery() {
        executor.execute {
            val json = """{"query":1}""".toByteArray(StandardCharsets.UTF_8)
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrNull() ?: return@execute
            val targets = broadcastAddresses().map { it to BEACON_PORT } +
                (InetAddress.getByName(MULTICAST_GROUP) to MULTICAST_PORT)
            repeat(3) { i ->
                if (i > 0) Thread.sleep(500)
                targets.forEach { (addr, p) ->
                    runCatching {
                        socket.send(DatagramPacket(json, json.size, addr, p))
                    }
                }
            }
            runCatching { socket.close() }
        }
    }

    /** 「扫描中」或「可被发现」任一开启 → 保持监听；都关 → 释放监听 socket */
    private fun updateBeaconListener() {
        if (discoveryWanted || beaconSenderRunning) startBeaconListener()
        else stopBeaconListener()
    }

    /** 同时监听广播通道（8766）和组播通道（8767）；异常退出自动重建 */
    private fun startBeaconListener() {
        if (listenRunning) return
        listenRunning = true
        startListenLoop("broadcast") {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(BEACON_PORT))
            }
        }
        startListenLoop("multicast") {
            java.net.MulticastSocket(MULTICAST_PORT).apply {
                reuseAddress = true
                joinGroup(
                    java.net.InetSocketAddress(
                        InetAddress.getByName(MULTICAST_GROUP), MULTICAST_PORT
                    ),
                    wifiInterface()  // 显式选 WiFi 网卡，避免 join 到流量接口
                )
            }
        }
    }

    /**
     * 单通道监听循环：socket 异常死亡后，只要仍需监听就 3 秒后重建。
     * （旧实现 socket 死一次就永久失效，导致"长时间搜不到设备"）
     */
    private fun startListenLoop(name: String, binder: () -> DatagramSocket) {
        executor.execute {
            while (listenRunning) {
                val socket = runCatching { binder() }.getOrNull()
                if (socket == null) {
                    if (!listenRunning) break
                    Log.w(TAG, "beacon $name 通道 bind 失败，3 秒后重试")
                    runCatching { Thread.sleep(3_000) }
                    continue
                }
                synchronized(listenSockets) { listenSockets.add(socket) }
                receiveLoop(socket)
                runCatching { socket.close() }
                synchronized(listenSockets) { listenSockets.remove(socket) }
                if (listenRunning) {
                    Log.w(TAG, "beacon $name 通道异常退出，3 秒后重建")
                    runCatching { Thread.sleep(3_000) }
                }
            }
        }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val gson = Gson()
        val buf = ByteArray(2048)
        while (listenRunning) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                break
            }
            runCatching {
                val json = gson.fromJson(
                    String(packet.data, 0, packet.length, StandardCharsets.UTF_8),
                    JsonObject::class.java
                )
                // query 包：本机可被发现时立即单播回应
                if (json.get("query")?.asBoolean == true) {
                    if (beaconSenderRunning) replyBeacon(packet.address)
                    return@runCatching
                }
                // 普通 beacon：仅在扫描时处理
                if (!discoveryWanted) return@runCatching
                val deviceId = json.get("deviceId")?.asString ?: return@runCatching
                if (deviceId == settings.deviceId) return@runCatching  // 自己的广播
                _stats.value = _stats.value.copy(beaconRx = _stats.value.beaconRx + 1)
                val port = json.get("port")?.asInt ?: return@runCatching
                synchronized(lastBeaconSeen) {
                    lastBeaconSeen[deviceId] = System.currentTimeMillis()
                }
                addOrUpdate(
                    "beacon-$deviceId",
                    LanDevice(
                        deviceId = deviceId,
                        name = json.get("name")?.asString ?: "未知设备",
                        model = json.get("model")?.asString ?: "",
                        host = packet.address.hostAddress,
                        port = port,
                        sharing = json.get("sharing")?.asBoolean == true,
                        online = true
                    )
                )
            }
        }
    }

    /** 关闭监听：直接 close socket 唤醒阻塞的 receive，循环看到 listenRunning=false 自然退出 */
    private fun stopBeaconListener() {
        listenRunning = false
        val sockets = synchronized(listenSockets) { listenSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
    }

    /** 仅 beacon 发现的设备 15 秒没收到新 beacon → 剔除，避免"幽灵在线" */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        executor.execute {
            while (watchdogRunning && discoveryWanted) {
                Thread.sleep(5_000)
                val now = System.currentTimeMillis()
                var changed = false
                // 先快照 key 再逐个处理：addOrUpdate 在其他线程并发写入，不能边遍历边删
                val ids = synchronized(onlineDevices) { onlineDevices.keys.toList() }
                ids.forEach { id ->
                    // 有 NSD 或手动来源的设备由各自机制管理，不动
                    val hasOtherSource = serviceNameToId.any { (k, v) ->
                        v == id && !k.startsWith("beacon-")
                    }
                    if (hasOtherSource) return@forEach
                    val seen = synchronized(lastBeaconSeen) { lastBeaconSeen[id] }
                        ?: return@forEach
                    if (now - seen > 15_000) {
                        Log.d(TAG, "beacon 超时剔除: $id")
                        synchronized(onlineDevices) { onlineDevices.remove(id) }
                        serviceNameToId.entries.removeIf { it.value == id }
                        synchronized(lastBeaconSeen) { lastBeaconSeen.remove(id) }
                        changed = true
                    }
                }
                if (changed) publish()
            }
            watchdogRunning = false
        }
    }

    /** 本机局域网 IP 所在的网卡（组播 join 用） */
    private fun wifiInterface(): java.net.NetworkInterface? = runCatching {
        val ip = localIp() ?: return null
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { ni -> ni.inetAddresses.toList().any { it.hostAddress == ip } }
    }.getOrNull()

    /** 计算广播地址：255.255.255.255 + 当前子网广播地址 */
    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo.ipAddress  // IPv4，小端
            if (ip != 0) {
                val subnet = ip and 0xFFFFFF
                val broadcast = subnet or 0xFF000000.toInt()
                val bytes = byteArrayOf(
                    (broadcast and 0xFF).toByte(),
                    (broadcast shr 8 and 0xFF).toByte(),
                    (broadcast shr 16 and 0xFF).toByte(),
                    (broadcast shr 24 and 0xFF).toByte()
                )
                list.add(InetAddress.getByAddress(bytes))
            }
        }
        return list
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("clipditto_nsd").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) runCatching { it.release() } }
        multicastLock = null
    }

    companion object {
        private const val TAG = "LanDiscovery"
        const val SERVICE_TYPE = "_clipditto._tcp."
        private const val SERVICE_NAME_PREFIX = "ClipDitto-"
        private const val BEACON_PORT = 8766
        private const val MULTICAST_GROUP = "239.255.60.60"
        private const val MULTICAST_PORT = 8767
    }
}
