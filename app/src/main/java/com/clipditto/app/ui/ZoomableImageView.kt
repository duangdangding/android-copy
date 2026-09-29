package com.clipditto.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 可交互的图片预览：双指缩放、单指拖动平移、双击放大 / 还原。
 * 以 fitCenter 为基准（最小缩放），最大放大 5 倍。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val baseMatrix = Matrix()   // fitCenter 基准
    private val extraMatrix = Matrix()  // 用户手势累积的缩放/平移
    private val workMatrix = Matrix()

    private var curScale = 1f           // 相对基准的当前倍数
    private val maxScale = 5f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val target = (curScale * d.scaleFactor).coerceIn(1f, maxScale)
                val f = target / curScale
                if (f != 1f) {
                    extraMatrix.postScale(f, f, d.focusX, d.focusY)
                    curScale = target
                    applyMatrix()
                }
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (curScale > 1f) {
                    resetZoom()
                } else {
                    val target = 2.5f.coerceAtMost(maxScale)
                    extraMatrix.postScale(target, target, e.x, e.y)
                    curScale = target
                    applyMatrix()
                }
                return true
            }
            override fun onDown(e: MotionEvent): Boolean = true
        })

    private var lastX = 0f
    private var lastY = 0f

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
    }

    fun resetZoom() {
        extraMatrix.reset()
        curScale = 1f
        computeBase()
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeBase()
        applyMatrix()
    }

    /** fitCenter 基准矩阵：图片完整居中显示 */
    private fun computeBase() {
        baseMatrix.reset()
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return
        val s = minOf(width / dw, height / dh)
        baseMatrix.setScale(s, s)
        baseMatrix.postTranslate((width - dw * s) / 2f, (height - dh * s) / 2f)
    }

    private fun applyMatrix() {
        workMatrix.set(baseMatrix)
        workMatrix.postConcat(extraMatrix)
        imageMatrix = workMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // 缩放状态下拦截父容器（如弹窗滚动）抢手势
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                // 放大后的单指平移；双指交给 ScaleGestureDetector
                if (event.pointerCount == 1 && curScale > 1f && !scaleDetector.isInProgress) {
                    extraMatrix.postTranslate(event.x - lastX, event.y - lastY)
                    applyMatrix()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指抬起一指后重置平移基准点，避免跳动
                lastX = event.x
                lastY = event.y
            }
        }
        return true
    }
}
