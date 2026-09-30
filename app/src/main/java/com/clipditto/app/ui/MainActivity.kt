package com.clipditto.app.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.BuildConfig
import com.clipditto.app.R
import com.clipditto.app.util.EdgeToEdge
import com.clipditto.app.backup.BackupManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.clipditto.app.service.BootReceiver
import com.clipditto.app.service.ClipboardService
import com.clipditto.app.service.PasteAccessibilityService
import com.clipditto.app.service.ShizukuClipboard
import com.clipditto.app.util.FuzzySearch
import com.clipditto.app.util.AppSettings
import com.clipditto.app.util.MediaFiles
import com.clipditto.app.util.StorageStats
import com.clipditto.app.util.UpdateChecker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** 本进程是否已做过自动静默检查更新（避免重建 Activity 反复弹） */
        @Volatile
        private var autoUpdateChecked = false
    }

    private lateinit var repo: ClipRepository
    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var btnListen: MaterialButton
    private lateinit var btnBall: MaterialButton

    private var allClips: List<ClipItem> = emptyList()
    private var query: String = ""

    /** 按搜索词过滤主界面列表（模糊匹配），命中字符在列表里高亮 */
    private fun applyFilter() {
        adapter.highlightQuery = query.trim()
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

    @SuppressLint("ClickableViewAccessibility")   // 搜索框清除图标用 OnTouchListener 判定点击区域
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // 沉浸式：Toolbar 背景延伸到状态栏，底部避开手势导航条
        EdgeToEdge.apply(this, findViewById(R.id.toolbar))

        repo = ClipRepository(this)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnListen = findViewById(R.id.btnToggleListen)
        btnBall = findViewById(R.id.btnToggleBall)

        adapter = HistoryAdapter(
            onClick = { item -> copyToSystem(item) },
            onLongClick = { item -> showItemMenu(item) }
        )
        val recyclerView = findViewById<RecyclerView>(R.id.recycler)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        // 滑动操作：右滑收藏/取消收藏，左滑删除（弹确认框）
        SwipeActions.attach(
            recyclerView, adapter,
            onFav = { item ->
                lifecycleScope.launch {
                    repo.toggleFavorite(item)
                    Toast.makeText(
                        this@MainActivity,
                        if (item.favorite) "已取消收藏" else "已收藏置顶",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDelete = { item -> confirmDeleteItem(item) }
        )

        lifecycleScope.launch {
            repo.clips.collectLatest { list ->
                // 撤销窗口内的待删条目即使被 flow 重新下发也不再显示
                val pendingId = pendingDelete?.first?.id
                allClips = if (pendingId == null) list else list.filter { it.id != pendingId }
                applyFilter()
                refreshStorage()   // 增删记录后同步刷新占用统计
            }
        }

        // 模糊搜索：输入即过滤；有内容时右侧出现「清除」图标，点击一键清空
        val etSearch = findViewById<EditText>(R.id.etSearch)
        etSearch.addTextChangedListener { text ->
            query = text?.toString() ?: ""
            updateSearchClearIcon(etSearch)
            applyFilter()
        }
        updateSearchClearIcon(etSearch)
        etSearch.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val end = etSearch.compoundDrawablesRelative[2]
                // 触点落在右侧清除图标范围内时清空搜索词
                if (end != null && event.x >= etSearch.width - etSearch.paddingEnd - end.bounds.width()) {
                    etSearch.text.clear()
                    return@setOnTouchListener true
                }
            }
            false
        }

        btnListen.setOnClickListener { toggleListen() }
        btnBall.setOnClickListener { toggleBall() }
        // 无障碍开启入口已合并到顶部状态栏（tvStatus 点我开启）；
        // Shizuku / 设备同步 / 删除 / 备份 / 导入 / 设置 / 关于 收进 Toolbar 菜单
        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_shizuku -> { onShizukuClick(); true }
                R.id.action_devices -> {
                    startActivity(Intent(this, DevicesActivity::class.java)); true
                }
                R.id.action_delete_range -> { showDeleteRangeDialog(); true }
                R.id.action_backup -> { onBackupClick(); true }
                R.id.action_import -> {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream")); true
                }
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java)); true
                }
                else -> false
            }
        }

        // 静默检查更新：每个进程只自动查一次，无新版/失败都不打扰
        if (!autoUpdateChecked) {
            autoUpdateChecked = true
            checkUpdateSilently()
        }

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
        // 退出页面时还在撤销窗口内的待删条目立即落库删除
        flushPendingDelete()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    // ---------------- Shizuku 免打扰读取 ----------------

    /** Shizuku 状态实时显示在 Toolbar 菜单项标题上 */
    private fun refreshShizukuButton() {
        findViewById<MaterialToolbar>(R.id.toolbar)
            .menu.findItem(R.id.action_shizuku)?.title = when {
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
        // 主界面打开时收起悬浮列表面板（默认关闭弹窗列表），悬浮球保持显示
        ClipboardService.instance?.dismissPanel()
        refreshListenButton()
        refreshBallButton()
        refreshStatus()
        refreshStorage()
        refreshShizukuButton()
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

    /** 显示监听服务 / 无障碍两项状态，无障碍未开启时点击跳转设置页 */
    private fun refreshStatus() {
        val status = buildString {
            append(if (ClipboardService.isRunning) "✅ 监听服务运行中" else "⛔ 监听服务未开启")
            append("　")
            append(if (PasteAccessibilityService.isEnabled) "✅ 无障碍已开启" else "⚠️ 无障碍未开启（点我开启）")
        }
        findViewById<TextView>(R.id.tvStatus).apply {
            text = status
            setOnClickListener {
                if (!PasteAccessibilityService.isEnabled) {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
    }

    // ---------------- 服务开关（监听与悬浮球相互独立） ----------------

    private fun refreshListenButton() {
        val running = ClipboardService.isRunning
        btnListen.text = if (running) "关闭监听" else "开启监听"
        btnListen.setIconResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
        styleStateButton(btnListen, running)
    }

    private fun refreshBallButton() {
        val on = AppSettings.isBallEnabled(this)
        btnBall.text = "悬浮球：${if (on) "开" else "关"}"
        styleStateButton(btnBall, on)
    }

    /** 高频开关按钮状态化：开 = 主色实心白字；关 = 灰底深字，一眼看出当前状态 */
    private fun styleStateButton(btn: MaterialButton, on: Boolean) {
        val bgAttr = if (on) com.google.android.material.R.attr.colorPrimary
            else com.google.android.material.R.attr.colorSurfaceVariant
        val fgAttr = if (on) com.google.android.material.R.attr.colorOnPrimary
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        val bg = MaterialColors.getColor(btn, bgAttr)
        val fg = MaterialColors.getColor(btn, fgAttr)
        btn.backgroundTintList = ColorStateList.valueOf(bg)
        btn.setTextColor(fg)
        btn.iconTint = ColorStateList.valueOf(fg)
    }

    /** 监听开关：只启动/停止剪贴板监听服务；悬浮球是否显示由悬浮球开关决定 */
    private fun toggleListen() {
        if (ClipboardService.isRunning) {
            ClipboardService.stop(this)
            saveServiceEnabled(false)
            refreshListenButton()
            return
        }
        if (!ensureOverlayPermission()) return
        ClipboardService.start(this)
        saveServiceEnabled(true)
        refreshListenButton()
        if (!PasteAccessibilityService.isEnabled) {
            Toast.makeText(
                this,
                "提示：开启无障碍服务后，点击文字记录可直接粘贴到输入框",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** 悬浮球开关：只控制悬浮球显示/隐藏，后台监听不受影响 */
    private fun toggleBall() {
        val now = !AppSettings.isBallEnabled(this)
        if (now && !ensureOverlayPermission()) return
        ClipboardService.setBallVisible(this, now)
        refreshBallButton()
        Toast.makeText(
            this,
            when {
                !now -> "悬浮球已关闭，后台监听不受影响"
                ClipboardService.isRunning -> "悬浮球已开启"
                else -> "悬浮球已开启，将在启动监听后显示"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 检查悬浮窗权限，未授权时弹引导；已授权返回 true */
    private fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        MaterialAlertDialogBuilder(this)
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
        return false
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
            Toast.makeText(this, "请先开启监听服务", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 长按记录弹出底部菜单：详情 / 收藏置顶 / 删除 */
    private fun showItemMenu(item: ClipItem) {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_item_menu, null)
        // 顶部显示内容预览，确认操作对象
        view.findViewById<TextView>(R.id.tvSheetPreview).text =
            item.text?.take(60)
                ?: item.filePath?.let { MediaFiles.displayName(this, it) }
                ?: ClipType.label(item.type)
        view.findViewById<TextView>(R.id.rowSheetFav).text =
            if (item.favorite) "取消收藏" else "★ 收藏置顶"
        view.findViewById<View>(R.id.rowSheetDetail).setOnClickListener {
            sheet.dismiss()
            showItemDetail(item)
        }
        view.findViewById<View>(R.id.rowSheetFav).setOnClickListener {
            sheet.dismiss()
            lifecycleScope.launch {
                repo.toggleFavorite(item)
                Toast.makeText(
                    this@MainActivity,
                    if (item.favorite) "已取消收藏" else "已收藏置顶",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        view.findViewById<View>(R.id.rowSheetDelete).setOnClickListener {
            sheet.dismiss()
            confirmDeleteItem(item)
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /** 详情底部弹窗：显示完整内容（长文本内部滚动、可选择复制），
     *  底部固定「粘贴」+ 与列表项一致的动作按钮（打开平台/浏览器）；
     *  下拉、点外部或返回键关闭 */
    private fun showItemDetail(item: ClipItem) {
        val view = layoutInflater.inflate(R.layout.dialog_detail, null)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val meta = buildString {
            append(ClipType.label(item.type))
            append(" · ${fmt.format(Date(item.timestamp))}")
            item.text?.let { append(" · 共 ${it.length} 字") }
        }
        view.findViewById<TextView>(R.id.detailMeta).text = meta
        // 文字记录显示全文；媒体/文件记录显示文件名 + 真实保存路径 + 大小
        view.findViewById<TextView>(R.id.detailContent).text = buildString {
            append(
                item.text
                    ?: item.filePath?.let { MediaFiles.displayName(this@MainActivity, it) }
                    ?: "（无文本内容）"
            )
            item.filePath?.let { p ->
                append("\n\n路径：${MediaFiles.displayPath(this@MainActivity, p)}")
                val size = MediaFiles.length(this@MainActivity, p)
                if (size > 0) append("\n大小：${StorageStats.format(size)}")
            }
        }
        // 图片/视频记录：显示媒体预览大图
        DetailPreview.bind(view.findViewById(R.id.ivDetailImage), item)

        val dialog = BottomSheetDialog(this)

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

        dialog.setContentView(view)
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

    /** 搜索框右侧清除图标：有内容时显示、无内容隐藏（左侧放大镜保持在 XML 中设置） */
    private fun updateSearchClearIcon(et: EditText) {
        val start = et.compoundDrawablesRelative[0]
        val end = if (et.text.isNullOrEmpty()) null
            else AppCompatResources.getDrawable(this, R.drawable.ic_clear)
        et.setCompoundDrawablesRelativeWithIntrinsicBounds(start, null, end, null)
    }

    private fun confirmDeleteItem(item: ClipItem) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage("删除这条记录？")
            .setPositiveButton("删除") { _, _ -> deleteWithUndo(item) }
            .setNegativeButton("取消", null)
            .show()
        // 删除按钮红色强调
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(getColor(R.color.danger))
    }

    // ---------------- 删除（可撤销） ----------------

    /** 已确认删除但还在撤销窗口内的条目（含原位置）；超时后才真正删除（含媒体文件） */
    private var pendingDelete: Pair<ClipItem, Int>? = null

    /** 列表先移除并给出撤销入口；Snackbar 超时/被打断后才真正落库删除 */
    private fun deleteWithUndo(item: ClipItem) {
        // 上一条还在撤销窗口内的记录立即真正删除，避免积压
        flushPendingDelete()
        val index = allClips.indexOfFirst { it.id == item.id }
        if (index < 0) return
        pendingDelete = item to index
        allClips = allClips.toMutableList().also { it.removeAt(index) }
        applyFilter()
        Snackbar.make(findViewById(R.id.recycler), "已删除", Snackbar.LENGTH_LONG)
            .setAction("撤销") {
                val (pending, idx) = pendingDelete ?: return@setAction
                pendingDelete = null
                allClips = allClips.toMutableList()
                    .also { it.add(idx.coerceAtMost(it.size), pending) }
                applyFilter()
            }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(sb: Snackbar?, event: Int) {
                    // 点「撤销」关闭的不落库；超时或滑走才真正删除
                    if (event != Snackbar.Callback.DISMISS_EVENT_ACTION) flushPendingDelete()
                }
            })
            .show()
    }

    /** 撤销窗口结束，真正删除待删条目 */
    private fun flushPendingDelete() {
        val (item, _) = pendingDelete ?: return
        pendingDelete = null
        lifecycleScope.launch { repo.delete(item) }
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
            .setTitle("确认删除")
            .setMessage("将删除「$label」的记录，且不可恢复，确定？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val favCount = repo.countFavoritesBetween(start, end)
                    if (favCount > 0) {
                        // 时间段内有收藏记录：询问是否一并删除
                        MaterialAlertDialogBuilder(this@MainActivity)
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

    // ---------------- 备份 / 导入 ----------------

    /** 备份入口：设置页已选保存文件夹时直接写入该文件夹，否则手动选位置 */
    private fun onBackupClick() {
        val name = "clipditto_backup_${System.currentTimeMillis()}.zip"
        val doc = createDocInBackupTree(name, "application/zip")
        if (doc != null) doBackup(doc) else backupLauncher.launch(name)
    }

    /** 在设置页选择的保存文件夹里新建文档，返回文档 Uri；未设置或失败返回 null */
    private fun createDocInBackupTree(displayName: String, mime: String): Uri? {
        val treeStr = AppSettings.getBackupTreeUri(this) ?: return null
        return runCatching {
            val treeUri = Uri.parse(treeStr)
            val treeDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            android.provider.DocumentsContract.createDocument(
                contentResolver, treeDocUri, mime, displayName
            )
        }.onFailure {
            Toast.makeText(this, "保存文件夹不可用，请到设置页重新选择", Toast.LENGTH_LONG).show()
        }.getOrNull()
    }

    // ---------------- 版本更新 ----------------

    /**
     * 静默检查更新：发现新版本时只弹一条轻提示（不弹对话框、不展示更新日志），
     * 下载与安装流程集中在 AboutActivity，主页不持有。
     */
    private fun checkUpdateSilently() {
        lifecycleScope.launch {
            val info = withContext(kotlinx.coroutines.Dispatchers.IO) {
                UpdateChecker.fetchLatest()
            }
            if (isFinishing || isDestroyed) return@launch
            if (info == null ||
                !UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME)
            ) return@launch
            Toast.makeText(
                this@MainActivity,
                "发现新版本 ${info.tag}，可到「关于」页更新",
                Toast.LENGTH_LONG
            ).show()
        }
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
        MaterialAlertDialogBuilder(this)
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
