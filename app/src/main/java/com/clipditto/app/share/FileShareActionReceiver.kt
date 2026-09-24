package com.clipditto.app.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 文件接收请求通知的「接收/拒绝」按钮回调。
 * 收到后解出对应传输的等待锁（通知的取消由 FileShareManager 在等待返回后统一处理）。
 */
class FileShareActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_TRANSFER_ID) ?: return
        when (intent.action) {
            ACTION_ACCEPT -> FileShareManager.resolvePending(id, true)
            ACTION_REJECT -> FileShareManager.resolvePending(id, false)
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.clipditto.app.share.ACTION_ACCEPT"
        const val ACTION_REJECT = "com.clipditto.app.share.ACTION_REJECT"
        const val EXTRA_TRANSFER_ID = "transfer_id"
    }
}
