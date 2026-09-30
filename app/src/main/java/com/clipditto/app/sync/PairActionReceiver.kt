package com.clipditto.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 配对请求通知的「同意配对/拒绝」按钮回调。
 * 收到后解出对应请求的等待锁（通知的取消由 LanSyncManager 在等待返回后统一处理）。
 */
class PairActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return
        when (intent.action) {
            ACTION_ACCEPT -> LanSyncManager.resolvePendingPair(deviceId, true)
            ACTION_REJECT -> LanSyncManager.resolvePendingPair(deviceId, false)
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.clipditto.app.sync.ACTION_PAIR_ACCEPT"
        const val ACTION_REJECT = "com.clipditto.app.sync.ACTION_PAIR_REJECT"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
