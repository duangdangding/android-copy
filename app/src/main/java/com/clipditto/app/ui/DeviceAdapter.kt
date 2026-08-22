package com.clipditto.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.sync.LanDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 局域网设备列表：显示在线状态、共享状态、配对状态、最近同步时间，支持多选。
 */
class DeviceAdapter(
    private val onClick: (LanDevice) -> Unit,
    private val onToggleSelect: (LanDevice, Boolean) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items: List<LanDevice> = emptyList()
    private var selected: Set<String> = emptySet()
    private var syncing: Set<String> = emptySet()

    fun submit(list: List<LanDevice>, selected: Set<String>, syncing: Set<String>) {
        items = list
        this.selected = selected
        this.syncing = syncing
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.avatar.text = d.displayName.firstOrNull()?.toString() ?: "?"
        holder.name.text =
            if (d.model.isNullOrBlank()) d.displayName else "${d.displayName}（${d.model}）"
        holder.dot.setBackgroundResource(
            if (d.online) R.drawable.bg_status_dot_online else R.drawable.bg_status_dot_offline
        )
        holder.status.setTextColor(
            if (d.online) 0xFF2E7D32.toInt() else 0xFF999999.toInt()
        )
        holder.status.text = buildString {
            append(if (d.online) "在线" else "离线")
            d.host?.takeIf { d.online }?.let { append(" · $it") }
            append(if (d.sharing) " · 共享中" else " · 未共享")
            append(if (d.paired) " · 已配对" else " · 未配对")
        }
        val syncText = when {
            d.lastSync > 0 -> "上次同步：${fmt.format(Date(d.lastSync))}"
            d.paired -> "尚未同步过"
            else -> ""
        }
        holder.sync.text = syncText
        holder.sync.visibility = if (syncText.isEmpty()) View.GONE else View.VISIBLE
        val isSelected = d.deviceId in selected
        holder.itemView.setBackgroundResource(
            if (isSelected) R.drawable.bg_item_selected else R.drawable.bg_item
        )
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = isSelected
        holder.check.setOnCheckedChangeListener { _, checked ->
            onToggleSelect(d, checked)
        }
        holder.progress.visibility =
            if (d.deviceId in syncing) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(d) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvDeviceAvatar)
        val check: CheckBox = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.tvName)
        val dot: View = v.findViewById(R.id.viewStatusDot)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val sync: TextView = v.findViewById(R.id.tvLastSync)
        val progress: ProgressBar = v.findViewById(R.id.progress)
    }

    companion object {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
