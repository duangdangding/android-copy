package com.clipditto.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import com.clipditto.app.sync.LanSyncManager
import com.google.android.material.color.DynamicColors

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 外观主题：默认跟随系统，按设置应用
        runCatching { com.clipditto.app.util.AppSettings.applyTheme(this) }
        // 动态取色（Material You）：设置开启时，Android 12+ 的主题色跟随壁纸
        runCatching {
            if (com.clipditto.app.util.AppSettings.isDynamicColor(this))
                DynamicColors.applyToActivitiesIfAvailable(this)
        }
        createNotificationChannel()
        // Shizuku 剪贴板通道：监听 binder 到来/死亡，可用时自动绑定
        runCatching { com.clipditto.app.service.ShizukuClipboard.init() }
        // 局域网同步：按开关状态恢复服务/发现
        runCatching { LanSyncManager.init(this) }
        // 云端中继同步：按开关状态恢复连接
        runCatching { com.clipditto.app.sync.relay.RelaySyncManager.init(this) }
        // 文件共享：按开关状态恢复接收服务/在线广播
        runCatching { com.clipditto.app.share.FileShareManager.init(this) }
        // 追踪当前前台页面：配对请求等全局确认框要弹在用户正在看的页面上
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                topActivity = activity
            }

            override fun onActivityPaused(activity: Activity) {
                if (topActivity === activity) topActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
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
        // 文件局域网传输接收请求：需要用户及时处理，用高重要性（横幅+声音）
        val fileShareChannel = NotificationChannel(
            CHANNEL_FILE_SHARE,
            "文件局域网传输",
            NotificationManager.IMPORTANCE_HIGH
        )
        // 配对请求：需要用户及时处理，用高重要性（横幅+声音）
        val pairChannel = NotificationChannel(
            CHANNEL_PAIR,
            "配对请求",
            NotificationManager.IMPORTANCE_HIGH
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(syncChannel)
        nm.createNotificationChannel(fileShareChannel)
        nm.createNotificationChannel(pairChannel)
    }

    companion object {
        const val CHANNEL_ID = "clipboard_monitor"
        const val CHANNEL_SYNC = "sync_result"
        const val CHANNEL_FILE_SHARE = "file_share"
        const val CHANNEL_PAIR = "pair_request"
        lateinit var instance: App
            private set

        /** 本 App 当前处于前台的页面（无 = App 在后台），全局确认框弹在它上面 */
        @Volatile
        var topActivity: Activity? = null
            private set
    }
}
