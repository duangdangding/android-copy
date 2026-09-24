package com.clipditto.app.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.data.ClipDatabase
import com.clipditto.app.data.TransferRecord
import com.clipditto.app.share.FileShareClient
import com.clipditto.app.share.FileShareManager
import com.clipditto.app.share.FsDevice
import com.clipditto.app.share.PendingTransfer
import com.clipditto.app.sync.LanSettings
import com.clipditto.app.sync.LanSyncManager
import com.clipditto.app.util.FuzzySearch
import com.clipditto.app.util.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「文件共享」页：
 * 顶部本机信息行（名称 + IP:端口）与「自动接收」开关、存储位置说明，
 * 中部在线设备列表（点击设备 → 选文件发送；或「输入 IP 发送」/「刷新」），
 * 底部接收记录（单击看详情/打开文件，长按进入多选批量删除）。
 */
class FileShareActivity : AppCompatActivity() {

    private lateinit var deviceAdapter: FsDeviceAdapter
    private lateinit var recordAdapter: RecordAdapter
    private lateinit var client: FileShareClient

    /** 文件选择完成后要发送到的目标（host, port）；点设备或输 IP 时先记下 */
    private var pendingTarget: Pair<String, Int>? = null

    /** 主线程 Handler：接收确认弹窗 30 秒自动关闭用 */
    private val uiHandler = Handler(Looper.getMainLooper())

    /** 进行中确认弹窗的超时任务（Activity 销毁时统一移除，防泄漏） */
    private val confirmTimeouts = mutableListOf<Runnable>()

    // 接收记录多选状态
    private var records: List<TransferRecord> = emptyList()
    private var selectionMode = false
    private val selected = mutableSetOf<Long>()
    private lateinit var backCallback: OnBackPressedCallback

    /** 接收记录搜索词（只过滤显示，不改数据库） */
    private var recQuery = ""

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val target = pendingTarget
            pendingTarget = null
            if (uris.isNullOrEmpty() || target == null) return@registerForActivityResult
            sendFiles(uris, target.first, target.second)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_share)

        FileShareManager.init(this)
        client = FileShareClient(LanSyncManager.settings())

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        bindSwitches()

        deviceAdapter = FsDeviceAdapter { d ->
            pendingTarget = d.host to d.port
            pickFiles.launch(arrayOf("*/*"))
        }
        findViewById<RecyclerView>(R.id.recyclerDevices).apply {
            layoutManager = LinearLayoutManager(this@FileShareActivity)
            adapter = deviceAdapter
        }

        recordAdapter = RecordAdapter(
            onClick = { r -> onRecordClick(r) },
            onLongClick = { r -> enterSelectionMode(r) }
        )
        findViewById<RecyclerView>(R.id.recyclerRecords).apply {
            layoutManager = LinearLayoutManager(this@FileShareActivity)
            adapter = recordAdapter
        }
        bindRecordsBottomBar()

        // 接收记录搜索：实时过滤显示；搜索词变化时清空勾选，避免误删不可见条目
        findViewById<EditText>(R.id.etRecSearch).addTextChangedListener { text ->
            recQuery = text?.toString() ?: ""
            if (selected.isNotEmpty()) selected.clear()
            refreshRecordList()
        }

        // 多选模式下按返回键 = 退出多选，而不是关闭页面
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = exitSelectionMode()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        findViewById<Button>(R.id.btnSendIp).setOnClickListener { showIpDialog() }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            // 主动询问在线设备立即回应；beacon 监听 socket 异常时顺便触发重建
            FileShareManager.discovery.queryNow()
            FileShareManager.discovery.kickListener()
            Toast.makeText(this, "正在刷新设备列表…", Toast.LENGTH_SHORT).show()
        }

        // 在线设备列表
        lifecycleScope.launch {
            FileShareManager.discovery.devices.collectLatest { list ->
                deviceAdapter.submit(list)
                findViewById<TextView>(R.id.tvDevicesEmpty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // 接收记录列表
        lifecycleScope.launch {
            ClipDatabase.get(this@FileShareActivity).transferDao().observeAll()
                .collectLatest { list ->
                    records = list
                    // 清掉已消失记录的勾选状态
                    selected.retainAll(list.map { it.id }.toSet())
                    refreshRecordList()
                }
        }
        // 页面可见期间每 5 秒主动询问一次，加快设备出现速度
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    FileShareManager.discovery.queryNow()
                    delay(5_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册前台确认弹窗：本页可见时来文件直接弹窗，不走通知
        FileShareManager.confirmUiHandler = { p -> showReceiveConfirm(p) }
        FileShareManager.discovery.queryNow()
        refreshUi()
        refreshMyInfo()
    }

    override fun onPause() {
        super.onPause()
        FileShareManager.confirmUiHandler = null
    }

    override fun onDestroy() {
        // 移除所有确认弹窗的超时任务，防止 Activity 销毁后回调泄漏
        confirmTimeouts.forEach { uiHandler.removeCallbacks(it) }
        confirmTimeouts.clear()
        super.onDestroy()
    }

    // ---------------- 开关 ----------------

    private fun bindSwitches() {
        // 接收能力常驻，只剩「自动接收」一个开关，始终可点
        findViewById<SwitchCompat>(R.id.swFsAutoReceive).setOnCheckedChangeListener { _, on ->
            FileShareManager.settings.autoReceive = on
        }
    }

    private fun refreshUi() {
        val s = FileShareManager.settings
        findViewById<SwitchCompat>(R.id.swFsAutoReceive).isChecked = s.autoReceive
        // 存储位置与局域网同步共用同一份设置，这里只读展示
        val dir = LanSyncManager.settings().syncDirUri
        findViewById<TextView>(R.id.tvFsSavePath).text =
            if (dir == null) "存储位置（与局域网同步共用）：系统 Download/ClipDitto"
            else "存储位置（与局域网同步共用）：${displayDirName(dir)}"
    }

    /** 把 SAF 树 URI 转为可读目录名，如 "primary:Download/clip" → "Download/clip" */
    private fun displayDirName(treeUri: String): String =
        runCatching {
            Uri.decode(Uri.parse(treeUri).lastPathSegment ?: treeUri).substringAfter(':')
        }.getOrDefault(treeUri)

    /** 顶部本机信息行：名称 + 局域网 IP:端口（供对方手动输入） */
    private fun refreshMyInfo() {
        val name = LanSyncManager.settings().deviceName
        val ip = FileShareManager.localIp()
        val port = LanSyncManager.serverPort
        findViewById<TextView>(R.id.tvMyInfo).text =
            if (ip == null) "本机：$name · 未连接局域网"
            else "本机：$name · $ip:$port"
    }

    // ---------------- 接收确认（前台弹窗） ----------------

    /**
     * 接收确认弹窗：在 HTTP 服务线程上被调用，转到主线程展示；
     * 用户点击后通过 resolvePending 解出服务线程的等待锁。
     * 30 秒未操作自动按拒收处理并关闭弹窗（与服务端等待超时一致）。
     */
    private fun showReceiveConfirm(p: PendingTransfer) {
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                FileShareManager.resolvePending(p.id, false)
                return@runOnUiThread
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("文件接收请求")
                .setMessage(
                    "「${p.fromDeviceName}」想发送\n${p.fileName}" +
                        "（${FileShareManager.formatSize(p.fileSize)}）\n\n是否接收？（30 秒未操作自动拒绝）"
                )
                .setCancelable(false)
                .setPositiveButton("接收") { _, _ ->
                    FileShareManager.resolvePending(p.id, true)
                }
                .setNegativeButton("拒绝") { _, _ ->
                    FileShareManager.resolvePending(p.id, false)
                }
                .create()
            // 30 秒自动关闭：按拒收处理；弹窗被 dismiss 时（含这里）移除该任务
            val timeoutTask = Runnable {
                FileShareManager.resolvePending(p.id, false)
                runCatching { if (dialog.isShowing) dialog.dismiss() }
            }
            dialog.setOnDismissListener {
                uiHandler.removeCallbacks(timeoutTask)
                confirmTimeouts.remove(timeoutTask)
            }
            confirmTimeouts.add(timeoutTask)
            dialog.show()
            uiHandler.postDelayed(timeoutTask, 30_000)
        }
    }

    // ---------------- 发送 ----------------

    /** 手动输入 IP 发送：接受 "ip" 或 "ip:port"，默认端口 8765（与局域网同步共用） */
    private fun showIpDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "对方 IP，如 192.168.1.23 或 192.168.1.23:8765"
        }
        AlertDialog.Builder(this)
            .setTitle("输入 IP 发送")
            .setMessage("输入对方设备的局域网 IP（可带端口，默认 ${LanSettings.DEFAULT_PORT}），然后选择要发送的文件")
            .setView(input)
            .setPositiveButton("选择文件") { _, _ ->
                val target = parseHostPort(input.text.toString().trim())
                if (target == null) {
                    Toast.makeText(this, "IP 格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingTarget = target
                pickFiles.launch(arrayOf("*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 解析 "ip" / "ip:port"；IPv6 裸地址（含多个冒号）按不带端口处理 */
    private fun parseHostPort(s: String): Pair<String, Int>? {
        if (s.isBlank()) return null
        val idx = s.lastIndexOf(':')
        if (idx > 0 && s.indexOf(':') == idx) {
            // 恰好一个冒号：按 host:port 解析
            val host = s.substring(0, idx)
            val port = s.substring(idx + 1).toIntOrNull()
            if (host.isNotBlank() && port != null && port in 1..65535) return host to port
            return null
        }
        return s to LanSettings.DEFAULT_PORT
    }

    /** 待发送文件的元数据；读不到大小时先拷贝到缓存拿到准确大小 */
    private data class SendMeta(
        val name: String,
        val mime: String?,
        val size: Long,
        val tmpFile: File?
    )

    private fun resolveMeta(uri: Uri): SendMeta? = runCatching {
        var name: String? = null
        var size = -1L
        contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = contentResolver.getType(uri)
        if (size >= 0) return SendMeta(name ?: "文件", mime, size, null)
        // 读不到大小：先拷到缓存（发送需要固定 Content-Length）
        val tmp = File(cacheDir, "fs_send_${System.currentTimeMillis()}.tmp")
        contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
        SendMeta(name ?: "文件", mime, tmp.length(), tmp)
    }.getOrNull()

    /** 逐个发送所选文件，期间显示进度弹窗，结束后弹出汇总结果 */
    private fun sendFiles(uris: List<Uri>, host: String, port: Int) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val tvProgress = TextView(this).apply { textSize = 14f }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(
                ProgressBar(this@FileShareActivity, null, android.R.attr.progressBarStyle)
                    .apply { isIndeterminate = true }
            )
            addView(tvProgress)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("发送文件到 $host:$port")
            .setView(box)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            var ok = 0
            val failures = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val meta = withContext(Dispatchers.IO) { resolveMeta(uri) }
                if (meta == null) {
                    failures.add("（第 ${i + 1} 个文件无法读取信息）")
                    return@forEachIndexed
                }
                tvProgress.text = "正在发送 ${i + 1}/${uris.size}：${meta.name}"
                val err = withContext(Dispatchers.IO) {
                    runCatching {
                        client.send(host, port, meta.name, meta.mime, meta.size) {
                            meta.tmpFile?.inputStream()
                                ?: contentResolver.openInputStream(uri)
                        }
                    }.exceptionOrNull()?.message
                }
                if (err == null) ok++ else failures.add("${meta.name}：$err")
                meta.tmpFile?.delete()
            }
            dialog.dismiss()
            val msg = buildString {
                append("成功 $ok 个")
                if (failures.isNotEmpty()) {
                    append("，失败 ${failures.size} 个\n")
                    append(failures.joinToString("\n"))
                }
            }
            AlertDialog.Builder(this@FileShareActivity)
                .setTitle("发送结果")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    // ---------------- 接收记录 ----------------

    // ---------- 列表与多选 ----------

    private fun refreshRecordList() {
        // 搜索只影响显示：按文件名/来源设备模糊匹配（忽略大小写 + 子序列）
        val filtered = if (recQuery.isBlank()) records else records.filter {
            FuzzySearch.matches(recQuery, it.fileName) ||
                FuzzySearch.matches(recQuery, it.fromDeviceName)
        }
        recordAdapter.submit(filtered, selected.toSet(), selectionMode)
        val tvEmpty = findViewById<TextView>(R.id.tvRecordsEmpty)
        tvEmpty.text =
            if (records.isEmpty()) "暂无接收记录" else "没有匹配「$recQuery」的记录"
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        val bar = findViewById<LinearLayout>(R.id.recordsBottomBar)
        bar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvRecSelection).text = "已选 ${selected.size} 条"
        backCallback.isEnabled = selectionMode
    }

    /** 单击：多选模式下切换勾选，否则打开详情 */
    private fun onRecordClick(r: TransferRecord) {
        if (selectionMode) {
            if (!selected.remove(r.id)) selected.add(r.id)
            refreshRecordList()
        } else {
            showRecordDetail(r)
        }
    }

    /** 长按进入多选模式并选中该条 */
    private fun enterSelectionMode(r: TransferRecord) {
        if (selectionMode) return
        selectionMode = true
        selected.add(r.id)
        refreshRecordList()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selected.clear()
        refreshRecordList()
    }

    private fun bindRecordsBottomBar() {
        findViewById<Button>(R.id.btnRecDelete).setOnClickListener {
            val targets = records.filter { it.id in selected }
            if (targets.isEmpty()) {
                Toast.makeText(this, "请先勾选要删除的记录", Toast.LENGTH_SHORT).show()
            } else {
                confirmDeleteRecords(targets)
            }
        }
        findViewById<Button>(R.id.btnRecCancel).setOnClickListener { exitSelectionMode() }
    }

    // ---------- 详情与打开文件 ----------

    /** 详情对话框：文件名/大小/来源/时间/保存位置，按钮 打开文件 / 删除 / 关闭 */
    private fun showRecordDetail(r: TransferRecord) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val msg = buildString {
            append("文件名：${r.fileName}\n")
            append("大小：${FileShareManager.formatSize(r.fileSize)}\n")
            append("来源：${r.fromDeviceName ?: "未知设备"}\n")
            append("接收时间：${fmt.format(Date(r.timestamp))}\n")
            append("保存位置：${displayPath(r.savedPath)}")
        }
        AlertDialog.Builder(this)
            .setTitle("文件详情")
            .setMessage(msg)
            .setPositiveButton("打开文件") { _, _ -> openRecordFile(r) }
            .setNegativeButton("删除") { _, _ -> confirmDeleteRecords(listOf(r)) }
            .setNeutralButton("关闭", null)
            .show()
    }

    /**
     * 保存位置的可读展示（content:// 只是句柄，文件本体只有一个）：
     * - MediaStore 下载 URI（默认目录落盘）：查 RELATIVE_PATH + DISPLAY_NAME
     *   拼成真实路径，如 "Download/ClipDitto/xxx.jpg"；查不到则原样显示
     * - SAF 目录树文档 URI：documentId（"primary:Download/clip/xx"）转 "Download/clip/xx"
     * - 本地路径（API 26-28 私有目录回退）：原样显示
     */
    private fun displayPath(path: String): String {
        if (!MediaFiles.isContentUri(path)) return path
        val uri = Uri.parse(path)
        if (uri.authority?.startsWith("media") == true) {
            return runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val dir = c.getString(0)
                    val name = c.getString(1)
                    if (dir.isNullOrBlank() || name.isNullOrBlank()) null
                    else dir + name  // RELATIVE_PATH 末尾自带 "/"
                }
            }.getOrNull() ?: path
        }
        return runCatching {
            val seg = Uri.decode(uri.lastPathSegment ?: path)
            if (seg.contains(':')) seg.substringAfter(':') else path
        }.getOrDefault(path)
    }

    /**
     * 用系统应用打开接收的文件：content:// 直接用；本地路径（API 26-28 私有目录回退）
     * 经 FileProvider 授权暴露。文件已不存在或没有可打开的应用时给出提示。
     */
    private fun openRecordFile(r: TransferRecord) {
        if (!MediaFiles.exists(this, r.savedPath)) {
            Toast.makeText(this, "文件不存在或已被手动移走", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = runCatching {
            if (MediaFiles.isContentUri(r.savedPath)) Uri.parse(r.savedPath)
            else FileProvider.getUriForFile(this, "$packageName.fileprovider", File(r.savedPath))
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, "无法打开该文件", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, r.mimeType ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 删除（单条/批量共用） ----------

    /** 删除确认：可选是否同时删除已接收的文件（单条与批量共用同一流程） */
    private fun confirmDeleteRecords(targets: List<TransferRecord>) {
        if (targets.isEmpty()) return
        val title = if (targets.size == 1) "删除该记录？" else "删除选中的 ${targets.size} 条记录？"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("是否同时删除已接收的文件？")
            .setPositiveButton("删除记录和文件") { _, _ -> deleteRecords(targets, deleteFile = true) }
            .setNegativeButton("仅删除记录") { _, _ -> deleteRecords(targets, deleteFile = false) }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun deleteRecords(targets: List<TransferRecord>, deleteFile: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            var fileFailures = 0
            if (deleteFile) {
                // content:// 和本地路径统一走 MediaFiles 删除
                targets.forEach {
                    if (!MediaFiles.delete(applicationContext, it.savedPath)) fileFailures++
                }
            }
            ClipDatabase.get(applicationContext).transferDao()
                .deleteByIds(targets.map { it.id })
            launch(Dispatchers.Main) {
                exitSelectionMode()
                Toast.makeText(
                    this@FileShareActivity,
                    if (deleteFile && fileFailures > 0)
                        "已删除 ${targets.size} 条记录，$fileFailures 个文件删除失败（可能已被手动移走）"
                    else "已删除 ${targets.size} 条记录",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/** 在线设备列表：头像 + 名称/型号 + 在线状态与地址 */
private class FsDeviceAdapter(
    private val onClick: (FsDevice) -> Unit
) : RecyclerView.Adapter<FsDeviceAdapter.VH>() {

    private var items: List<FsDevice> = emptyList()

    fun submit(list: List<FsDevice>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.tvFsAvatar)
        val name: TextView = v.findViewById(R.id.tvFsName)
        val host: TextView = v.findViewById(R.id.tvFsHost)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_fs_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val d = items[position]
        holder.avatar.text = d.name.firstOrNull()?.toString() ?: "?"
        holder.name.text =
            if (d.model.isNullOrBlank()) d.name else "${d.name}（${d.model}）"
        holder.host.text = "在线 · ${d.host}:${d.port}" + (if (d.legacy) " · PC" else "")
        holder.itemView.setOnClickListener { onClick(d) }
    }

    override fun getItemCount(): Int = items.size
}

/** 接收记录列表：文件名 + 大小/时间/来源设备；多选模式下显示 CheckBox */
private class RecordAdapter(
    private val onClick: (TransferRecord) -> Unit,
    private val onLongClick: (TransferRecord) -> Unit
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    private var items: List<TransferRecord> = emptyList()
    private var selected: Set<Long> = emptySet()
    private var selectionMode = false

    fun submit(list: List<TransferRecord>, selected: Set<Long>, selectionMode: Boolean) {
        items = list
        this.selected = selected
        this.selectionMode = selectionMode
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: TextView = v.findViewById(R.id.tvRecIcon)
        val name: TextView = v.findViewById(R.id.tvRecName)
        val meta: TextView = v.findViewById(R.id.tvRecMeta)
        val check: CheckBox = v.findViewById(R.id.checkRec)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_record, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.icon.text = r.fileName.firstOrNull()?.toString() ?: "件"
        holder.name.text = r.fileName
        holder.meta.text = "${FileShareManager.formatSize(r.fileSize)} · " +
            "${fmt.format(Date(r.timestamp))} · 来自 ${r.fromDeviceName ?: "未知设备"}"
        val isSelected = r.id in selected
        holder.itemView.setBackgroundResource(
            if (isSelected) R.drawable.bg_item_selected else R.drawable.bg_item
        )
        holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
        // 勾选状态只作展示，切换统一走 item 点击，避免两套入口状态不一致
        holder.check.isChecked = isSelected
        holder.itemView.setOnClickListener { onClick(r) }
        holder.itemView.setOnLongClickListener {
            onLongClick(r)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}
