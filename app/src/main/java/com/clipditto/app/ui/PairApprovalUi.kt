package com.clipditto.app.ui

import android.app.Activity
import com.clipditto.app.sync.LanDevice
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 配对请求确认框：可弹在本 App 任意前台页面上（不限设备页）。
 * 在 HTTP 服务线程上被调用，阻塞等待用户选择。
 */
object PairApprovalUi {

    /** 等待用户选择的上限（配对请求读超时 35 秒，留 5 秒余量） */
    private const val TIMEOUT_SEC = 30L

    /** 确认结果。区分 NOT_SHOWN / TIMEOUT：前者调用方可立即走通知兜底，后者已等满 30 秒不能再等 */
    enum class Answer { APPROVED, REJECTED, NOT_SHOWN, TIMEOUT }

    /** 在 [activity] 上弹「配对请求」确认框并阻塞等待用户选择 */
    fun askBlocking(activity: Activity, requester: LanDevice): Answer {
        if (activity.isFinishing || activity.isDestroyed) return Answer.NOT_SHOWN
        val latch = CountDownLatch(1)
        val shown = AtomicBoolean(false)
        val approved = AtomicBoolean(false)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            shown.set(true)
            val modelSuffix = requester.model?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
            dialog = MaterialAlertDialogBuilder(activity)
                .setTitle("配对请求")
                .setMessage("「${requester.displayName}$modelSuffix」请求与本机配对，配对后可以访问你共享的剪贴板内容。\n\n是否同意？")
                .setCancelable(false)
                .setPositiveButton("同意配对") { _, _ ->
                    approved.set(true)
                    latch.countDown()
                }
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .show()
        }
        val decided = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        return when {
            !shown.get() -> Answer.NOT_SHOWN
            !decided -> {
                // 超时：收掉残留弹窗，按"未响应"处理（对方提示重试，而不是误报"被拒绝"）
                activity.runOnUiThread { runCatching { dialog?.dismiss() } }
                Answer.TIMEOUT
            }
            approved.get() -> Answer.APPROVED
            else -> Answer.REJECTED
        }
    }
}
