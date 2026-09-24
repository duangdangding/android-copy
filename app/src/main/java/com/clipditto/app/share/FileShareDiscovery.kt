package com.clipditto.app.share

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.clipditto.app.sync.LanSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** 文件共享在线设备（与局域网同步的设备列表完全独立，无配对/黑名单概念） */
data class FsDevice(
    val deviceId: String,
    val name: String,
    val model: String?,
    val host: String,
    val port: Int,
    /** true = 来自 8766 同步信标的旧协议设备（PC 端等，只支持 POST /recv） */
    val legacy: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * 文件共享的设备发现：独立的 UDP beacon 通道（8771 广播 + 8772 组播双发兜底）。
 *
 * - 本机每 3 秒广播一次 {"fs":1,...}（常驻）；
 * - 兼容 PC 端（copy-pc）：同时监听 8766 广播 + 8767 组播的同步式信标
 *   （无 fs 字段，{"deviceId","name","model","port",...}），并入设备列表并标记 legacy；
 *   PC 不回查询包，所以其条目过期时间放宽到 15 秒；
 * - 同一 deviceId 同时从 8771 与 8766 出现时合并为一条，优先 8771（非 legacy）；
 * - 页面进入前台时调用 [queryNow] 主动询问（{"fsq":1}），在线的新协议设备立即单播回应；
 * - 10 秒没收到某设备的 beacon 则剔除（legacy 15 秒），避免"幽灵在线"；
 * - 监听 socket 异常死亡后 3 秒自动重建（同 LanDiscovery 的自愈设计）。
 */
class FileShareDiscovery(
    private val context: Context,
    private val lanSettings: LanSettings,
    /** 本机文件传输服务的实际监听端口（写入 beacon，端口被占用时可能是系统分配值） */
    private val myPort: () -> Int
) {
    private val executor = Executors.newCachedThreadPool()
    private val gson = Gson()
    private var multicastLock: WifiManager.MulticastLock? = null

    /** 在线设备表，key = deviceId */
    private val onlineDevices = mutableMapOf<String, FsDevice>()

    private val _devices = MutableStateFlow<List<FsDevice>>(emptyList())
    val devices: StateFlow<List<FsDevice>> = _devices

    @Volatile private var beaconSenderRunning = false
    private var sendSocket: DatagramSocket? = null

    @Volatile private var listenRunning = false
    private val listenSockets = mutableListOf<DatagramSocket>()
    @Volatile private var watchdogRunning = false
    @Volatile private var started = false

    /** 初始化后调用一次：监听通道 + 超时剔除常驻运行（beacon 发送由开关控制） */
    fun start() {
        if (started) return
        started = true
        acquireMulticastLock()
        startBeaconListener()
        startWatchdog()
    }

    // ---------------- beacon 发送（仅「允许接收文件」开启时） ----------------

    /** 构造本机 beacon 报文 */
    private fun buildBeaconJson(): ByteArray = gson.toJson(JsonObject().apply {
        addProperty("fs", 1)
        addProperty("deviceId", lanSettings.deviceId)
        addProperty("name", lanSettings.deviceName)
        addProperty("model", lanSettings.deviceModel)
        addProperty("port", myPort())
    }).toByteArray(StandardCharsets.UTF_8)

    /** 每 3 秒广播本机信息（广播地址 + 组播组双发） */
    fun startBeaconSender() {
        if (beaconSenderRunning) return
        beaconSenderRunning = true
        executor.execute {
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrNull()
            if (socket == null) {
                beaconSenderRunning = false
                return@execute
            }
            sendSocket = socket
            while (beaconSenderRunning) {
                val targets = broadcastAddresses().map { it to FileShareSettings.BEACON_PORT } +
                    (InetAddress.getByName(FileShareSettings.MULTICAST_GROUP) to
                        FileShareSettings.MULTICAST_PORT)
                val json = buildBeaconJson()
                targets.forEach { (addr, p) ->
                    runCatching { socket.send(DatagramPacket(json, json.size, addr, p)) }
                }
                Thread.sleep(3_000)
            }
            runCatching { socket.close() }
            sendSocket = null
        }
    }

    fun stopBeaconSender() {
        beaconSenderRunning = false
    }

    /**
     * 手动刷新时顺手触发：关掉现有监听 socket，让监听循环立即重建。
     * 正常运行时只是 3 秒内完成一次无害重建；socket 静默死亡时借此立即恢复。
     */
    fun kickListener() {
        val sockets = synchronized(listenSockets) { listenSockets.toList() }
        sockets.forEach { runCatching { it.close() } }
    }

    /** 收到其他设备的 query：本机正在广播时立即单播回应，对方无需等 3 秒周期 */
    private fun replyBeacon(target: InetAddress) {
        val json = buildBeaconJson()
        val shared = sendSocket
        runCatching {
            val s = shared ?: DatagramSocket().apply { broadcast = true }
            s.send(DatagramPacket(json, json.size, target, FileShareSettings.BEACON_PORT))
            if (shared == null) s.close()
        }
    }

    /** 主动询问：广播 + 组播各发 3 次 query，提高到达率（页面进入前台时调用） */
    fun queryNow() {
        executor.execute {
            val json = """{"fsq":1}""".toByteArray(StandardCharsets.UTF_8)
            val socket = runCatching {
                DatagramSocket().apply { broadcast = true }
            }.getOrNull() ?: return@execute
            val targets = broadcastAddresses().map { it to FileShareSettings.BEACON_PORT } +
                (InetAddress.getByName(FileShareSettings.MULTICAST_GROUP) to
                    FileShareSettings.MULTICAST_PORT)
            repeat(3) { i ->
                if (i > 0) Thread.sleep(500)
                targets.forEach { (addr, p) ->
                    runCatching { socket.send(DatagramPacket(json, json.size, addr, p)) }
                }
            }
            runCatching { socket.close() }
        }
    }

    // ---------------- beacon 监听（常开） ----------------

    /** 同时监听本协议通道（8771 广播 + 8772 组播）与 PC 同步信标通道（8766 广播 + 8767 组播）；
     *  8766/8767 可能与 LanDiscovery 共用，必须 reuseAddress；异常退出自动重建 */
    private fun startBeaconListener() {
        if (listenRunning) return
        listenRunning = true
        startListenLoop("broadcast") {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(FileShareSettings.BEACON_PORT))
            }
        }
        startListenLoop("multicast") {
            MulticastSocket(FileShareSettings.MULTICAST_PORT).apply {
                reuseAddress = true
                joinGroup(
                    InetSocketAddress(
                        InetAddress.getByName(FileShareSettings.MULTICAST_GROUP),
                        FileShareSettings.MULTICAST_PORT
                    ),
                    wifiInterface()  // 显式选 WiFi 网卡，避免 join 到流量接口
                )
            }
        }
        // PC 端只发同步式信标（8766 广播 + 239.255.60.60:8767 组播），不回查询包
        startListenLoop("broadcast-sync") {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SYNC_BEACON_PORT))
            }
        }
        startListenLoop("multicast-sync") {
            MulticastSocket(SYNC_MULTICAST_PORT).apply {
                reuseAddress = true
                joinGroup(
                    InetSocketAddress(
                        InetAddress.getByName(SYNC_MULTICAST_GROUP),
                        SYNC_MULTICAST_PORT
                    ),
                    wifiInterface()
                )
            }
        }
    }

    /** 单通道监听循环：socket 异常死亡后，只要仍需监听就 3 秒后重建 */
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
                // query 包：本机正在广播时立即单播回应（PC 不回查询，无妨）
                if (json.get("fsq")?.asInt == 1) {
                    if (beaconSenderRunning) replyBeacon(packet.address)
                    return@runCatching
                }
                // 8771 信标带 fs=1；8766 同步信标（PC 端）没有 fs 字段，按 legacy 处理
                val isFs = json.get("fs")?.asInt == 1
                val deviceId = json.get("deviceId")?.asString ?: return@runCatching
                if (deviceId == lanSettings.deviceId) return@runCatching  // 自己的广播
                val port = json.get("port")?.asInt ?: return@runCatching
                val host = packet.address.hostAddress ?: return@runCatching
                synchronized(onlineDevices) {
                    val existing = onlineDevices[deviceId]
                    if (existing != null && !existing.legacy && !isFs) {
                        // 同一设备同时出现在 8771 与 8766：优先 8771 的信息，
                        // 8766 信标只刷新存活时间
                        onlineDevices[deviceId] =
                            existing.copy(lastSeen = System.currentTimeMillis())
                    } else {
                        onlineDevices[deviceId] = FsDevice(
                            deviceId = deviceId,
                            name = json.get("name")?.asString ?: "未知设备",
                            model = json.get("model")?.asString,
                            host = host,
                            port = port,
                            legacy = !isFs
                        )
                    }
                }
                publish()
            }
        }
    }

    /** 10 秒没收到某设备的 beacon → 剔除（PC 端不回查询，legacy 放宽到 15 秒），避免"幽灵在线" */
    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        executor.execute {
            while (watchdogRunning) {
                Thread.sleep(5_000)
                val now = System.currentTimeMillis()
                var changed = false
                synchronized(onlineDevices) {
                    val it = onlineDevices.entries.iterator()
                    while (it.hasNext()) {
                        val d = it.next().value
                        val expire = if (d.legacy) EXPIRE_LEGACY_MS else EXPIRE_MS
                        if (now - d.lastSeen > expire) {
                            it.remove()
                            changed = true
                        }
                    }
                }
                if (changed) publish()
            }
        }
    }

    private fun publish() {
        _devices.value = synchronized(onlineDevices) {
            onlineDevices.values.sortedBy { it.name }
        }
    }

    // ---------------- 网络工具 ----------------

    /** 本机局域网 IPv4 地址 */
    private fun localIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()

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
        multicastLock = wifi.createMulticastLock("clipditto_fileshare").apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
    }

    companion object {
        private const val TAG = "FileShareDiscovery"
        private const val EXPIRE_MS = 10_000L
        private const val EXPIRE_LEGACY_MS = 15_000L

        /** PC 端同步信标通道（与 LanDiscovery 同端口，靠 reuseAddress 共存） */
        private const val SYNC_BEACON_PORT = 8766
        private const val SYNC_MULTICAST_GROUP = "239.255.60.60"
        private const val SYNC_MULTICAST_PORT = 8767
    }
}
