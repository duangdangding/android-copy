package com.clipditto.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 长按触感反馈：跟随设置开关（AppSettings.haptic_enabled，默认开）。
 * Android 10+ 用系统预置的 CLICK 触感（线性马达清脆、不受"触摸反馈"全局开关限制）；
 * 更老版本退回 LONG_PRESS 反馈。
 */
object Haptics {

    fun longPress(context: Context, view: View) {
        if (!AppSettings.isHapticEnabled(context)) return
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            when {
                // Android 10+：优先系统预置 CLICK 触感（线性马达清脆）；硬件不支持时降级为短振动
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val supported = vibrator.areEffectsSupported(VibrationEffect.EFFECT_CLICK)
                    if (supported.firstOrNull() == Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else {
                        vibrator.vibrate(
                            VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                        )
                    }
                }
                // Android 8~9：一次性短振动
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                else ->
                    view.performHapticFeedback(
                        HapticFeedbackConstants.LONG_PRESS,
                        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
                    )
            }
        }
    }
}
