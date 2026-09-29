package com.clipditto.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.data.ClipItem

/**
 * 列表滑动操作：右滑收藏/取消收藏（琥珀色），左滑删除（红色）。
 * 滑动过程中绘制底色与中文标签；松手后条目先弹回原位，
 * 由回调决定后续动作（收藏走数据流刷新，删除可先弹确认框）。
 */
object SwipeActions {

    fun attach(
        recycler: RecyclerView,
        adapter: HistoryAdapter,
        onFav: (ClipItem) -> Unit,
        onDelete: (ClipItem) -> Unit
    ) {
        val density = recycler.resources.displayMetrics.density
        val favColor = Color.parseColor("#F59E0B")
        val delColor = Color.parseColor("#E53935")
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * recycler.resources.displayMetrics.scaledDensity
            isFakeBoldText = true
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        lateinit var helper: ItemTouchHelper

        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val item = adapter.itemAt(pos)
                // 实测该环境下 ItemTouchHelper 的滑出收尾动画不会结束（clearView 不回调），
                // 视图会卡在滑出位置。这里重新 attach 强制终结滑动状态、复位视图后再执行动作。
                val view = holder.itemView
                view.post {
                    helper.attachToRecyclerView(null)
                    view.translationX = 0f
                    view.alpha = 1f
                    helper.attachToRecyclerView(recycler)
                    recycler.post { adapter.notifyItemChanged(pos) }
                    if (direction == ItemTouchHelper.RIGHT) onFav(item) else onDelete(item)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val v = viewHolder.itemView
                    val label: String
                    if (dX > 0) {
                        // 右滑：收藏/取消收藏，标签靠左
                        bgPaint.color = favColor
                        val pos = viewHolder.bindingAdapterPosition
                        label = if (pos != RecyclerView.NO_POSITION &&
                            adapter.itemAt(pos).favorite
                        ) "取消收藏" else "★ 收藏"
                        c.drawRect(
                            v.left.toFloat(), v.top.toFloat(),
                            v.left + dX, v.bottom.toFloat(), bgPaint
                        )
                        val y = v.top + v.height / 2f -
                            (textPaint.descent() + textPaint.ascent()) / 2f
                        c.drawText(label, v.left + 20f * density, y, textPaint)
                    } else {
                        // 左滑：删除，标签靠右
                        bgPaint.color = delColor
                        label = "删除"
                        c.drawRect(
                            v.right + dX, v.top.toFloat(),
                            v.right.toFloat(), v.bottom.toFloat(), bgPaint
                        )
                        val y = v.top + v.height / 2f -
                            (textPaint.descent() + textPaint.ascent()) / 2f
                        val w = textPaint.measureText(label)
                        c.drawText(label, v.right - 20f * density - w, y, textPaint)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recycler)
    }
}
