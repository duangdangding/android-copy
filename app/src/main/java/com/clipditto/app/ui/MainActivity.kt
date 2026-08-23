package com.clipditto.app.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.backup.BackupManager
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.clipditto.app.service.BootReceiver
import com.clipditto.app.service.ClipboardService
import com.clipditto.app.service.PasteAccessibilityService
import com.clipditto.app.service.ShizukuClipboard
import com.clipditto.app.util.FuzzySearch
import com.clipditto.app.util.StorageStats
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repo: ClipRepository
    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var btnToggle: Button

    private var allClips: List<ClipItem> = emptyList()
    private var query: String = ""

    /** 按搜索词过滤主界面列表（模糊匹配） */
    private fun applyFilter() {
        val filtered = allClips.filter { FuzzySearch.matches(query, it.text) }
        adapter.submit(filtered)
        tvEmpty.text = if (query.isBlank())
            "暂无剪贴板记录\n复制任意内容后会出现在这里"
        else
            "没有匹配「$query」的记录"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { doBackup(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { doImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = ClipRepository(this)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnToggle = findViewById(R.id.btnToggleService)

        adapter = HistoryAdapter(
            onClick = { item -> copyToSystem(item) },
            onLongClick = { item -> showItemMenu(item) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        lifecycleScope.launch {
            repo.clips.collectLatest { list ->
                allClips = list
                applyFilter()
                refreshStorage()   // 增删记录后同步刷新占用统计
            }
        }

        // 模糊搜索
        findViewById<EditText>(R.id.etSearch).addTextChangedListener { text ->
            query = text?.toString() ?: ""
            applyFilter()
        }

        btnToggle.setOnClickListener { toggleService() }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在列表中找到「剪贴板管家」并开启", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnDeleteRange).setOnClickListener { showDeleteRangeDialog() }
        findViewById<Button>(R.id.btnBackup).setOnClickListener {
            backupLauncher.launch("clipditto_backup_${System.currentTimeMillis()}.zip")
        }
        findViewById<Button>(R.id.btnImport).setOnClickListener {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        findViewById<Button>(R.id.btnMaxRecords).setOnClickListener { showMaxRecordsDialog() }
        findViewById<Button>(R.id.btnLanSync).setOnClickListener {
            startActivity(Intent(this, DevicesActivity::class.java))
        }

        findViewById<Button>(R.id.btnShizuku).setOnClickListener { onShizukuClick() }
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        requestNotificationPermissionIfNeeded()
    }

    /** Shizuku 授权结果回调：授权成功后立即绑定桥接服务并刷新按钮 */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuClipboard.bind()
                Toast.makeText(this, "Shizuku 已授权，读取剪贴板不再抢焦点", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
            refreshShizukuButton()
        }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    // ---------------- Shizuku 免打扰读取 ----------------

    private fun refreshShizukuButton() {
        findViewById<Button>(R.id.btnShizuku).text = when {
            !ShizukuClipboard.isServerRunning() ->
                "Shizuku 免打扰读取：服务未运行（点击打开）"
            !ShizukuClipboard.isPermissionGranted() ->
                "Shizuku 免打扰读取：未授权（点击授权）"
            ShizukuClipboard.isChannelBroken() ->
                "Shizuku 免打扰读取：读取失败已回退（点击重试）"
            else -> "Shizuku 免打扰读取：✅ 已启用"
        }
    }

    private fun onShizukuClick() {
        when {
            !ShizukuClipboard.isServerRunning() -> {
                val launch = packageManager.getLaunchIntentForPackage(ShizukuClipboard.SHIZUKU_PACKAGE)
                if (launch != null) {
                    startActivity(launch)
                    Toast.makeText(this, "请先在 Shizuku 中启动服务", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "未安装 Shizuku，请先安装并启动", Toast.LENGTH_LONG).show()
                }
            }
            !ShizukuClipboard.isPermissionGranted() -> ShizukuClipboard.requestPermission()
            ShizukuClipboard.isChannelBroken() -> {
                ShizukuClipboard.resetChannel()
                Toast.makeText(this, "已重试 Shizuku 通道", Toast.LENGTH_SHORT).show()
                refreshShizukuButton()
            }
            else -> Toast.makeText(this, "Shizuku 读取已启用，复制监听不再抢焦点", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 主界面打开时收起悬浮列表面板，避免它盖在主界面上层
        ClipboardService.instance?.dismissPanel()
        refreshToggleButton()
        refreshStatus()
        refreshStorage()
        refreshMaxRecordsBtn()
        refreshShizukuButton()
    }

    /** 按钮上直接显示当前上限设置 */
    private fun refreshMaxRecordsBtn() {
        val max = repo.getMaxRecords()
        findViewById<Button>(R.id.btnMaxRecords).text =
            "记录数量上限：${if (max == 0) "无限制" else "$max 条"}"
    }

    /** 显示应用 / 数据库占用情况 */
    private fun refreshStorage() {
        val tv = findViewById<TextView>(R.id.tvStorage)
        lifecycleScope.launch {
            val count = repo.count()
            val stats = StorageStats.collect(this@MainActivity, count)
            tv.text = "应用占用：${StorageStats.format(stats.appTotalBytes)}" +
                "（媒体 ${StorageStats.format(stats.mediaBytes)}）　" +
                "数据库：${StorageStats.format(stats.dbBytes)}（${stats.recordCount} 条记录）"
        }
    }

    /** 显示监听服务 / 无障碍 / 使用情况访问三项状态，点击可跳转对应设置页 */
    private fun refreshStatus() {
        val usageGranted = hasUsageAccess()
        val status = buildString {
            append(if (ClipboardService.isRunning) "✅ 监听服务运行中" else "⛔ 监听服务未开启")
            append("　")
            append(if (PasteAccessibilityService.isEnabled) "✅ 无障碍已开启" else "⚠️ 无障碍未开启（点我开启）")
            append("　")
            append(if (usageGranted) "✅ 使用情况访问" else "⚠️ 使用情况访问未授权（来源识别备用）")
        }
        findViewById<TextView>(R.id.tvStatus).apply {
            text = status
            setOnClickListener {
                when {
                    !PasteAccessibilityService.isEnabled ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    !usageGranted ->
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 60_000L, end)
        return events.hasNextEvent()
    }

    // ---------------- 服务开关 ----------------

    private fun refreshToggleButton() {
        btnToggle.text = if (ClipboardService.isRunning) "关闭监听+悬浮球" else "开启监听+悬浮球"
    }

    private fun toggleService() {
        if (ClipboardService.isRunning) {
            ClipboardService.stop(this)
            saveServiceEnabled(false)
            refreshToggleButton()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("悬浮球和剪贴板面板需要「显示在其他应用上层」权限，是否前往开启？")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        ClipboardService.start(this)
        saveServiceEnabled(true)
        refreshToggleButton()
        if (!PasteAccessibilityService.isEnabled) {
            Toast.makeText(
                this,
                "提示：开启无障碍服务后，点击文字记录可直接粘贴到输入框",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveServiceEnabled(enabled: Boolean) {
        getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_SERVICE_ENABLED, enabled)
            .apply()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    // ---------------- 主列表点击 ----------------

    /** 主界面中点击 = 复制回系统剪贴板（主界面本身持有焦点，无需无障碍） */
    private fun copyToSystem(item: ClipItem) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (item.type == ClipType.TEXT) {
            cm.setPrimaryClip(ClipData.newPlainText("clipditto", item.text ?: ""))
        } else {
            // 媒体交给悬浮面板逻辑复制（含 FileProvider 授权）
            ClipboardService.instance?.let {
                Toast.makeText(this, "请通过悬浮球面板复制媒体内容", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "请先开启悬浮球服务", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 长按记录弹出菜单：详情 / 收藏置顶 / 删除 */
    private fun showItemMenu(item: ClipItem) {
        val favLabel = if (item.favorite) "取消收藏" else "★ 收藏置顶"
        AlertDialog.Builder(this)
            .setItems(arrayOf("查看详情", favLabel, "删除")) { _, which ->
                when (which) {
                    0 -> showItemDetail(item)
                    1 -> lifecycleScope.launch {
                        repo.toggleFavorite(item)
                        Toast.makeText(
                            this@MainActivity,
                            if (item.favorite) "已取消收藏" else "已收藏置顶",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    2 -> confirmDeleteItem(item)
                }
            }
            .show()
    }

    /** 详情弹窗：显示完整内容（长文本内部滚动、可选择复制），
     *  底部固定「粘贴」+ 与列表项一致的动作按钮（打开平台/浏览器） */
    private fun showItemDetail(item: ClipItem) {
        val view = layoutInflater.inflate(R.layout.dialog_detail, null)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val meta = buildString {
            append(ClipType.label(item.type))
            append(" · ${fmt.format(Date(item.timestamp))}")
            item.sourceApp?.takeIf { it.isNotBlank() }?.let { append("\n来源：$it") }
            item.text?.let { append(" · 共 ${it.length} 字") }
        }
        view.findViewById<TextView>(R.id.detailMeta).text = meta
        view.findViewById<TextView>(R.id.detailContent).text =
            item.text ?: item.filePath?.let { "文件路径：$it" } ?: "（无文本内容）"

        val dialog = AlertDialog.Builder(this)
            .setTitle("详情")
            .setView(view)
            .setNegativeButton("关闭", null)
            .create()

        // 动作按钮：与列表项完全一致的识别和跳转逻辑
        ItemActionButtons.bind(
            view.findViewById(R.id.btnDetailOpenUrl),
            view.findViewById(R.id.btnDetailOpenAlt),
            view.findViewById(R.id.btnDetailOpenBrowser),
            item
        ) { dialog.dismiss() }

        // 粘贴 = 点击列表项：主界面为复制回系统剪贴板
        view.findViewById<Button>(R.id.btnDetailPaste).setOnClickListener {
            dialog.dismiss()
            copyToSystem(item)
        }

        dialog.show()
        // 内容区太高时压缩为屏幕 45% 并内部滚动，保证底部按钮始终可见
        val scroll = view.findViewById<ScrollView>(R.id.detailScroll)
        scroll.post {
            val maxContentH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            if (scroll.height > maxContentH) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxContentH }
            }
        }
    }

    private fun confirmDeleteItem(item: ClipItem) {
        AlertDialog.Builder(this)
            .setMessage("删除这条记录？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    repo.delete(item)
                    Toast.makeText(this@MainActivity, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 按时间段删除 ----------------

    private fun showDeleteRangeDialog() {
        val options = arrayOf(
            "最近 1 小时",
            "今天（0 点至今）",
            "最近 7 天",
            "最近 30 天",
            "自定义时间段",
            "全部清空"
        )
        AlertDialog.Builder(this)
            .setTitle("按时间删除记录")
            .setItems(options) { _, which ->
                val now = System.currentTimeMillis()
                when (which) {
                    0 -> confirmDeleteRange(now - 3600_000L, now, "最近 1 小时")
                    1 -> confirmDeleteRange(todayStart(), now, "今天")
                    2 -> confirmDeleteRange(now - 7L * 86400_000L, now, "最近 7 天")
                    3 -> confirmDeleteRange(now - 30L * 86400_000L, now, "最近 30 天")
                    4 -> pickCustomRange()
                    5 -> confirmDeleteRange(0L, now, "全部记录")
                }
            }
            .show()
    }

    private fun todayStart(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun pickCustomRange() {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val now = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val start = Calendar.getInstance().apply {
                    set(y, m, d, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                DatePickerDialog(
                    this,
                    { _, y2, m2, d2 ->
                        val end = Calendar.getInstance().apply {
                            set(y2, m2, d2, 23, 59, 59)
                            set(Calendar.MILLISECOND, 999)
                        }
                        val label = "${fmt.format(start.time)} ~ ${fmt.format(end.time)}"
                        confirmDeleteRange(start.timeInMillis, end.timeInMillis, label)
                    },
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
                ).apply { setTitle("选择结束日期") }.show()
            },
            now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)
        ).apply { setTitle("选择开始日期") }.show()
    }

    private fun confirmDeleteRange(start: Long, end: Long, label: String) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("将删除「$label」的记录，且不可恢复，确定？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val favCount = repo.countFavoritesBetween(start, end)
                    if (favCount > 0) {
                        // 时间段内有收藏记录：询问是否一并删除
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("包含收藏记录")
                            .setMessage("「$label」内有 $favCount 条收藏记录，是否一并删除？")
                            .setPositiveButton("一并删除") { _, _ ->
                                doDeleteRange(start, end, includeFavorites = true)
                            }
                            .setNegativeButton("保留收藏") { _, _ ->
                                doDeleteRange(start, end, includeFavorites = false)
                            }
                            .setNeutralButton("取消", null)
                            .show()
                    } else {
                        doDeleteRange(start, end, includeFavorites = true)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doDeleteRange(start: Long, end: Long, includeFavorites: Boolean) {
        lifecycleScope.launch {
            val count = repo.deleteBetween(start, end, includeFavorites)
            Toast.makeText(
                this@MainActivity,
                if (includeFavorites) "已删除 $count 条" else "已删除 $count 条（收藏已保留）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ---------------- 记录数量上限 ----------------

    private fun showMaxRecordsDialog() {
        val current = repo.getMaxRecords()
        val presets = arrayOf("无限制（0）", "100 条", "300 条", "500 条", "1000 条", "自定义…")
        val values = intArrayOf(0, 100, 300, 500, 1000, -1)
        AlertDialog.Builder(this)
            .setTitle("记录数量上限（当前：${if (current == 0) "无限制" else "$current 条"}）\n超出后自动删除最旧的非收藏记录")
            .setItems(presets) { _, which ->
                val v = values[which]
                if (v >= 0) {
                    repo.setMaxRecords(v)
                    refreshMaxRecordsBtn()
                    Toast.makeText(
                        this,
                        if (v == 0) "已设为无限制" else "上限已设为 $v 条",
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
            hint = "输入条数，0 表示无限制"
        }
        AlertDialog.Builder(this)
            .setTitle("自定义数量上限")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                repo.setMaxRecords(v)
                refreshMaxRecordsBtn()
                Toast.makeText(
                    this,
                    if (v == 0) "已设为无限制" else "上限已设为 $v 条",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 备份 / 导入 ----------------

    private fun doBackup(uri: Uri) {
        lifecycleScope.launch {
            runCatching { BackupManager.export(this@MainActivity, repo, uri) }
                .onSuccess {
                    Toast.makeText(this@MainActivity, "备份完成，共 $it 条", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this@MainActivity, "备份失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun doImport(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("导入备份")
            .setMessage("备份中的记录将合并到当前列表（不会覆盖现有记录），继续？")
            .setPositiveButton("导入") { _, _ ->
                lifecycleScope.launch {
                    runCatching { BackupManager.import(this@MainActivity, repo, uri) }
                        .onSuccess {
                            Toast.makeText(this@MainActivity, "导入完成，共 $it 条", Toast.LENGTH_LONG).show()
                        }
                        .onFailure {
                            Toast.makeText(this@MainActivity, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
