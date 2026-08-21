package com.clipditto.app.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (ClipItem) -> Unit,
    private val onLongClick: (ClipItem) -> Unit,
    /** 点击「打开」按钮打开网址后的回调（悬浮面板用于收起列表） */
    private val onOpenUrl: (() -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<ClipItem>()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    /** 包名 -> 应用名 缓存 */
    private val labelCache = HashMap<String, String>()

    private fun sourceLabel(view: View, pkg: String?): String {
        if (pkg.isNullOrBlank()) return "未知来源"
        return labelCache.getOrPut(pkg) {
            runCatching {
                val pm = view.context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrElse {
                // 应用已卸载：只显示包名最后一段，避免一长串包名撑破布局
                pkg.substringAfterLast('.')
            }
        }
    }

    fun submit(list: List<ClipItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        // 收藏的记录：类型徽章变橙色带 ★，整条卡片浅金底色
        holder.type.text =
            if (item.favorite) "★${ClipType.label(item.type)}" else ClipType.label(item.type)
        holder.type.setBackgroundResource(
            if (item.favorite) R.drawable.bg_badge_fav else R.drawable.bg_ball
        )
        holder.itemView.setBackgroundResource(
            if (item.favorite) R.drawable.bg_item_fav else R.drawable.bg_item
        )
        holder.content.text = item.text ?: "(无预览)"
        holder.time.text = timeFormat.format(Date(item.timestamp))
        holder.source.text = "来自 ${sourceLabel(holder.itemView, item.sourceApp)}"

        when (item.type) {
            ClipType.IMAGE -> {
                val bmp = item.filePath?.let { BitmapFactory.decodeFile(it) }
                if (bmp != null) {
                    holder.thumb.setImageBitmap(bmp)
                    holder.thumb.visibility = View.VISIBLE
                } else holder.thumb.visibility = View.GONE
            }
            ClipType.VIDEO -> {
                val bmp = item.filePath?.let { path ->
                    runCatching {
                        val r = MediaMetadataRetriever()
                        r.setDataSource(path)
                        val frame = r.frameAtTime
                        r.release()
                        frame
                    }.getOrNull()
                }
                if (bmp != null) {
                    holder.thumb.setImageBitmap(bmp)
                    holder.thumb.visibility = View.VISIBLE
                } else holder.thumb.visibility = View.GONE
            }
            else -> holder.thumb.visibility = View.GONE
        }

        // 识别顺序：文字口令特征 > 来源包名反推 > 网址（逻辑与详情弹窗共用）
        ItemActionButtons.bind(holder.openUrl, holder.openAlt, holder.openBrowser, item, onOpenUrl)

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val type: TextView = view.findViewById(R.id.tvType)
        val content: TextView = view.findViewById(R.id.tvContent)
        val time: TextView = view.findViewById(R.id.tvTime)
        val source: TextView = view.findViewById(R.id.tvSource)
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val openUrl: Button = view.findViewById(R.id.btnOpenUrl)
        val openAlt: Button = view.findViewById(R.id.btnOpenAlt)
        val openBrowser: Button = view.findViewById(R.id.btnOpenBrowser)
    }
}
