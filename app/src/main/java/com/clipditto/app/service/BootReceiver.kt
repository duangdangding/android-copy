package com.clipditto.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 开机后如果用户之前开启了监听，则自动启动服务 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, false)
        if (enabled && Settings.canDrawOverlays(context)) {
            ClipboardService.start(context)
        }
    }

    companion object {
        const val PREFS = "settings"
        const val KEY_SERVICE_ENABLED = "service_enabled"
        const val KEY_MONITOR_ENABLED = "monitor_enabled"
    }
}
