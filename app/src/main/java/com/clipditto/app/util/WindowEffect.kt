package com.clipditto.app.util

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.widget.ActionMenuView
import com.clipditto.app.R
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import java.util.WeakHashMap

/**
 * 全局窗口效果（默认/亚克力/半透明），两条路径：
 * 1. 页面创建：Activity 在 super.onCreate 前 setTheme(themeRes(...))，窗口/工具栏/页面背景
 *    全部由主题决定，第一帧就是正确效果，打开页面不闪；
 * 2. 运行中切换（设置页选择 / 返回后台页面时设置已变）：apply 检测到不一致，
 *    对已创建的窗口原地换背景与工具栏样式（单帧直切，不 recreate、无重建黑闪）。
 * 悬浮面板不走主题（它是悬浮窗），由 ClipboardService 自行处理。
 */
object WindowEffect {

    /** 模糊半径（dp）：亚克力效果的底层模糊强度 */
    private const val BLUR_RADIUS_DP = 20

    /** 各 Activity 最近一次应用的效果，用于检测"运行中切换了效果" */
    private val appliedEffect = WeakHashMap<Activity, Int>()

    /** 哨兵：记录"根布局原本没有背景"（tag 里 null 与"未记录"无法区分） */
    private val NULL_BG = ColorDrawable(Color.TRANSPARENT)

    /** 按当前设置返回应使用的主题（在 super.onCreate 前 setTheme） */
    fun themeRes(context: Context): Int = when (AppSettings.getWindowEffect(context)) {
        AppSettings.WINDOW_EFFECT_ACRYLIC -> R.style.Theme_ClipDitto_Effect_Acrylic
        AppSettings.WINDOW_EFFECT_TRANSLUCENT -> R.style.Theme_ClipDitto_Effect_Translucent
        else -> R.style.Theme_ClipDitto
    }

    /**
     * 在 onCreate / onResume 调用：首次仅记录（主题已处理一切）；
     * 若与记录不一致（运行中切换过窗口效果），对已创建窗口原地应用新效果。
     * 页面此时尚不可见，含壁纸标志在内的完整切换不会产生可见闪烁。
     */
    fun apply(activity: Activity) {
        val effect = AppSettings.getWindowEffect(activity)
        val last = appliedEffect.put(activity, effect)
        if (last != null && last != effect) applyNow(activity, effect, touchWindow = true)
    }

    /**
     * 页面正显示在前台时切换效果（设置页选择）：只改视图层（工具栏 + 根布局背景色），
     * 窗口背景/模糊/壁纸标志一律不动——任何窗口级改动都会触发重合成，看起来就像页面闪了一下。
     * 代价是本页的窗口级效果（透下层、模糊）要等下次页面创建走主题时才完整。
     */
    fun applyVisible(activity: Activity) {
        val effect = AppSettings.getWindowEffect(activity)
        val last = appliedEffect.put(activity, effect)
        if (last != null && last != effect) applyNow(activity, effect, touchWindow = false)
    }

    // ---------------- 运行中切换：原地应用（不 recreate） ----------------

    private fun applyNow(activity: Activity, effect: Int, touchWindow: Boolean) {
        val window = activity.window
        when (effect) {
            AppSettings.WINDOW_EFFECT_ACRYLIC -> {
                styleToolbar(activity, R.color.window_bg_acrylic)
                if (touchWindow) {
                    window.setBackgroundDrawable(
                        ColorDrawable(activity.getColor(R.color.window_bg_acrylic))
                    )
                    makeContentRootTranslucent(activity)
                    // 系统模糊仅 Android 12+；低版本保留半透明底，自动降级为半透明观感
                    if (Build.VERSION.SDK_INT >= 31) {
                        val radius = blurRadiusPx(activity)
                        val attrs = window.attributes
                        attrs.flags = attrs.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        attrs.blurBehindRadius = radius
                        window.attributes = attrs
                        runCatching { window.setBackgroundBlurRadius(radius) }
                    }
                } else {
                    tintContentRoot(activity, R.color.window_bg_acrylic)
                }
            }
            AppSettings.WINDOW_EFFECT_TRANSLUCENT -> {
                styleToolbar(activity, R.color.window_bg_translucent)
                if (touchWindow) {
                    window.setBackgroundDrawable(
                        ColorDrawable(activity.getColor(R.color.window_bg_translucent))
                    )
                    makeContentRootTranslucent(activity)
                    clearBlur(window)
                } else {
                    tintContentRoot(activity, R.color.window_bg_translucent)
                }
            }
            else -> {
                restoreToolbar(activity)
                restoreContentRoot(activity)
                if (touchWindow) {
                    val tv = TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
                    window.setBackgroundDrawable(ColorDrawable(activity.getColor(tv.resourceId)))
                    clearBlur(window)
                }
            }
        }
    }

    /** 工具栏换半透明底色，标题/返回箭头/溢出图标/文字菜单项统一反转为正文色 */
    private fun styleToolbar(activity: Activity, bgColorRes: Int) {
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar) ?: return
        val bg = activity.getColor(bgColorRes)
        toolbar.setBackgroundColor(bg)
        // 状态栏底色跟随工具栏，避免切换后顶部一条颜色不一致
        activity.window.statusBarColor = bg
        val fg = activity.getColor(R.color.text_primary)
        toolbar.setTitleTextColor(fg)
        toolbar.setNavigationIconTint(fg)
        runCatching { toolbar.overflowIcon?.setTint(fg) }
        tintActionMenuItems(toolbar, fg)
    }

    /** 恢复默认：工具栏回主题主色底（兼容动态取色），前景回 colorOnPrimary */
    private fun restoreToolbar(activity: Activity) {
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar) ?: return
        val bg = MaterialColors.getColor(
            activity, com.google.android.material.R.attr.colorPrimary,
            activity.getColor(R.color.md_primary)
        )
        val fg = MaterialColors.getColor(
            activity, com.google.android.material.R.attr.colorOnPrimary,
            activity.getColor(R.color.md_on_primary)
        )
        toolbar.setBackgroundColor(bg)
        activity.window.statusBarColor = bg
        toolbar.setTitleTextColor(fg)
        toolbar.setNavigationIconTint(fg)
        runCatching { toolbar.overflowIcon?.setTint(fg) }
        tintActionMenuItems(toolbar, fg)
    }

    /** 文字型菜单项（如设备页右上角「设置」）颜色来自主题，运行中切换时需手动改 */
    private fun tintActionMenuItems(toolbar: MaterialToolbar, color: Int) {
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView) {
                for (j in 0 until child.childCount) {
                    (child.getChildAt(j) as? android.widget.TextView)?.setTextColor(color)
                }
            }
        }
    }

    /**
     * 可见页面切换：不动窗口，把根布局背景直接染成效果色（视图级改动，单帧生效无重合成）。
     * 窗口背景仍是创建时的不透明主题底色，所以这只是"着色预览"，完整透底效果待下次创建生效。
     */
    private fun tintContentRoot(activity: Activity, colorRes: Int) {
        val root = contentRoot(activity) ?: return
        if (root.getTag(R.id.tag_window_effect_bg) == null) {
            root.setTag(R.id.tag_window_effect_bg, root.background ?: NULL_BG)
        }
        root.setBackgroundColor(activity.getColor(colorRes))
    }

    /** 根布局背景（如主页 page_bg）改透明，让半透明窗口背景透出；原背景存 tag 供还原 */
    private fun makeContentRootTranslucent(activity: Activity) {
        val root = contentRoot(activity) ?: return
        if (root.getTag(R.id.tag_window_effect_bg) == null) {
            root.setTag(R.id.tag_window_effect_bg, root.background ?: NULL_BG)
        }
        root.setBackgroundColor(Color.TRANSPARENT)
    }

    /** 还原根布局原背景（仅处理被 [makeContentRootTranslucent] 改过的） */
    private fun restoreContentRoot(activity: Activity) {
        val root = contentRoot(activity) ?: return
        when (val saved = root.getTag(R.id.tag_window_effect_bg)) {
            NULL_BG -> root.background = null
            is Drawable -> root.background = saved
        }
        root.setTag(R.id.tag_window_effect_bg, null)
    }

    private fun contentRoot(activity: Activity): android.view.View? =
        activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)

    private fun blurRadiusPx(activity: Activity): Int =
        (BLUR_RADIUS_DP * activity.resources.displayMetrics.density).toInt().coerceIn(1, 200)

    /** 关闭窗口模糊（切回默认/半透明时调用） */
    private fun clearBlur(window: Window) {
        if (Build.VERSION.SDK_INT >= 31) {
            val attrs = window.attributes
            attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            attrs.blurBehindRadius = 0
            window.attributes = attrs
            runCatching { window.setBackgroundBlurRadius(0) }
        }
    }
}
