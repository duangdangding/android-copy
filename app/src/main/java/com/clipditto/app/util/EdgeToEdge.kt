package com.clipditto.app.util

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 沉浸式状态栏：内容延伸到状态栏与导航栏区域，
 * Toolbar 加顶部内边距并增高（紫色背景延伸进状态栏，图标白色），
 * 根布局加底部内边距，避免内容被手势导航条遮挡。
 * 需配合主题中 android:statusBarColor 设为透明使用。
 */
object EdgeToEdge {

    fun apply(activity: Activity, toolbar: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // 状态栏图标亮暗跟随主题主色亮度：深色主色（浅色模式）用白图标，浅色主色（深色模式）用黑图标
        runCatching {
            val ta = toolbar.context.obtainStyledAttributes(
                intArrayOf(com.google.android.material.R.attr.colorPrimary)
            )
            val primary = ta.getColor(0, 0)
            ta.recycle()
            WindowCompat.getInsetsController(activity.window, toolbar)
                .isAppearanceLightStatusBars = Color.luminance(primary) > 0.5
        }
        val root = toolbar.parent as View
        val toolbarTop = toolbar.paddingTop
        val toolbarBaseHeight = toolbar.layoutParams.height   // 布局里固定 40dp
        val rootBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.updatePadding(top = toolbarTop + bars.top)
            if (toolbarBaseHeight > 0) {
                // 固定高度的 Toolbar：整体加高，保证标题/图标可用区域不变
                toolbar.layoutParams = toolbar.layoutParams.apply {
                    height = toolbarBaseHeight + bars.top
                }
            }
            root.updatePadding(bottom = rootBottom + bars.bottom)
            insets
        }
    }
}
