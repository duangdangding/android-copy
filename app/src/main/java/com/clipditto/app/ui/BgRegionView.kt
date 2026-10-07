package com.clipditto.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 背景图片区域选取视图：图片居中适配显示，上面叠加一个可拖动/四角缩放的选框，
 * 选框外区域压暗。选区以图片显示区域为基准换算成归一化坐标（0~1）。
 */
class BgRegionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 要显示的图片；设置后重新布局选框（已布局时立即重建，未布局时等 onSizeChanged） */
    var bitmap: Bitmap? = null
        set(value) {
            field = value
            selectionInited = false
            if (width > 0 && height > 0) {
                computeImageRect()
                initSelection()
            }
            invalidate()
        }

    /** 已保存的归一化选区（左/上/右/下，0~1），用于还原上次选取；null 表示全图 */
    var initialRegion: FloatArray? = null

    /** 图片在视图中的显示区域（fit-center） */
    private val imgRect = RectF()

    /** 当前选框（视图坐标） */
    private val sel = RectF()
    private var selectionInited = false

    private val dimPaint = Paint().apply { color = 0x77000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val handleRadius = 10f * resources.displayMetrics.density
    private val touchSlop = 28f * resources.displayMetrics.density

    // 拖动模式：0 无，1 移动，2 左上，3 右上，4 右下，5 左下
    private var dragMode = 0
    private var lastX = 0f
    private var lastY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
        initSelection()
    }

    /** 图片按 fit-center 计算显示区域 */
    private fun computeImageRect() {
        val bmp = bitmap
        if (bmp == null || width == 0 || height == 0) {
            imgRect.setEmpty()
            return
        }
        val scale = min(
            width.toFloat() / bmp.width,
            height.toFloat() / bmp.height
        )
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        imgRect.set(
            (width - dw) / 2f,
            (height - dh) / 2f,
            (width + dw) / 2f,
            (height + dh) / 2f
        )
    }

    /** 初始化选框：有历史选区按历史还原，否则整张图 */
    private fun initSelection() {
        if (selectionInited || imgRect.isEmpty) return
        val r = initialRegion
        if (r != null && r.size == 4 && r[2] > r[0] && r[3] > r[1]) {
            sel.set(
                imgRect.left + r[0] * imgRect.width(),
                imgRect.top + r[1] * imgRect.height(),
                imgRect.left + r[2] * imgRect.width(),
                imgRect.top + r[3] * imgRect.height()
            )
        } else {
            sel.set(imgRect)
        }
        selectionInited = true
    }

    /** 重置选框为整张图片 */
    fun resetSelection() {
        if (imgRect.isEmpty) return
        sel.set(imgRect)
        invalidate()
    }

    /** 当前选框换算成相对图片的归一化坐标（左/上/右/下，0~1） */
    fun getNormalizedRegion(): FloatArray {
        if (imgRect.isEmpty) return floatArrayOf(0f, 0f, 1f, 1f)
        val l = ((sel.left - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
        val t = ((sel.top - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
        val r = ((sel.right - imgRect.left) / imgRect.width()).coerceIn(0f, 1f)
        val b = ((sel.bottom - imgRect.top) / imgRect.height()).coerceIn(0f, 1f)
        return floatArrayOf(min(l, r), min(t, b), max(l, r), max(t, b))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (imgRect.isEmpty) return
        canvas.drawBitmap(bmp, null, imgRect, null)
        // 选框外压暗
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, dimPaint)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, dimPaint)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, dimPaint)
        // 选框边框 + 四角手柄
        canvas.drawRect(sel, borderPaint)
        drawHandle(canvas, sel.left, sel.top)
        drawHandle(canvas, sel.right, sel.top)
        drawHandle(canvas, sel.right, sel.bottom)
        drawHandle(canvas, sel.left, sel.bottom)
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadius, handlePaint)
        canvas.drawCircle(cx, cy, handleRadius, borderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = hitTest(event.x, event.y)
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return dragMode != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 0) return false
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y
                when (dragMode) {
                    1 -> moveSelection(dx, dy)
                    2 -> resizeSelection(dx, dy, left = true, top = true)
                    3 -> resizeSelection(dx, dy, left = false, top = true)
                    4 -> resizeSelection(dx, dy, left = false, top = false)
                    5 -> resizeSelection(dx, dy, left = true, top = false)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 命中检测：优先四角手柄，其次选框内部（移动） */
    private fun hitTest(x: Float, y: Float): Int {
        if (near(x, y, sel.left, sel.top)) return 2
        if (near(x, y, sel.right, sel.top)) return 3
        if (near(x, y, sel.right, sel.bottom)) return 4
        if (near(x, y, sel.left, sel.bottom)) return 5
        if (sel.contains(x, y)) return 1
        return 0
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float): Boolean =
        abs(x - cx) <= touchSlop && abs(y - cy) <= touchSlop

    /** 选框最小边长（图片显示尺寸的 10%），避免缩到看不见 */
    private fun minSize(): Float = min(imgRect.width(), imgRect.height()) * 0.1f

    private fun moveSelection(dx: Float, dy: Float) {
        val w = sel.width()
        val h = sel.height()
        var nl = sel.left + dx
        var nt = sel.top + dy
        nl = nl.coerceIn(imgRect.left, imgRect.right - w)
        nt = nt.coerceIn(imgRect.top, imgRect.bottom - h)
        sel.set(nl, nt, nl + w, nt + h)
    }

    private fun resizeSelection(dx: Float, dy: Float, left: Boolean, top: Boolean) {
        val min = minSize()
        if (left) {
            sel.left = (sel.left + dx).coerceIn(imgRect.left, sel.right - min)
        } else {
            sel.right = (sel.right + dx).coerceIn(sel.left + min, imgRect.right)
        }
        if (top) {
            sel.top = (sel.top + dy).coerceIn(imgRect.top, sel.bottom - min)
        } else {
            sel.bottom = (sel.bottom + dy).coerceIn(sel.top + min, imgRect.bottom)
        }
    }
}
