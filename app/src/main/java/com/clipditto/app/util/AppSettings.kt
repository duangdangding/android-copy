package com.clipditto.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.clipditto.app.R
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

    // ---------------- 操作反馈 ----------------

    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"

    /** 长按触感反馈（长按记录/悬浮球时震动提示），默认开启 */
    fun isHapticEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAPTIC_ENABLED, true)

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
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

    private const val KEY_WINDOW_EFFECT = "window_effect"

    /** 窗口效果：默认（不透明纯色底） */
    const val WINDOW_EFFECT_DEFAULT = 0

    /** 窗口效果：亚克力（毛玻璃，Android 12+ 模糊底层内容，低版本降级为半透明） */
    const val WINDOW_EFFECT_ACRYLIC = 1

    /** 窗口效果：半透明（无模糊，全版本可用） */
    const val WINDOW_EFFECT_TRANSLUCENT = 2

    /** 全局窗口效果（各页面窗口 + 悬浮面板），默认 WINDOW_EFFECT_DEFAULT */
    fun getWindowEffect(context: Context): Int =
        prefs(context).getInt(KEY_WINDOW_EFFECT, WINDOW_EFFECT_DEFAULT)

    fun setWindowEffect(context: Context, effect: Int) {
        prefs(context).edit().putInt(KEY_WINDOW_EFFECT, effect).apply()
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

    private const val KEY_DYNAMIC_COLOR = "dynamic_color"

    /** 跟随系统主题色（Material You 动态取色，仅 Android 12+ 有效），默认关闭 */
    fun isDynamicColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, false)

    fun setDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    // ---------------- 记录规则 ----------------

    private const val KEY_RETENTION_DAYS = "retention_days"

    /** 记录保留天数，0 = 不限制（默认） */
    fun getRetentionDays(context: Context): Int =
        prefs(context).getInt(KEY_RETENTION_DAYS, 0)

    fun setRetentionDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days.coerceAtLeast(0)).apply()
    }

    // ---------------- 自定义背景 ----------------

    private const val KEY_BG_ENABLED = "bg_enabled"

    /** 是否启用自定义背景（图片覆盖主页工具栏/悬浮列表），默认关闭 */
    fun isBgEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_ENABLED, false)

    fun setBgEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_ENABLED, enabled).apply()
    }

    private const val KEY_BG_TARGET_MAIN = "bg_target_main"

    /** 背景应用到主页（含工具栏），默认开启 */
    fun isBgTargetMain(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_TARGET_MAIN, true)

    fun setBgTargetMain(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_TARGET_MAIN, on).apply()
    }

    private const val KEY_BG_TARGET_PANEL = "bg_target_panel"

    /** 背景应用到悬浮列表，默认开启 */
    fun isBgTargetPanel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BG_TARGET_PANEL, true)

    fun setBgTargetPanel(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_TARGET_PANEL, on).apply()
    }

    private const val KEY_BG_MODE = "bg_mode"

    /** 显示模式：拉伸（图片/所选区域填满目标区域） */
    const val BG_MODE_STRETCH = 0

    /** 显示模式：平铺（按原图尺寸重复排列） */
    const val BG_MODE_TILE = 1

    /** 显示模式：自定义尺寸（图片按目标区域宽高的指定百分比显示，居中） */
    const val BG_MODE_CUSTOM = 3

    /**
     * 背景图显示模式，默认拉伸。
     * 历史上 2 表示"选取区域"模式——选区现在是独立的裁剪参数（作用于原图后再按模式显示），
     * 旧值统一归位为拉伸。
     */
    fun getBgMode(context: Context): Int =
        when (prefs(context).getInt(KEY_BG_MODE, BG_MODE_STRETCH)) {
            BG_MODE_TILE -> BG_MODE_TILE
            BG_MODE_CUSTOM -> BG_MODE_CUSTOM
            else -> BG_MODE_STRETCH
        }

    fun setBgMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_BG_MODE, mode).apply()
    }

    private const val KEY_BG_ALPHA = "bg_alpha"

    /** 背景图透明度（百分比 10~100），默认 100 不透明 */
    fun getBgAlpha(context: Context): Int =
        prefs(context).getInt(KEY_BG_ALPHA, 100)

    fun setBgAlpha(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_BG_ALPHA, percent.coerceIn(10, 100)).apply()
    }

    private const val KEY_BG_REGION_L = "bg_region_l"
    private const val KEY_BG_REGION_T = "bg_region_t"
    private const val KEY_BG_REGION_R = "bg_region_r"
    private const val KEY_BG_REGION_B = "bg_region_b"

    /** 选取区域（归一化 0~1：左/上/右/下，以原图为基准），默认整张图片 */
    fun getBgRegion(context: Context): FloatArray {
        val p = prefs(context)
        return floatArrayOf(
            p.getFloat(KEY_BG_REGION_L, 0f),
            p.getFloat(KEY_BG_REGION_T, 0f),
            p.getFloat(KEY_BG_REGION_R, 1f),
            p.getFloat(KEY_BG_REGION_B, 1f)
        )
    }

    fun setBgRegion(context: Context, left: Float, top: Float, right: Float, bottom: Float) {
        prefs(context).edit()
            .putFloat(KEY_BG_REGION_L, left)
            .putFloat(KEY_BG_REGION_T, top)
            .putFloat(KEY_BG_REGION_R, right)
            .putFloat(KEY_BG_REGION_B, bottom)
            .apply()
    }

    private const val KEY_BG_ROTATION = "bg_rotation"

    /** 背景图旋转方向（0/90/180/270 度，顺时针），默认 0 不旋转 */
    fun getBgRotation(context: Context): Int {
        val v = prefs(context).getInt(KEY_BG_ROTATION, 0)
        return ((v % 360) + 360) % 360
    }

    fun setBgRotation(context: Context, degrees: Int) {
        // 只保留 90 的整数倍，非法值就近归位
        val norm = ((degrees % 360) + 360) % 360
        val snapped = ((norm + 45) / 90 * 90) % 360
        prefs(context).edit().putInt(KEY_BG_ROTATION, snapped).apply()
    }

    private const val KEY_BG_SCALE_W = "bg_scale_w"
    private const val KEY_BG_SCALE_H = "bg_scale_h"

    /** 自定义尺寸模式：图片显示宽度占目标区域宽度的百分比（10~200），默认 100 */
    fun getBgScaleW(context: Context): Int =
        prefs(context).getInt(KEY_BG_SCALE_W, 100)

    fun setBgScaleW(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_BG_SCALE_W, percent.coerceIn(10, 200)).apply()
    }

    /** 自定义尺寸模式：图片显示高度占目标区域高度的百分比（10~200），默认 100 */
    fun getBgScaleH(context: Context): Int =
        prefs(context).getInt(KEY_BG_SCALE_H, 100)

    fun setBgScaleH(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_BG_SCALE_H, percent.coerceIn(10, 200)).apply()
    }

    // ---------------- 搜索高亮 ----------------

    private const val KEY_HIGHLIGHT_COLOR = "highlight_color"

    /**
     * 搜索高亮颜色预设：名称 + 颜色资源（语义色，深浅色自适应）。
     * 0 为默认主题链接色；存预设序号而非色值——颜色资源 id 跨版本不稳定。
     */
    val HIGHLIGHT_PRESETS: List<Pair<String, Int>> = listOf(
        "默认（主题色）" to R.color.accent_link,
        "琥珀" to R.color.badge_fav,
        "红色" to R.color.danger,
        "绿色" to R.color.badge_image,
        "蓝色" to R.color.badge_file,
        "橙色" to R.color.badge_video,
        "青色" to R.color.badge_audio
    )

    /** 搜索高亮颜色预设序号，默认 0（主题色） */
    fun getHighlightColorIndex(context: Context): Int =
        prefs(context).getInt(KEY_HIGHLIGHT_COLOR, 0).coerceIn(HIGHLIGHT_PRESETS.indices)

    fun setHighlightColorIndex(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_HIGHLIGHT_COLOR, index.coerceIn(HIGHLIGHT_PRESETS.indices))
            .apply()
    }

    /** 解析当前设置对应的高亮颜色（ARGB） */
    fun resolveHighlightColor(context: Context): Int =
        ContextCompat.getColor(context, HIGHLIGHT_PRESETS[getHighlightColorIndex(context)].second)

    // ---------------- 存储位置 ----------------

    private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"

    /** 备份（数据库 zip）与配置导出的保存文件夹（SAF 目录树）；null = 未设置，每次手动选位置 */
    fun getBackupTreeUri(context: Context): String? =
        prefs(context).getString(KEY_BACKUP_TREE_URI, null)

    fun setBackupTreeUri(context: Context, treeUri: String?) {
        prefs(context).edit().putString(KEY_BACKUP_TREE_URI, treeUri).apply()
    }
}
