package com.clipditto.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.clipditto.app.sync.LanSyncManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Shizuku 剪贴板通道：监听 binder 到来/死亡，可用时自动绑定
        runCatching { com.clipditto.app.service.ShizukuClipboard.init() }
        // 局域网同步：按开关状态恢复服务/发现
        runCatching { LanSyncManager.init(this) }
        // 文件共享：按开关状态恢复接收服务/在线广播
        runCatching { com.clipditto.app.share.FileShareManager.init(this) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "剪贴板监听",
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        // 同步结果提示：低打扰（无声音、不横幅），只进通知栏
        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            "同步结果",
            NotificationManager.IMPORTANCE_LOW
        )
        // 文件共享接收请求：需要用户及时处理，用高重要性（横幅+声音）
        val fileShareChannel = NotificationChannel(
            CHANNEL_FILE_SHARE,
            "文件共享",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(syncChannel)
        nm.createNotificationChannel(fileShareChannel)
    }

    companion object {
        const val CHANNEL_ID = "clipboard_monitor"
        const val CHANNEL_SYNC = "sync_result"
        const val CHANNEL_FILE_SHARE = "file_share"
        lateinit var instance: App
            private set
    }
}
