package com.clipditto.app.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipType
import com.clipditto.app.util.MediaFiles
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onClick: (ClipItem) -> Unit,
    private val onLongClick: (ClipItem) -> Unit,
    /** 点击「打开」按钮打开网址后的回调（悬浮面板用于收起列表） */
    private val onOpenUrl: (() -> Unit)? = null
) : ListAdapter<ClipItem, HistoryAdapter.VH>(DIFF) {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    /** 包名 -> 应用名 缓存 */
    private val labelCache = HashMap<String, String>()

    /** 当前搜索词：非空时列表内容里命中的字符加粗标色 */
    var highlightQuery: String = ""

    companion object {
        /** 记录 id 定位条目，数据类 equals 判断内容变化（收藏/时间戳等） */
        private val DIFF = object : DiffUtil.ItemCallback<ClipItem>() {
            override fun areItemsTheSame(oldItem: ClipItem, newItem: ClipItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ClipItem, newItem: ClipItem) =
                oldItem == newItem
        }
    }

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

    /**
     * 搜索命中高亮：与 FuzzySearch 规则一致——
     * 包含匹配标出所有出现片段；否则按子序列贪心命中逐字标出。
     */
    private fun highlightMatches(text: String, view: View): CharSequence {
        val q = highlightQuery.trim().lowercase()
        if (q.isEmpty()) return text
        val lower = text.lowercase()
        val hit = BooleanArray(text.length)
        var any = false
        var from = lower.indexOf(q)
        if (from >= 0) {
            // 包含匹配：标出所有出现位置
            while (from >= 0) {
                for (k in from until from + q.length) hit[k] = true
                any = true
                from = lower.indexOf(q, from + q.length)
            }
        } else {
            // 子序列匹配：贪心顺序标出命中字符
            var qi = 0
            for (i in lower.indices) {
                if (qi < q.length && lower[i] == q[qi]) {
                    hit[i] = true
                    qi++
                    any = true
                }
            }
        }
        if (!any) return text
        val spannable = SpannableString(text)
        val color = ContextCompat.getColor(view.context, R.color.accent_link)
        var i = 0
        while (i < hit.size) {
            if (!hit[i]) { i++; continue }
            var j = i
            while (j < hit.size && hit[j]) j++
            spannable.setSpan(ForegroundColorSpan(color), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = j
        }
        return spannable
    }

    /** 差异刷新入口（内部走 DiffUtil，增删/变更有动画，不再全表闪动） */
    fun submit(list: List<ClipItem>) = submitList(list)

    /** 供滑动操作按位置取条目 */
    fun itemAt(position: Int): ClipItem = getItem(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        // 收藏的记录：类型徽章变琥珀色带 ★，整条卡片浅金底色；
        // 普通记录：徽章按类型着色（文字紫/图片绿/文件蓝/视频橙/音频青）
        holder.type.text =
            if (item.favorite) "★${ClipType.label(item.type)}" else ClipType.label(item.type)
        if (item.favorite) {
            holder.type.setBackgroundResource(R.drawable.bg_badge_fav)
            holder.type.backgroundTintList = null
        } else {
            holder.type.setBackgroundResource(R.drawable.bg_badge)
            val badgeColor = when (item.type) {
                ClipType.IMAGE -> R.color.badge_image
                ClipType.FILE -> R.color.badge_file
                ClipType.VIDEO -> R.color.badge_video
                ClipType.AUDIO -> R.color.badge_audio
                else -> R.color.badge_text
            }
            holder.type.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(holder.itemView.context, badgeColor)
            )
        }
        holder.itemView.setBackgroundResource(
            if (item.favorite) R.drawable.bg_item_fav_ripple else R.drawable.bg_item_ripple
        )
        holder.content.text = highlightMatches(item.text ?: "(无预览)", holder.itemView)
        holder.time.text = timeFormat.format(Date(item.timestamp))
        holder.source.text = "来自 ${sourceLabel(holder.itemView, item.sourceApp)}"

        when (item.type) {
            ClipType.IMAGE -> {
                val bmp = item.filePath?.let { path ->
                    MediaFiles.openInput(holder.itemView.context, path)
                        ?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) {
                    holder.thumb.setImageBitmap(bmp)
                    holder.thumb.visibility = View.VISIBLE
                } else holder.thumb.visibility = View.GONE
            }
            ClipType.VIDEO -> {
                val bmp = item.filePath?.let { path ->
                    runCatching {
                        val r = MediaMetadataRetriever()
                        // filePath 可能是本地路径或 content:// 文档 URI（同步下载的）
                        if (MediaFiles.isContentUri(path)) {
                            r.setDataSource(holder.itemView.context, android.net.Uri.parse(path))
                        } else {
                            r.setDataSource(path)
                        }
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
