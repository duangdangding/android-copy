package com.clipditto.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.sync.LanSettings
import com.clipditto.app.sync.LanSyncManager
import com.clipditto.app.sync.relay.RelaySyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 「同步设置」页：局域网同步的全部开关与接收配置 + 云端中继入口。
 * 从「设备同步」页右上角「设置」进入；设备列表页只保留设备与诊断信息。
 */
class LanSyncSettingsActivity : AppCompatActivity() {

    /** SAF 目录选择器：同步下载文件的存储目录，选定后持久化读写权限 */
    private val pickSyncDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                LanSyncManager.settings().syncDirUri = uri.toString()
                Toast.makeText(this, "已设置文件存储路径", Toast.LENGTH_SHORT).show()
            }
            refreshSyncSettingsUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lan_sync_settings)

        LanSyncManager.init(this)
        RelaySyncManager.init(this)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        bindSwitches()

        findViewById<TextView>(R.id.tvPort).setOnClickListener { showPortDialog() }
        findViewById<TextView>(R.id.tvMyName).setOnClickListener { showRenameDialog() }
        findViewById<TextView>(R.id.tvBlacklist).setOnClickListener { showBlacklistDialog() }
        findViewById<TextView>(R.id.tvSyncDir).setOnClickListener { showSyncDirDialog() }
        findViewById<TextView>(R.id.tvSyncMaxSize).setOnClickListener { showSyncMaxSizeDialog() }
        findViewById<TextView>(R.id.tvSyncTypes).setOnClickListener { showSyncTypesDialog() }
        findViewById<TextView>(R.id.tvSyncInterval).setOnClickListener { showSyncIntervalDialog() }
        // 云端中继入口：点击进中继设置页，文字实时显示连接状态
        findViewById<TextView>(R.id.tvRelay).setOnClickListener {
            startActivity(Intent(this, RelaySettingsActivity::class.java))
        }
        lifecycleScope.launch {
            RelaySyncManager.status.collectLatest { s ->
                findViewById<TextView>(R.id.tvRelay).text = "云端中继：$s（点击设置）"
            }
        }

        refreshSwitches()
        refreshDeviceRows()
        refreshSyncSettingsUi()
    }

    override fun onResume() {
        super.onResume()
        refreshSwitches()
        refreshDeviceRows()
        refreshSyncSettingsUi()
    }

    // ---------------- 开关 ----------------

    private fun bindSwitches() {
        findViewById<SwitchCompat>(R.id.swDiscoverable).setOnCheckedChangeListener { _, on ->
            LanSyncManager.settings().discoverable = on
            LanSyncManager.applyState()
        }
        findViewById<SwitchCompat>(R.id.swSharing).setOnCheckedChangeListener { _, on ->
            LanSyncManager.settings().sharing = on
            LanSyncManager.applyState()
            refreshPairingCode()
        }
        findViewById<SwitchCompat>(R.id.swAutoSync).setOnCheckedChangeListener { _, on ->
            LanSyncManager.settings().autoSync = on
            LanSyncManager.applyState()
        }
        findViewById<SwitchCompat>(R.id.swAutoAccept).setOnCheckedChangeListener { _, on ->
            LanSyncManager.settings().autoAcceptPair = on
        }
        findViewById<SwitchCompat>(R.id.swEncryption).setOnCheckedChangeListener { _, on ->
            LanSyncManager.settings().syncEncryption = on
        }
    }

    private fun refreshSwitches() {
        val s = LanSyncManager.settings()
        findViewById<SwitchCompat>(R.id.swDiscoverable).isChecked = s.discoverable
        findViewById<SwitchCompat>(R.id.swSharing).isChecked = s.sharing
        findViewById<SwitchCompat>(R.id.swAutoSync).isChecked = s.autoSync
        findViewById<SwitchCompat>(R.id.swAutoAccept).isChecked = s.autoAcceptPair
        findViewById<SwitchCompat>(R.id.swEncryption).isChecked = s.syncEncryption
        refreshPairingCode()
    }

    /** 共享开启时显示本机配对码，供其他设备输入 */
    private fun refreshPairingCode() {
        val s = LanSyncManager.settings()
        val tv = findViewById<TextView>(R.id.tvPairingCode)
        if (s.sharing) {
            tv.visibility = View.VISIBLE
            tv.text = "本机配对码：${s.pairingToken}（其他设备配对时需输入）"
            tv.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("重置配对码")
                    .setMessage("重置后，已配对的设备需要用新配对码重新配对。确定？")
                    .setPositiveButton("重置") { _, _ ->
                        s.regenerateToken()
                        refreshPairingCode()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        } else {
            tv.visibility = View.GONE
        }
    }

    /** 刷新本机名称 / 端口 / 黑名单三行 */
    private fun refreshDeviceRows() {
        val s = LanSyncManager.settings()
        findViewById<TextView>(R.id.tvPort).text =
            "服务端口：${s.serverPort}（点击修改，需两台设备保持一致）"
        findViewById<TextView>(R.id.tvMyName).text =
            "本机名称：${s.deviceName}（点击修改，对方列表里显示此名）"
        val blocked = LanSyncManager.getBlockedList()
        findViewById<TextView>(R.id.tvBlacklist).text =
            "黑名单：${blocked.size} 台（点击管理）"
    }

    // ---------------- 黑名单 ----------------

    private fun showBlacklistDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_blacklist, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvBlacklistTitle)
        val recycler = view.findViewById<RecyclerView>(R.id.blacklistRecycler)
        val tvEmpty = view.findViewById<TextView>(R.id.tvBlacklistEmpty)
        val dialog = AlertDialog.Builder(this).setView(view).create()

        lateinit var blockedAdapter: BlockedAdapter
        fun refreshBlocked() {
            val entries = LanSyncManager.getBlockedList().entries.toList()
            blockedAdapter.submit(entries)
            tvTitle.text = "黑名单（${entries.size} 台）"
            tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }

        blockedAdapter = BlockedAdapter { entry ->
            val (id, info) = entry
            AlertDialog.Builder(this)
                .setMessage("把「${info.name.ifBlank { "未知设备" }}」移出黑名单？\n移出后双方可重新扫描和配对")
                .setPositiveButton("移出") { _, _ ->
                    LanSyncManager.unblockDevice(id)
                    refreshDeviceRows()
                    refreshBlocked()
                    Toast.makeText(this, "已移出黑名单", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = blockedAdapter
        refreshBlocked()

        view.findViewById<Button>(R.id.btnBlacklistClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
        // 黑名单很多时限制列表高度，弹窗内部滚动，不超出屏幕
        recycler.post {
            val max = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            if (recycler.height > max) recycler.layoutParams.height = max
        }
    }

    // ---------------- 本机名称 ----------------

    private fun showRenameDialog() {
        val s = LanSyncManager.settings()
        val input = EditText(this).apply {
            setText(s.deviceName)
            setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME_LENGTH))
            hint = "最多 $MAX_NAME_LENGTH 个字"
        }
        AlertDialog.Builder(this)
            .setTitle("本机名称")
            .setMessage("这个名字会显示在其他设备的列表里（最多 $MAX_NAME_LENGTH 个字），修改后对方列表会自动更新")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (name != s.deviceName) {
                    s.deviceName = name
                    LanSyncManager.refreshName()
                }
                refreshDeviceRows()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 端口设置 ----------------

    private fun showPortDialog() {
        val s = LanSyncManager.settings()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(s.serverPort.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("服务端口")
            .setMessage("本机 HTTP 服务监听的端口（1024~65535），默认 8765。修改后服务会立即重启，对端通过扫描自动获取新端口，无需手动填写。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val port = input.text.toString().toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    Toast.makeText(this, "端口需在 1024~65535 之间", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                s.serverPort = port
                LanSyncManager.restartServer()
                refreshDeviceRows()
                Toast.makeText(this, "端口已改为 $port，服务已重启", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认 8765") { _, _ ->
                s.serverPort = 8765
                LanSyncManager.restartServer()
                refreshDeviceRows()
            }
            .show()
    }

    // ---------------- 同步接收设置 ----------------

    /** 刷新四个同步设置行的显示 */
    private fun refreshSyncSettingsUi() {
        val s = LanSyncManager.settings()
        val dir = s.syncDirUri
        findViewById<TextView>(R.id.tvSyncDir).text =
            if (dir == null) "文件存储路径：默认（系统 Download/ClipDitto，点击更换）"
            else "文件存储路径：${displayDirName(dir)}（点击更换/恢复默认）"
        findViewById<TextView>(R.id.tvSyncMaxSize).text =
            "同步文件大小上限：${s.syncMaxSizeMb}M（点击修改）"
        val labels = s.syncTypeGroups.mapNotNull { LanSettings.GROUP_LABELS[it] }
        findViewById<TextView>(R.id.tvSyncTypes).text =
            "同步类型：${if (labels.isEmpty()) "（未勾选任何类型）" else labels.joinToString("、")}（点击修改）"
        findViewById<TextView>(R.id.tvSyncInterval).text =
            "自动同步间隔：${formatInterval(s.autoSyncIntervalSec)}（点击修改）"
    }

    /** 间隔显示：60 秒以上显示为分钟，否则秒 */
    private fun formatInterval(sec: Int): String =
        if (sec % 60 == 0) "${sec / 60} 分钟" else "$sec 秒"

    /** 自动同步间隔：预设档位 + 自定义秒数 */
    private fun showSyncIntervalDialog() {
        val presets = intArrayOf(10, 30, 60, 300, 600)
        val labels = presets.map { formatInterval(it) }.toTypedArray() + "自定义…"
        AlertDialog.Builder(this)
            .setTitle("自动同步间隔")
            .setItems(labels) { _, which ->
                if (which < presets.size) {
                    LanSyncManager.settings().autoSyncIntervalSec = presets[which]
                    refreshSyncSettingsUi()
                } else {
                    showCustomIntervalDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomIntervalDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "间隔（秒，最小 ${LanSettings.MIN_AUTO_SYNC_INTERVAL_SEC}）"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义同步间隔")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n < LanSettings.MIN_AUTO_SYNC_INTERVAL_SEC) {
                    Toast.makeText(
                        this, "请输入不小于 ${LanSettings.MIN_AUTO_SYNC_INTERVAL_SEC} 的秒数",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    LanSyncManager.settings().autoSyncIntervalSec = n
                    refreshSyncSettingsUi()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把 SAF 树 URI 转为可读目录名，如 "primary:Download/clip" → "Download/clip" */
    private fun displayDirName(treeUri: String): String =
        runCatching {
            Uri.decode(Uri.parse(treeUri).lastPathSegment ?: treeUri).substringAfter(':')
        }.getOrDefault(treeUri)

    private fun showSyncDirDialog() {
        val hasDir = LanSyncManager.settings().syncDirUri != null
        val items = if (hasDir) arrayOf("重新选择目录", "恢复默认（系统 Download/ClipDitto）")
        else arrayOf("选择目录")
        // AlertDialog 的 setItems 会占掉 message 区域，说明文字放进标题
        AlertDialog.Builder(this)
            .setTitle("文件存储路径（默认存到系统 Download/ClipDitto）")
            .setItems(items) { _, which ->
                when {
                    which == 0 -> pickSyncDir.launch(null)
                    which == 1 -> {
                        LanSyncManager.settings().syncDirUri = null
                        refreshSyncSettingsUi()
                        Toast.makeText(
                            this, "已恢复默认：系统 Download/ClipDitto", Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSyncMaxSizeDialog() {
        val presets = intArrayOf(5, 10, 20, 50, 100, 200)
        val labels = presets.map { "${it}M" }.toTypedArray() + "自定义…"
        AlertDialog.Builder(this)
            .setTitle("同步文件大小上限")
            .setItems(labels) { _, which ->
                if (which < presets.size) {
                    LanSyncManager.settings().syncMaxSizeMb = presets[which]
                    refreshSyncSettingsUi()
                } else {
                    showCustomMaxSizeDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomMaxSizeDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "上限（M）"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("自定义大小上限")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n <= 0) {
                    Toast.makeText(this, "请输入有效大小（M）", Toast.LENGTH_SHORT).show()
                } else {
                    LanSyncManager.settings().syncMaxSizeMb = n
                    refreshSyncSettingsUi()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 同步类型多选：文字 / 图片 / 影视 / 其他（文件、音频归入"其他"） */
    private fun showSyncTypesDialog() {
        val groups = arrayOf(
            LanSettings.GROUP_TEXT, LanSettings.GROUP_IMAGE,
            LanSettings.GROUP_VIDEO, LanSettings.GROUP_OTHER
        )
        val labels = groups.map { LanSettings.GROUP_LABELS[it]!! }.toTypedArray()
        val s = LanSyncManager.settings()
        val checked = groups.map { it in s.syncTypeGroups }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("同步类型")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                val chosen = groups.filterIndexed { i, _ -> checked[i] }.toSet()
                s.syncTypeGroups = chosen
                refreshSyncSettingsUi()
                if (chosen.isEmpty()) {
                    Toast.makeText(this, "未勾选任何类型，同步将不会拉取内容", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        /** 设备名称长度上限 */
        private const val MAX_NAME_LENGTH = 20
    }
}

/** 黑名单列表适配器：头像 + 名称/型号 + 右侧「移出」按钮，设备 ID 编码不展示 */
private class BlockedAdapter(
    private val onUnblock: (Map.Entry<String, LanSettings.BlockedInfo>) -> Unit
) : RecyclerView.Adapter<BlockedAdapter.VH>() {

    private val items = mutableListOf<Map.Entry<String, LanSettings.BlockedInfo>>()

    fun submit(list: List<Map.Entry<String, LanSettings.BlockedInfo>>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.tvBlockedAvatar)
        val name: TextView = view.findViewById(R.id.tvBlockedName)
        val model: TextView = view.findViewById(R.id.tvBlockedModel)
        val btnUnblock: TextView = view.findViewById(R.id.btnUnblock)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val info = entry.value
        val displayName = info.name.ifBlank { "未知设备" }
        holder.avatar.text = displayName.first().toString()
        holder.name.text = displayName
        val model = info.model?.takeIf { it.isNotBlank() }
        holder.model.text = model ?: ""
        holder.model.visibility = if (model == null) View.GONE else View.VISIBLE
        holder.btnUnblock.setOnClickListener { onUnblock(entry) }
    }

    override fun getItemCount(): Int = items.size
}
