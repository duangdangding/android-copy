package com.clipditto.app.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.clipditto.app.R
import com.clipditto.app.util.EdgeToEdge
import com.clipditto.app.sync.relay.RelaySettings
import com.clipditto.app.sync.relay.RelaySyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 「云端中继」设置页（设计文档 docs/android-relay-sync-design.md §7）：
 * 总开关 + 服务器地址/接入密钥 + 分组 ID/密钥 + 图片中继开关 + 实时连接状态。
 *
 * 配置在输入框失去焦点/IME 完成时保存；任何变更都会触发 RelaySyncManager
 * 代际 +1，连接循环自动断开重连（重启生效，与 PC 端一致）。
 */
class RelaySettingsActivity : AppCompatActivity() {

    private lateinit var settings: RelaySettings
    private lateinit var swEnabled: SwitchCompat
    private lateinit var swSyncImage: SwitchCompat
    private lateinit var etServerUrl: EditText
    private lateinit var etAccessKey: EditText
    private lateinit var etGroupId: EditText
    private lateinit var etGroupKey: EditText

    /** 程序化改开关时屏蔽回调，避免递归 */
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_settings)
        // 沉浸式：Toolbar 背景延伸到状态栏，底部避开手势导航条
        EdgeToEdge.apply(this, findViewById(R.id.toolbar))

        RelaySyncManager.init(this)
        settings = RelaySyncManager.settings()

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        swEnabled = findViewById(R.id.swEnabled)
        swSyncImage = findViewById(R.id.swSyncImage)
        etServerUrl = findViewById(R.id.etServerUrl)
        etAccessKey = findViewById(R.id.etAccessKey)
        etGroupId = findViewById(R.id.etGroupId)
        etGroupKey = findViewById(R.id.etGroupKey)

        // 载入当前配置
        etServerUrl.setText(settings.serverUrl)
        etAccessKey.setText(settings.accessKey)
        etGroupId.setText(settings.groupId)
        etGroupKey.setText(settings.groupKey)
        suppressSwitch = true
        swEnabled.isChecked = settings.enabled
        swSyncImage.isChecked = settings.syncImage
        suppressSwitch = false

        // 输入框失焦即保存并触发重连
        listOf(etServerUrl, etAccessKey, etGroupId, etGroupKey).forEach { et ->
            et.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveInputs() }
            et.setOnEditorActionListener { _, _, _ -> saveInputs(); false }
        }

        // 密钥是密码框：悬浮球被系统隐藏、无障碍也贴不进去，用页内按钮从前台剪贴板粘贴
        bindPaste(R.id.btnPasteAccessKey, etAccessKey)
        bindPaste(R.id.btnPasteGroupKey, etGroupKey)

        swEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            saveInputs()
            if (isChecked && !settings.isComplete()) {
                Toast.makeText(
                    this,
                    "请先填写服务器地址、接入密钥和分组 ID",
                    Toast.LENGTH_LONG
                ).show()
                suppressSwitch = true
                swEnabled.isChecked = false
                suppressSwitch = false
                return@setOnCheckedChangeListener
            }
            settings.enabled = isChecked
            RelaySyncManager.notifyChanged()
            refreshStatusArea()
        }

        swSyncImage.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            settings.syncImage = isChecked
            RelaySyncManager.notifyChanged()
        }

        findViewById<Button>(R.id.btnReconnect).setOnClickListener {
            RelaySyncManager.reconnect()
            Toast.makeText(this, "正在重新连接…", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnResetCursor).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("全量补拉")
                .setMessage("将清除同步游标并重新连接，服务器暂存的历史条目会全部重新拉取（重复内容自动去重）。确定继续？")
                .setPositiveButton("继续") { _, _ ->
                    RelaySyncManager.resetCursor()
                    refreshStatusArea()
                    Toast.makeText(this, "已清除游标，正在重连补拉…", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 状态与在线设备实时刷新
        lifecycleScope.launch {
            RelaySyncManager.status.collectLatest { refreshStatusArea() }
        }
        lifecycleScope.launch {
            RelaySyncManager.peers.collectLatest { refreshStatusArea() }
        }
        refreshStatusArea()
    }

    /**
     * 密钥框的「粘贴」按钮：直接读系统剪贴板填入并保存。
     * 密码框聚焦时系统会隐藏悬浮窗（悬浮球无法使用），且无障碍 ACTION_PASTE
     * 对密码节点无效；App 在前台读剪贴板没有任何限制，这是唯一可靠的通道。
     */
    private fun bindPaste(btnId: Int, target: EditText) {
        findViewById<Button>(btnId).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            target.setText(text)
            target.setSelection(text.length)
            saveInputs()
            Toast.makeText(this, "已粘贴", Toast.LENGTH_SHORT).show()
        }
    }

    /** 把输入框内容写回配置（任何变更都会触发自动重连） */
    private fun saveInputs() {
        val changed = etServerUrl.text.toString().trim() != settings.serverUrl ||
            etAccessKey.text.toString().trim() != settings.accessKey ||
            etGroupId.text.toString().trim() != settings.groupId ||
            etGroupKey.text.toString().trim() != settings.groupKey
        if (!changed) return
        settings.serverUrl = etServerUrl.text.toString()
        settings.accessKey = etAccessKey.text.toString()
        settings.groupId = etGroupId.text.toString()
        settings.groupKey = etGroupKey.text.toString()
        // 已启用时地址可能从完整变成不完整（或反之），开关状态无需联动，连接循环会提示配置不完整
        RelaySyncManager.notifyChanged()
        refreshStatusArea()
    }

    private fun refreshStatusArea() {
        findViewById<TextView>(R.id.tvStatus).text =
            "连接状态：${RelaySyncManager.status.value}"
        val peers = RelaySyncManager.peers.value
        findViewById<TextView>(R.id.tvPeers).text =
            if (peers.isEmpty()) "同组在线设备：无"
            else "同组在线设备：\n" + peers.joinToString("\n") { "· ${it.name} — 云端" }
        findViewById<TextView>(R.id.tvLastSeq).text =
            "同步游标：seq ${RelaySyncManager.settings().lastSeq}"
    }
}
