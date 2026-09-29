package com.clipditto.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.clipditto.app.R
import com.clipditto.app.backup.ConfigManager
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.service.ClipboardService
import com.clipditto.app.util.AppSettings
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设置」页：悬浮球 / 悬浮面板 / 外观主题 / 记录规则 / 存储位置。
 * 开关项为「标题+副标题+Switch」行；点选项为整行可点、右侧显示当前值。
 * 所有面向用户的提示内容必须使用中文。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var repo: ClipRepository

    /** 选择数据库/配置保存文件夹（SAF 目录树） */
    private val dirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                AppSettings.setBackupTreeUri(this, it.toString())
                refreshBackupDirRow()
                Toast.makeText(this, "保存文件夹已设置", Toast.LENGTH_SHORT).show()
            }
        }

    /** 未设置保存文件夹时，导出配置退回手动选位置 */
    private val exportConfigLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { doExportConfig(it) }
        }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImportConfig(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        repo = ClipRepository(this)

        findViewById<MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        // ---- 悬浮球：Switch 已自行切换视觉状态，直接读 isChecked ----
        findViewById<SwitchCompat>(R.id.swBallEnabled).setOnClickListener {
            val now = findViewById<SwitchCompat>(R.id.swBallEnabled).isChecked
            ClipboardService.setBallVisible(this, now)
            Toast.makeText(
                this,
                if (now) "悬浮球已开启" else "悬浮球已关闭，后台监听不受影响",
                Toast.LENGTH_SHORT
            ).show()
        }

        // ---- 悬浮球外观：大小 / 透明度 ----
        findViewById<android.view.View>(R.id.rowBallSize).setOnClickListener {
            val cur = AppSettings.getBallSizeDp(this)
            showSliderDialog("悬浮球大小", 32, 64, cur, { "$it dp" }) { v ->
                AppSettings.setBallSizeDp(this, v)
                refreshBallSizeRow()
                ClipboardService.refreshAppearance(this)
            }
        }
        findViewById<android.view.View>(R.id.rowBallAlpha).setOnClickListener {
            val cur = AppSettings.getBallAlpha(this)
            showSliderDialog("悬浮球透明度", 30, 100, cur, { "$it%" }) { v ->
                AppSettings.setBallAlpha(this, v)
                refreshBallAlphaRow()
                ClipboardService.refreshAppearance(this)
            }
        }

        // ---- 悬浮面板 ----
        findViewById<SwitchCompat>(R.id.swRememberPanelSize).setOnClickListener {
            val now = findViewById<SwitchCompat>(R.id.swRememberPanelSize).isChecked
            AppSettings.setRememberPanelSize(this, now)
            Toast.makeText(
                this,
                if (now) "将记录调整后的弹窗列表大小" else "弹窗列表将始终使用默认大小",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<android.view.View>(R.id.rowPanelAlpha).setOnClickListener {
            val cur = AppSettings.getPanelAlpha(this)
            showSliderDialog("弹窗列表透明度", 30, 100, cur, { "$it%" }) { v ->
                AppSettings.setPanelAlpha(this, v)
                refreshPanelAlphaRow()
                ClipboardService.refreshAppearance(this)
            }
        }

        // ---- 外观主题 ----
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { showThemeDialog() }

        // ---- 记录规则 ----
        findViewById<android.view.View>(R.id.rowMaxRecords).setOnClickListener { showMaxRecordsDialog() }
        findViewById<android.view.View>(R.id.rowRetention).setOnClickListener { showRetentionDialog() }

        // ---- 存储位置 ----
        findViewById<android.view.View>(R.id.rowBackupDir).setOnClickListener { showBackupDirDialog() }
        findViewById<Button>(R.id.btnExportConfig).setOnClickListener { onExportConfigClick() }
        findViewById<Button>(R.id.btnImportConfig).setOnClickListener {
            importConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBallSwitch()
        refreshBallSizeRow()
        refreshBallAlphaRow()
        refreshPanelSwitch()
        refreshPanelAlphaRow()
        refreshThemeRow()
        refreshMaxRecordsRow()
        refreshRetentionRow()
        refreshBackupDirRow()
    }

    // ---------------- 悬浮球 / 面板 ----------------

    private fun refreshBallSwitch() {
        findViewById<SwitchCompat>(R.id.swBallEnabled).isChecked =
            AppSettings.isBallEnabled(this)
    }

    private fun refreshBallSizeRow() {
        findViewById<TextView>(R.id.tvBallSizeValue).text =
            "${AppSettings.getBallSizeDp(this)} dp"
    }

    private fun refreshBallAlphaRow() {
        findViewById<TextView>(R.id.tvBallAlphaValue).text =
            "${AppSettings.getBallAlpha(this)}%"
    }

    private fun refreshPanelSwitch() {
        findViewById<SwitchCompat>(R.id.swRememberPanelSize).isChecked =
            AppSettings.isRememberPanelSize(this)
    }

    private fun refreshPanelAlphaRow() {
        findViewById<TextView>(R.id.tvPanelAlphaValue).text =
            "${AppSettings.getPanelAlpha(this)}%"
    }

    /** 滑杆弹窗：拖动实时预览数值，确定后回调应用 */
    private fun showSliderDialog(
        title: String,
        min: Int,
        max: Int,
        current: Int,
        format: (Int) -> String,
        onApply: (Int) -> Unit
    ) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val tvValue = TextView(this).apply {
            text = format(current)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = current - min
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = format(min + p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(tvValue)
            addView(seek)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("确定") { _, _ -> onApply(min + seek.progress) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 外观主题 ----------------

    private fun themeLabel(mode: Int): String = when (mode) {
        AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
        AppCompatDelegate.MODE_NIGHT_YES -> "深色"
        else -> "跟随系统"
    }

    private fun refreshThemeRow() {
        findViewById<TextView>(R.id.tvThemeValue).text =
            themeLabel(AppSettings.getThemeMode(this))
    }

    private fun showThemeDialog() {
        val labels = arrayOf("跟随系统（默认）", "浅色", "深色")
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES
        )
        AlertDialog.Builder(this)
            .setTitle("外观主题")
            .setItems(labels) { _, which ->
                AppSettings.setThemeMode(this, modes[which])
                AppCompatDelegate.setDefaultNightMode(modes[which])
                Toast.makeText(this, "主题已切换为${themeLabel(modes[which])}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------------- 记录规则 ----------------

    private fun refreshMaxRecordsRow() {
        val max = repo.getMaxRecords()
        findViewById<TextView>(R.id.tvMaxRecordsValue).text =
            if (max == 0) "不限制" else "$max 条"
    }

    private fun showMaxRecordsDialog() {
        val current = repo.getMaxRecords()
        val presets = arrayOf("不限制（默认）", "100 条", "300 条", "500 条", "1000 条", "自定义…")
        val values = intArrayOf(0, 100, 300, 500, 1000, -1)
        AlertDialog.Builder(this)
            .setTitle("最多保存记录数（当前：${if (current == 0) "不限制" else "$current 条"}）\n超出后自动删除最旧的非收藏记录")
            .setItems(presets) { _, which ->
                val v = values[which]
                if (v >= 0) {
                    repo.setMaxRecords(v)
                    refreshMaxRecordsRow()
                    Toast.makeText(
                        this,
                        if (v == 0) "已设为不限制" else "最多保存 $v 条",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showCustomMaxDialog()
                }
            }
            .show()
    }

    private fun showCustomMaxDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入条数，0 表示不限制"
        }
        AlertDialog.Builder(this)
            .setTitle("自定义数量上限")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                repo.setMaxRecords(v)
                refreshMaxRecordsRow()
                Toast.makeText(
                    this,
                    if (v == 0) "已设为不限制" else "最多保存 $v 条",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRetentionRow() {
        val days = AppSettings.getRetentionDays(this)
        findViewById<TextView>(R.id.tvRetentionValue).text =
            if (days == 0) "不限制" else "$days 天"
    }

    private fun showRetentionDialog() {
        val current = AppSettings.getRetentionDays(this)
        val presets = arrayOf("不限制（默认）", "1 天", "7 天", "30 天", "90 天", "自定义…")
        val values = intArrayOf(0, 1, 7, 30, 90, -1)
        AlertDialog.Builder(this)
            .setTitle("数据保留天数（当前：${if (current == 0) "不限制" else "$current 天"}）\n超过天数的非收藏记录会被自动删除")
            .setItems(presets) { _, which ->
                val v = values[which]
                if (v >= 0) applyRetention(v) else showCustomRetentionDialog()
            }
            .show()
    }

    private fun showCustomRetentionDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入天数，0 表示不限制"
        }
        AlertDialog.Builder(this)
            .setTitle("自定义保留天数")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                applyRetention(v)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存保留天数并立即清理一次过期记录 */
    private fun applyRetention(days: Int) {
        AppSettings.setRetentionDays(this, days)
        refreshRetentionRow()
        if (days <= 0) {
            Toast.makeText(this, "已设为不限制", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val removed = repo.enforceRetention()
            Toast.makeText(
                this@SettingsActivity,
                if (removed > 0) "保留 $days 天，已清理 $removed 条过期记录"
                else "保留天数已设为 $days 天",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------- 存储位置 ----------------

    private fun displayDirName(treeUri: String): String =
        runCatching {
            Uri.decode(Uri.parse(treeUri).lastPathSegment ?: treeUri).substringAfter(':')
        }.getOrDefault(treeUri)

    private fun refreshBackupDirRow() {
        val tree = AppSettings.getBackupTreeUri(this)
        findViewById<TextView>(R.id.tvBackupDirValue).text =
            if (tree == null) "未设置" else displayDirName(tree)
    }

    private fun showBackupDirDialog() {
        val hasDir = AppSettings.getBackupTreeUri(this) != null
        val items = if (hasDir) arrayOf("重新选择文件夹", "清除（恢复每次手动选位置）")
        else arrayOf("选择文件夹")
        AlertDialog.Builder(this)
            .setTitle("数据库/配置保存文件夹")
            .setItems(items) { _, which ->
                when {
                    which == 0 -> dirLauncher.launch(null)
                    hasDir && which == 1 -> {
                        AppSettings.setBackupTreeUri(this, null)
                        refreshBackupDirRow()
                        Toast.makeText(this, "已清除，备份时将手动选择保存位置", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    /** 在已设置的文件夹里新建一个文档，返回文档 Uri；失败返回 null */
    private fun createDocInBackupTree(displayName: String, mime: String): Uri? {
        val treeStr = AppSettings.getBackupTreeUri(this) ?: return null
        return runCatching {
            val treeUri = Uri.parse(treeStr)
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(contentResolver, treeDocUri, mime, displayName)
        }.onFailure {
            Toast.makeText(this, "保存文件夹不可用，请重新选择", Toast.LENGTH_LONG).show()
        }.getOrNull()
    }

    private fun onExportConfigClick() {
        val name = "clipditto_config_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        val doc = createDocInBackupTree(name, "application/json")
        if (doc != null) doExportConfig(doc) else exportConfigLauncher.launch(name)
    }

    private fun doExportConfig(uri: Uri) {
        lifecycleScope.launch {
            runCatching { ConfigManager.export(this@SettingsActivity, uri) }
                .onSuccess {
                    Toast.makeText(this@SettingsActivity, "配置已导出（$it 项）", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this@SettingsActivity, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun doImportConfig(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setMessage("配置将覆盖当前设置项（不含剪贴板记录），继续？")
            .setPositiveButton("导入") { _, _ ->
                lifecycleScope.launch {
                    runCatching { ConfigManager.import(this@SettingsActivity, uri) }
                        .onSuccess {
                            // 主题可能随配置变化，立即应用并刷新界面
                            AppSettings.applyTheme(this@SettingsActivity)
                            onResume()
                            Toast.makeText(this@SettingsActivity, "配置已导入（$it 项）", Toast.LENGTH_LONG).show()
                        }
                        .onFailure {
                            Toast.makeText(this@SettingsActivity, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
