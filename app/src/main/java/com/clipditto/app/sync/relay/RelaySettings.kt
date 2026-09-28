package com.clipditto.app.sync.relay

import android.content.Context

/**
 * 云端中继同步的配置存储（SharedPreferences 文件 "relay_sync"，与 lan_sync 互不干扰）。
 *
 * 字段口径与 PC 端 relay.rs 的 RelaySettings 一致：
 * - enabled / serverUrl / groupId / accessKey / groupKey / syncImage / lastSeq
 * - 接入密钥与分组密钥是两层密钥：接入密钥证明「我是这台服务器的合法用户」，
 *   分组密钥证明「我属于这个分组」并作为端到端加密的根密钥（可空 = 明文模式）。
 */
class RelaySettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("relay_sync", Context.MODE_PRIVATE)

    /** 总开关 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /**
     * 服务器地址：支持 `host:port`、`ws://host:port`、`wss://domain`。
     * 连接前经 [normalizeUrl] 规范化（补 ws:// 前缀与 /ws 路径）。
     */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v.trim()).apply()

    /** 接入密钥（服务器 RELAY_ACCESS_KEYS 之一），握手 HMAC 用 */
    var accessKey: String
        get() = prefs.getString(KEY_ACCESS_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ACCESS_KEY, v.trim()).apply()

    /** 分组 ID：同一组设备互相同步 */
    var groupId: String
        get() = prefs.getString(KEY_GROUP_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GROUP_ID, v.trim()).apply()

    /** 分组密钥（可选）：非空启用端到端加密，同组设备需一致；服务器只见到密文 */
    var groupKey: String
        get() = prefs.getString(KEY_GROUP_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GROUP_KEY, v.trim()).apply()

    /** 图片也经中继同步（默认关，省服务器流量/手机流量；仅 ≤4MB） */
    var syncImage: Boolean
        get() = prefs.getBoolean(KEY_SYNC_IMAGE, false)
        set(v) = prefs.edit().putBoolean(KEY_SYNC_IMAGE, v).apply()

    /** 补拉游标：服务器分配的条目序号，连接成功后 pull sinceSeq=lastSeq */
    var lastSeq: Long
        get() = prefs.getLong(KEY_LAST_SEQ, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_SEQ, v).apply()

    /** 配置是否完整（可以发起连接的最小集合） */
    fun isComplete(): Boolean =
        normalizeUrl(serverUrl).isNotEmpty() && groupId.isNotBlank() && accessKey.isNotBlank()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCESS_KEY = "access_key"
        private const val KEY_GROUP_ID = "group_id"
        private const val KEY_GROUP_KEY = "group_key"
        private const val KEY_SYNC_IMAGE = "sync_image"
        private const val KEY_LAST_SEQ = "last_seq"

        /** 经中继同步的图片上限（超出只走局域网），与 PC 端 MAX_RELAY_IMAGE 一致 */
        const val MAX_RELAY_IMAGE_BYTES = 4 * 1024 * 1024L

        /** 线上记录类型（与 PC 端 relay.rs / lan.rs 一致） */
        const val WIRE_TEXT = 0
        const val WIRE_IMAGE = 1

        /**
         * 规范化服务器地址：补 ws:// 前缀与 /ws 路径。
         * 与 PC 端 normalize_url 行为一致：
         *   "1.2.3.4:8780"            -> "ws://1.2.3.4:8780/ws"
         *   "ws://1.2.3.4:8780"       -> "ws://1.2.3.4:8780/ws"
         *   "wss://relay.example.com" -> "wss://relay.example.com/ws"
         */
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return u
            if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "ws://$u"
            val trimmed = u.trimEnd('/')
            val afterScheme = trimmed.substringAfter("://")
            return if (!afterScheme.contains('/')) "$trimmed/ws" else trimmed
        }
    }
}
