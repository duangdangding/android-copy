package com.clipditto.app.util

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import com.clipditto.app.R
import java.io.File

/**
 * 自定义背景：用户选择的图片可覆盖主页（含工具栏）和悬浮列表。
 * 显示模式：拉伸 / 平铺 / 选取图片某一区域；支持背景图透明度。
 * 图片复制到应用私有目录（等比压缩到 2048px 内），不依赖原 Uri 长期有效。
 */
object CustomBackground {

    private const val DIR_NAME = "custom_bg"
    private const val FILE_NAME = "background.img"

    /** 导入图片的最长边（像素），足够覆盖全屏背景且控制内存占用 */
    private const val MAX_DIM = 2048

    /** 悬浮面板圆角半径（与 bg_panel 一致） */
    private const val PANEL_CORNER_DP = 20f

    private fun imageFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 是否已选择背景图片 */
    fun hasImage(context: Context): Boolean =
        imageFile(context).let { it.isFile && it.length() > 0 }

    /**
     * 把用户选择的图片复制到应用私有目录（等比压缩，有透明通道存 PNG 否则 JPEG）。
     * 返回是否成功。
     */
    fun importImage(context: Context, uri: Uri): Boolean = runCatching {
        val resolver = context.contentResolver
        // 先读尺寸计算采样率，避免一次性解码超大图
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIM &&
            bounds.outHeight / (sample * 2) >= MAX_DIM
        ) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return false
        val out = imageFile(context)
        out.parentFile?.mkdirs()
        val hasAlpha = bmp.hasAlpha()
        out.outputStream().use { os ->
            if (hasAlpha) bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            else bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)
        }
        if (bmp != cachedBitmap) bmp.recycle()
        clearCache()
        true
    }.getOrDefault(false)

    /** 清除已选择的背景图片 */
    fun clearImage(context: Context) {
        imageFile(context).delete()
        clearCache()
    }

    // ---------------- 解码缓存 ----------------

    private var cachedMtime: Long = -1
    private var cachedBitmap: Bitmap? = null

    private fun clearCache() {
        cachedMtime = -1
        cachedBitmap = null
    }

    /** 读取背景图片（带缓存）；不存在或解码失败返回 null */
    @Synchronized
    fun sourceBitmap(context: Context): Bitmap? {
        val f = imageFile(context)
        if (!f.isFile || f.length() == 0L) return null
        val mtime = f.lastModified()
        cachedBitmap?.let { if (cachedMtime == mtime && !it.isRecycled) return it }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        cachedBitmap = bmp
        cachedMtime = if (bmp != null) mtime else -1
        return bmp
    }

    /** 按当前设置构建背景 Drawable：选区裁剪 → 旋转 → 显示模式 → 背景图透明度 */
    fun buildDrawable(context: Context): Drawable? = buildDrawable(
        context,
        AppSettings.getBgMode(context),
        AppSettings.getBgRotation(context),
        AppSettings.getBgRegion(context),
        AppSettings.getBgAlpha(context),
        AppSettings.getBgScaleW(context),
        AppSettings.getBgScaleH(context)
    )

    /**
     * 按指定参数构建背景 Drawable（预览页实时调整用，不依赖已保存设置）。
     * 处理管线：原图 → 选取区域裁剪（默认全图） → 旋转 → 显示模式：
     * 拉伸 = 填满；平铺 = 原尺寸重复；自定义尺寸 = 拉伸到目标区域宽高的指定百分比并居中；
     * 最后整体应用背景图透明度。
     */
    fun buildDrawable(
        context: Context,
        mode: Int,
        rotation: Int,
        region: FloatArray,
        alphaPercent: Int,
        scaleW: Int,
        scaleH: Int
    ): Drawable? {
        val orig = sourceBitmap(context) ?: return null
        var bmp = orig
        // 1. 选取区域裁剪（对原图操作，默认全图不裁）
        val l = (region[0] * orig.width).toInt().coerceIn(0, orig.width - 1)
        val t = (region[1] * orig.height).toInt().coerceIn(0, orig.height - 1)
        val rr = (region[2] * orig.width).toInt().coerceIn(l + 1, orig.width)
        val bb = (region[3] * orig.height).toInt().coerceIn(t + 1, orig.height)
        if (l > 0 || t > 0 || rr < orig.width || bb < orig.height) {
            bmp = runCatching {
                Bitmap.createBitmap(orig, l, t, rr - l, bb - t)
            }.getOrNull() ?: orig
        }
        // 2. 旋转（作用于裁剪结果；未裁剪时即原图）
        bmp = rotateBitmap(bmp, rotation)
        // 3. 显示模式 + 透明度
        val alphaValue = alphaPercent.coerceIn(10, 100) * 255 / 100
        // 自定义尺寸：居中按百分比绘制，重写 draw 控制目标矩形
        if (mode == AppSettings.BG_MODE_CUSTOM) {
            return CenterScaleDrawable(
                context.resources, bmp,
                scaleW.coerceIn(10, 200) / 100f,
                scaleH.coerceIn(10, 200) / 100f
            ).apply { alpha = alphaValue }
        }
        return BitmapDrawable(context.resources, bmp).apply {
            if (mode == AppSettings.BG_MODE_TILE) {
                tileModeX = Shader.TileMode.REPEAT
                tileModeY = Shader.TileMode.REPEAT
            } else {
                gravity = Gravity.FILL
            }
            alpha = alphaValue
        }
    }

    /** 按 90 的整数倍旋转图片；0 度直接返回原图 */
    private fun rotateBitmap(bmp: Bitmap, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        if (rot == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrNull() ?: bmp
    }

    /** 自定义尺寸背景：图片拉伸到视图宽高的指定百分比并居中绘制（超出部分裁剪） */
    private class CenterScaleDrawable(
        resources: android.content.res.Resources,
        private val bmp: Bitmap,
        private val scaleW: Float,
        private val scaleH: Float
    ) : BitmapDrawable(resources, bmp) {
        private val dst = android.graphics.RectF()

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.isEmpty || bmp.isRecycled) return
            val w = b.width() * scaleW
            val h = b.height() * scaleH
            dst.set(
                b.left + (b.width() - w) / 2f,
                b.top + (b.height() - h) / 2f,
                b.left + (b.width() + w) / 2f,
                b.top + (b.height() + h) / 2f
            )
            canvas.drawBitmap(bmp, null, dst, paint)
        }
    }

    // ---------------- 主页（含工具栏） ----------------

    /**
     * 应用/还原主页背景。开启时：根布局铺背景图、工具栏与状态栏透明（图片覆盖工具栏）；
     * 关闭时：按当前窗口效果恢复主题默认底色（与 WindowEffect 的视图级行为一致）。
     * 在 Activity 的 onCreate / onResume 中、WindowEffect.apply 之后调用。
     */
    fun applyToActivity(activity: Activity, root: View, toolbar: View) {
        // 自定义背景接管根布局背景，清掉 WindowEffect 记录的待还原背景，避免互相覆盖
        root.setTag(R.id.tag_window_effect_bg, null)
        val enabled = AppSettings.isBgEnabled(activity) && AppSettings.isBgTargetMain(activity)
        val bg = if (enabled) buildDrawable(activity) else null
        if (bg != null) {
            root.background = bg
            toolbar.setBackgroundColor(Color.TRANSPARENT)
            activity.window.statusBarColor = Color.TRANSPARENT
            return
        }
        // 还原：底色与窗口效果保持一致（亚克力/半透明用效果色，默认用主题页面/工具栏色）
        when (AppSettings.getWindowEffect(activity)) {
            AppSettings.WINDOW_EFFECT_ACRYLIC -> {
                val c = activity.getColor(R.color.window_bg_acrylic)
                root.setBackgroundColor(c)
                toolbar.setBackgroundColor(c)
                activity.window.statusBarColor = c
            }
            AppSettings.WINDOW_EFFECT_TRANSLUCENT -> {
                val c = activity.getColor(R.color.window_bg_translucent)
                root.setBackgroundColor(c)
                toolbar.setBackgroundColor(c)
                activity.window.statusBarColor = c
            }
            else -> {
                val ta = activity.theme.obtainStyledAttributes(
                    intArrayOf(R.attr.effectPageBg, R.attr.effectToolbarBg)
                )
                val pageBg = ta.getColor(0, Color.WHITE)
                val toolbarBg = ta.getColor(1, Color.WHITE)
                ta.recycle()
                root.setBackgroundColor(pageBg)
                toolbar.setBackgroundColor(toolbarBg)
                activity.window.statusBarColor = toolbarBg
            }
        }
    }

    // ---------------- 悬浮面板 ----------------

    /**
     * 应用/还原悬浮面板背景。返回 true 表示已应用自定义背景（调用方跳过窗口效果背景）；
     * 返回 false 表示恢复圆角与描边交由 applyPanelEffect 处理。
     * 自定义背景通过圆角轮廓裁剪保持面板圆角外形与阴影。
     */
    fun applyToPanel(context: Context, view: View): Boolean {
        val enabled = AppSettings.isBgEnabled(context) && AppSettings.isBgTargetPanel(context)
        val bg = if (enabled) buildDrawable(context) else null
        if (bg == null) {
            view.clipToOutline = false
            view.outlineProvider = ViewOutlineProvider.BACKGROUND
            return false
        }
        val radius = PANEL_CORNER_DP * context.resources.displayMetrics.density
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
        view.background = bg
        return true
    }
}
