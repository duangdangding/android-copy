package com.clipditto.app.share

import android.content.Context

/**
 * 「文件共享」功能的开关存储，SharedPreferences 文件 "file_share"。
 * 与局域网同步（lan_sync）完全独立：无配对、无配对码、无黑名单。
 * 接收能力常驻（始终可被其他设备发现和发送），这里只存「自动接收」开关。
 */
class FileShareSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("file_share", Context.MODE_PRIVATE)

    /** 自动接收：开启后来文件直接保存；关闭（默认）时需本机手动确认（弹窗或通知） */
    var autoReceive: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RECEIVE, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_RECEIVE, v).apply()

    companion object {
        private const val KEY_AUTO_RECEIVE = "auto_receive"

        /** 在线状态 UDP 广播端口（传输端口复用局域网同步的 LanSettings.serverPort） */
        const val BEACON_PORT = 8771

        /** 组播兜底通道（有的路由器拦广播但放行组播） */
        const val MULTICAST_GROUP = "239.255.60.61"
        const val MULTICAST_PORT = 8772
    }
}
