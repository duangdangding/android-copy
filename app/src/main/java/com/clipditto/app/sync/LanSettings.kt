package com.clipditto.app.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * 局域网同步的开关与配对信息存储。
 *
 * 三个核心开关（全部默认关闭）：
 * - discoverable：可被其他设备扫描到（控制 NSD 服务注册）
 * - sharing：允许其他设备拉取本机剪贴板（控制内容接口是否响应）
 * - autoSync：自动把已配对设备的内容同步到本机（轮询）
 */
class LanSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("lan_sync", Context.MODE_PRIVATE)
    private val gson = Gson()

    var discoverable: Boolean
        get() = prefs.getBoolean(KEY_DISCOVERABLE, false)
        set(v) = prefs.edit().putBoolean(KEY_DISCOVERABLE, v).apply()

    var sharing: Boolean
        get() = prefs.getBoolean(KEY_SHARING, false)
        set(v) = prefs.edit().putBoolean(KEY_SHARING, v).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_SYNC, v).apply()

    /** 自动同意配对请求：关闭时（默认）每次配对需本机手动点同意 */
    var autoAcceptPair: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ACCEPT_PAIR, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_ACCEPT_PAIR, v).apply()

    /** 本机设备唯一标识，首次启动生成后固定不变 */
    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    /** 本机设备名（默认随机昵称，可修改），显示在别人的设备列表里 */
    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)
            ?: randomName().also { deviceName = it }
        set(v) = prefs.edit().putString(KEY_DEVICE_NAME, v).apply()

    /** 生成随机默认名称，如「小火箭-4821」 */
    private fun randomName(): String {
        val words = listOf(
            "小火箭", "闪电", "海豚", "柠檬", "琥珀", "棉花糖", "北极星",
            "风铃", "墨鱼丸", "咖啡豆", "蒲公英", "小鲸鱼", "萤火", "山竹"
        )
        return "${words.random()}-${(1000..9999).random()}"
    }

    /** 本机 HTTP 服务端口，默认 8765 */
    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = prefs.edit().putInt(KEY_PORT, v).apply()

    /**
     * 本机设备型号（营销名），如 "Xiaomi 15 Pro"、"联想拯救者 Y700 五代"。
     * 三级回退：内置对照表 → 系统 device_name（多数厂商写的是营销名）→ 厂商+型号代码。
     */
    val deviceModel: String
        get() = KNOWN_MODELS[android.os.Build.MODEL]
            ?: runCatching {
                android.provider.Settings.Global.getString(
                    appContext.contentResolver, "device_name"
                )
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    /** 配对码（6 位数字）：其他设备拉取本机内容时需提供 */
    val pairingToken: String
        get() {
            var t = prefs.getString(KEY_TOKEN, null)
            if (t.isNullOrBlank()) {
                t = (100000..999999).random().toString()
                prefs.edit().putString(KEY_TOKEN, t).apply()
            }
            return t
        }

    fun regenerateToken(): String {
        val t = (100000..999999).random().toString()
        prefs.edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** 已配对设备（含离线），key = deviceId */
    fun getPairedDevices(): Map<String, LanDevice> {
        val json = prefs.getString(KEY_PAIRED, null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, LanDevice>>() {}.type
        return runCatching { gson.fromJson<Map<String, LanDevice>>(json, type) }
            .getOrNull() ?: emptyMap()
    }

    fun savePairedDevice(device: LanDevice) {
        val map = getPairedDevices().toMutableMap()
        map[device.deviceId] = device
        prefs.edit().putString(KEY_PAIRED, gson.toJson(map)).apply()
    }

    fun removePairedDevice(deviceId: String) {
        val map = getPairedDevices().toMutableMap()
        map.remove(deviceId)
        prefs.edit().putString(KEY_PAIRED, gson.toJson(map)).apply()
    }

    /** 已拉黑（被本机主动取消配对）的设备 id 集合 */
    fun getBlockedDevices(): Set<String> {
        val json = prefs.getString(KEY_BLOCKED, null) ?: return emptySet()
        return runCatching {
            gson.fromJson<Set<String>>(json, object : TypeToken<Set<String>>() {}.type)
        }.getOrNull() ?: emptySet()
    }

    fun blockDevice(deviceId: String) {
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(getBlockedDevices() + deviceId))
            .apply()
    }

    fun unblockDevice(deviceId: String) {
        prefs.edit()
            .putString(KEY_BLOCKED, gson.toJson(getBlockedDevices() - deviceId))
            .apply()
    }

    /** 某设备上次同步到的时间戳（增量拉取的游标） */
    fun getLastSync(deviceId: String): Long = prefs.getLong("$KEY_LAST_SYNC$deviceId", 0L)

    fun setLastSync(deviceId: String, ts: Long) {
        prefs.edit().putLong("$KEY_LAST_SYNC$deviceId", ts).apply()
    }

    companion object {
        /** 型号代码 → 营销名对照表（系统 device_name 不可靠时使用） */
        private val KNOWN_MODELS = mapOf(
            "TB323FU" to "联想拯救者 Y700 五代",
            "TB320FC" to "联想拯救者 Y700 二代",
            "TB321FU" to "联想拯救者 Y700 三代",
            "2410DPN6CC" to "Xiaomi 15 Pro",
            "23046RP50C" to "Xiaomi Pad 6 Pro"
        )

        private const val KEY_DISCOVERABLE = "discoverable"
        private const val KEY_SHARING = "sharing"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_AUTO_ACCEPT_PAIR = "auto_accept_pair"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_PAIRED = "paired_devices"
        private const val KEY_BLOCKED = "blocked_devices"
        private const val KEY_LAST_SYNC = "last_sync_"
        private const val KEY_PORT = "server_port"
        const val DEFAULT_PORT = 8765
    }
}
