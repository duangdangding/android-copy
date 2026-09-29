package com.clipditto.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.clipditto.app.service.BootReceiver

/**
 * 应用设置项的统一读写入口（存放在 "settings" SharedPreferences）。
 * 所有面向用户的提示内容必须使用中文。
 */
object AppSettings {

    private fun prefs(context: Context) =
        context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    // ---------------- 悬浮球 ----------------

    private const val KEY_BALL_ENABLED = "ball_enabled"

    /** 是否显示悬浮球（默认显示）；关闭后后台监听不受影响 */
    fun isBallEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BALL_ENABLED, true)

    fun setBallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BALL_ENABLED, enabled).apply()
    }

    // ---------------- 悬浮球外观 ----------------

    private const val KEY_BALL_SIZE_DP = "ball_size_dp"

    /** 悬浮球边长（dp），默认 40，范围 32~64 */
    fun getBallSizeDp(context: Context): Int =
        prefs(context).getInt(KEY_BALL_SIZE_DP, 40)

    fun setBallSizeDp(context: Context, dp: Int) {
        prefs(context).edit().putInt(KEY_BALL_SIZE_DP, dp.coerceIn(32, 64)).apply()
    }

    private const val KEY_BALL_ALPHA = "ball_alpha"

    /** 悬浮球透明度（百分比 30~100），默认 100 不透明 */
    fun getBallAlpha(context: Context): Int =
        prefs(context).getInt(KEY_BALL_ALPHA, 100)

    fun setBallAlpha(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_BALL_ALPHA, percent.coerceIn(30, 100)).apply()
    }

    // ---------------- 悬浮面板 ----------------

    private const val KEY_REMEMBER_PANEL_SIZE = "remember_panel_size"

    /** 是否记住用户调整后的弹窗列表大小（默认记住）；关闭后每次打开都用默认大小 */
    fun isRememberPanelSize(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMEMBER_PANEL_SIZE, true)

    fun setRememberPanelSize(context: Context, remember: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMEMBER_PANEL_SIZE, remember).apply()
    }

    private const val KEY_PANEL_ALPHA = "panel_alpha"

    /** 弹窗列表透明度（百分比 30~100），默认 100 不透明 */
    fun getPanelAlpha(context: Context): Int =
        prefs(context).getInt(KEY_PANEL_ALPHA, 100)

    fun setPanelAlpha(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_PANEL_ALPHA, percent.coerceIn(30, 100)).apply()
    }

    // ---------------- 外观主题 ----------------

    private const val KEY_THEME_MODE = "theme_mode"

    /** 主题模式：跟随系统（默认）/ 浅色 / 深色，值为 AppCompatDelegate 的 MODE_NIGHT_* */
    fun getThemeMode(context: Context): Int =
        prefs(context).getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME_MODE, mode).apply()
    }

    /** 按设置应用主题（App 启动时调用一次即可） */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(getThemeMode(context))
    }

    // ---------------- 记录规则 ----------------

    private const val KEY_RETENTION_DAYS = "retention_days"

    /** 记录保留天数，0 = 不限制（默认） */
    fun getRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days.coerceAtLeast(0)).apply()
    }

    // ---------------- 存储位置 ----------------

    private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"

    /** 备份（数据库 zip）与配置导出的保存文件夹（SAF 目录树）；null = 未设置，每次手动选位置 */
    fun getBackupTreeUri(context: Context): String? =
        prefs(context).getString(KEY_BACKUP_TREE_URI, null)

    fun setBackupTreeUri(context: Context, treeUri: String?) {
        prefs(context).edit().putString(KEY_BACKUP_TREE_URI, treeUri).apply()
    }
}
