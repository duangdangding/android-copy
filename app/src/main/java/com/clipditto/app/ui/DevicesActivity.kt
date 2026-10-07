package com.clipditto.app.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.util.EdgeToEdge
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.sync.LanDevice
import com.clipditto.app.sync.LanSyncManager
import com.clipditto.app.sync.BlockedByException
import com.clipditto.app.sync.EncryptionRequiredException
import com.clipditto.app.sync.NeedPairingException
import com.clipditto.app.sync.SharingOffException
import com.clipditto.app.sync.UnpairedException
import com.clipditto.app.sync.relay.RelayClient
import com.clipditto.app.sync.relay.RelaySyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 「局域网剪贴板同步」设备页：
 * 顶部三个开关（可被发现 / 共享剪贴板 / 自动同步），下方设备列表支持多选批量操作。
 */
class DevicesActivity : AppCompatActivity() {

    private lateinit var adapter: DeviceAdapter
    private lateinit var repo: ClipRepository
    private lateinit var bottomBar: LinearLayout
    private lateinit var tvSelection: TextView

    private var devices: List<LanDevice> = emptyList()
    private var syncing: Set<String> = emptySet()
    private val selected = mutableSetOf<String>()

    /** 云端中继在线设备（服务器 peers 广播），合并进设备列表展示 */
    private var relayPeers: List<RelayClient.Peer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 按窗口效果选主题：窗口创建即是对应背景/模糊，避免进入时闪变
        setTheme(com.clipditto.app.util.WindowEffect.themeRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)
        // 全局窗口效果（默认/亚克力/半透明），设置页切换后 onResume 会重新应用
        com.clipditto.app.util.WindowEffect.apply(this)
        // 沉浸式：Toolbar 背景延伸到状态栏，底部避开手势导航条
        EdgeToEdge.apply(this, findViewById(R.id.toolbar))

        repo = ClipRepository(this)
        LanSyncManager.init(this)
        RelaySyncManager.init(this)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationOnClickListener { finish() }
            // 右上角「设置」：局域网同步/云端中继的全部配置项
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_settings -> {
                        // 右上角「设置」：局域网同步/云端中继的全部配置项
                        startActivity(
                            Intent(this@DevicesActivity, LanSyncSettingsActivity::class.java)
                        )
                        true
                    }
                    R.id.action_sweep -> startSweep()
                    else -> false
                }
            }
        }

        bottomBar = findViewById(R.id.bottomBar)
        tvSelection = findViewById(R.id.tvSelection)

        adapter = DeviceAdapter(
            onClick = { d -> onDeviceClick(d) },
            onToggleSelect = { d, checked ->
                if (checked) selected.add(d.deviceId) else selected.remove(d.deviceId)
                refreshList()
            }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@DevicesActivity)
            adapter = this@DevicesActivity.adapter
        }

        bindBottomBar()

        // 文件共享入口（与剪贴板同步相互独立的功能，合入设备同步页）
        findViewById<android.view.View>(R.id.rowFileShare).setOnClickListener {
            startActivity(Intent(this, FileShareActivity::class.java))
        }

        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            LanSyncManager.discovery.restartDiscovery()
            Toast.makeText(this, "正在重新扫描局域网…", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnAddIp).setOnClickListener { showAddIpDialog() }

        requestNearbyWifiPermissionIfNeeded()

        lifecycleScope.launch {
            LanSyncManager.devices.collectLatest { list ->
                devices = list
                // 清掉已消失设备的选择状态
                selected.retainAll(list.map { it.deviceId }.toSet())
                refreshList()
            }
        }
        lifecycleScope.launch {
            LanSyncManager.syncing.collectLatest { s ->
                syncing = s
                refreshList()
            }
        }
        // 云端中继在线设备变化时重新合并列表（同一 deviceId 并入现有卡，不重复出卡）
        lifecycleScope.launch {
            RelaySyncManager.peers.collectLatest { p ->
                relayPeers = p
                refreshList()
            }
        }
        // 诊断信息：每秒刷新一次（本机广播状态 + 扫描状态 + NSD 原始服务数）
        lifecycleScope.launch {
            while (true) {
                refreshDiag()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    /** Android 13+：附近 Wi-Fi 设备是运行时权限，不申请会导致 NSD/组播静默失败 */
    private fun requestNearbyWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val p = android.Manifest.permission.NEARBY_WIFI_DEVICES
        if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(p), 42)
        }
    }

    /** 手动输入 IP 添加设备（NSD 和广播都失效时的兜底） */
    private fun showAddIpDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "对方 IP，如 192.168.1.23"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("手动添加设备")
            .setMessage("在对方设备上查看其局域网 IP（Wi-Fi 设置里），输入后直接连接")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val ip = input.text.toString().trim()
                if (!ip.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) {
                    Toast.makeText(this, "IP 格式不正确", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val device = LanSyncManager.discovery.addManualHost(ip)
                    launch(kotlinx.coroutines.Dispatchers.Main) {
                        Toast.makeText(
                            this@DevicesActivity,
                            if (device != null) "已添加：${device.displayName}"
                            else "未找到设备（请确认对方已开启可被发现，且 IP 正确）",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 深度扫描：遍历本网段所有地址（已收进右上角溢出菜单，低频操作） */
    private fun startSweep(): Boolean {
        if (LanSyncManager.discovery.sweeping.value) {
            Toast.makeText(this, "深度扫描进行中…", Toast.LENGTH_SHORT).show()
        } else {
            LanSyncManager.discovery.sweepSubnet()
            Toast.makeText(this, "正在遍历本网段所有地址（约 10 秒）…", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun refreshDiag() {
        val s = LanSyncManager.settings()
        val disc = LanSyncManager.discovery
        val st = disc.stats.value
        val nearbyGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val line1 = buildString {
            append("本机：")
            append(disc.localIp() ?: "无局域网IP")
            append(" · ")
            if (s.discoverable) {
                if (disc.registered) append("已广播（端口 ${LanSyncManager.serverPort}）")
                else append(disc.registerError.value ?: "广播注册中…")
            } else append("未开启可被发现")
            if (!nearbyGranted) append("　⚠️ 附近设备权限未授予")
        }
        val line2 = buildString {
            append("扫描：")
            if (disc.sweeping.value) append("深度扫描中…")
            else append(if (disc.scanning.value) "进行中" else "已停止")
            append(" · mDNS 发现 ${disc.rawFound.value}")
            append(" · 解析成功 ${st.resolveOk} 失败 ${st.resolveFail}")
            append(" · HTTP 不通 ${st.httpFail}")
            append(" · 跳过本机 ${st.skipSelf}")
            append(" · 收到广播 ${st.beaconRx}")
            append(" · 识别 ${devices.count { it.online }} 台")
        }
        // 诊断信息分级：正常状态用次要色；出现异常（无局域网IP / 广播失败 / 权限缺失）才标红
        val hasProblem = disc.localIp() == null ||
            (s.discoverable && disc.registerError.value != null) || !nearbyGranted
        findViewById<TextView>(R.id.tvDiag).apply {
            text = "$line1\n$line2"
            setTextColor(getColor(if (hasProblem) R.color.danger else R.color.text_secondary))
        }
    }

    // ---------------- 黑名单 ----------------

    /** 加入黑名单确认 */
    private fun confirmBlock(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("加入黑名单")
            .setMessage("「${d.displayName}」加入黑名单后：\n· 你无法对它同步/配对\n· 它无法扫描到本机\n· 它无法访问本机内容\n\n确定？")
            .setPositiveButton("加入") { _, _ ->
                LanSyncManager.blockDevice(d)
                refreshDiag()
                Toast.makeText(this, "已加入黑名单", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 配对 ----------------

    companion object {
        /** 设备名称长度上限 */
        private const val MAX_NAME_LENGTH = 20
        /** 配对通知跳转携带：请求方 deviceId（等待已结束时用于提示「已超时」） */
        const val EXTRA_PAIR_DEVICE_ID = "pair_device_id"
        /** 「配对请求已过期」通知跳转携带 */
        const val EXTRA_PAIR_EXPIRED = "pair_expired"
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时，窗口效果可能已变更，重新应用
        com.clipditto.app.util.WindowEffect.apply(this)
        LanSyncManager.discoveryScreenActive = true
        // 注册配对确认弹窗：其他设备请求配对时在此页面弹出同意/拒绝
        LanSyncManager.pairApprovalUiHandler = { requester -> askPairApproval(requester) }
        // 通知路径挂起中的配对请求：用户点通知进入本页时补弹确认框
        val pendingRequester = LanSyncManager.pendingPairRequester()
        if (pendingRequester != null) {
            showPendingPairDialog(pendingRequester)
        } else if (intent?.hasExtra(EXTRA_PAIR_DEVICE_ID) == true) {
            // 点了配对通知但等待已结束（通知残留等场景）：明确告知，而不是静默无反应
            Toast.makeText(this, "该配对请求已超时，请对方重新发起", Toast.LENGTH_LONG).show()
        } else if (intent?.getBooleanExtra(EXTRA_PAIR_EXPIRED, false) == true) {
            Toast.makeText(this, "配对请求已过期，请对方重新发起", Toast.LENGTH_LONG).show()
        }
        intent?.removeExtra(EXTRA_PAIR_DEVICE_ID)
        intent?.removeExtra(EXTRA_PAIR_EXPIRED)
    }

    override fun onPause() {
        super.onPause()
        LanSyncManager.discoveryScreenActive = false
        LanSyncManager.pairApprovalUiHandler = null
    }

    /** 配对确认弹窗：在 HTTP 服务线程上被调用，阻塞等待用户选择（共享实现见 PairApprovalUi） */
    private fun askPairApproval(requester: LanDevice): Boolean =
        PairApprovalUi.askBlocking(this, requester) == PairApprovalUi.Answer.APPROVED

    /** 通知路径挂起的配对请求：用户点通知进入本页时补弹确认框（不阻塞，结果走等待锁） */
    private fun showPendingPairDialog(requester: LanDevice) {
        val modelSuffix = requester.model?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
        MaterialAlertDialogBuilder(this)
            .setTitle("配对请求")
            .setMessage("「${requester.displayName}$modelSuffix」请求与本机配对，配对后可以访问你共享的剪贴板内容。\n\n是否同意？")
            .setCancelable(false)
            .setPositiveButton("同意配对") { _, _ ->
                LanSyncManager.resolvePendingPair(requester.deviceId, true)
            }
            .setNegativeButton("拒绝") { _, _ ->
                LanSyncManager.resolvePendingPair(requester.deviceId, false)
            }
            .show()
    }

    // ---------------- 列表与多选 ----------------

    /**
     * 双通道合并（对齐 PC 端 §7.4）：同一 deviceId 在 LAN 与中继同时在线时合并为一张卡；
     * 局域网看不到但中继在线的设备，追加一张「云端」卡。
     */
    private fun mergedDevices(): List<LanDevice> {
        if (relayPeers.isEmpty()) return devices
        val known = devices.mapTo(HashSet()) { it.deviceId }
        val merged = devices.map { d ->
            d.copy(viaRelay = relayPeers.any { it.deviceId == d.deviceId })
        }
        val relayOnly = relayPeers.filter { it.deviceId !in known }
            .map { LanDevice(deviceId = it.deviceId, name = it.name, viaRelay = true) }
        return merged + relayOnly
    }

    private fun refreshList() {
        val merged = mergedDevices()
        adapter.submit(merged, selected.toSet(), syncing)
        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (merged.isEmpty()) View.VISIBLE else View.GONE
        val n = selected.size
        bottomBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        tvSelection.text = "已选 $n 台设备"
    }

    private fun onDeviceClick(d: LanDevice) {
        if (selected.isNotEmpty()) {
            // 多选模式下点击 = 切换勾选
            if (selected.remove(d.deviceId).not()) selected.add(d.deviceId)
            refreshList()
            return
        }
        when {
            // 仅云端可达且未局域网配对：配对/同步等局域网操作不可用
            d.viaRelay && !d.online && !d.paired -> showRelayOnlyMenu(d)
            !d.online -> showOfflineDeviceMenu(d)
            !d.paired -> showUnpairedMenu(d)
            else -> showDeviceMenu(d)
        }
    }

    /** 纯云端设备点击：说明 + 删除同步来的记录（中继是实时推送，无需手动同步） */
    private fun showRelayOnlyMenu(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("${d.displayName}（云端在线）")
            .setMessage("通过中继服务器连接，未在局域网内。\n复制内容会自动实时同步，无需手动操作。")
            .setPositiveButton("删除该设备同步来的记录") { _, _ -> confirmDeleteRemote(listOf(d)) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 离线设备点击：删除同步来的记录 / 从列表移除（已配对但仅云端可达的也走这里，这两个操作都是本地管理，可用） */
    private fun showOfflineDeviceMenu(d: LanDevice) {
        val state = if (d.viaRelay) "云端在线" else "离线"
        MaterialAlertDialogBuilder(this)
            .setTitle("${d.displayName}（$state）")
            .setItems(arrayOf("删除该设备同步来的记录", "从列表移除该设备")) { _, which ->
                when (which) {
                    0 -> confirmDeleteRemote(listOf(d))
                    1 -> confirmRemoveDevice(d)
                }
            }
            .show()
    }

    /**
     * 删除离线设备确认：解除本地配对并清理同步水位，列表不再残留。
     * 同步记录由用户选择：保留 或 连同媒体文件一起删除。
     */
    private fun confirmRemoveDevice(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("移除设备")
            .setMessage("把「${d.displayName}」从列表移除？\n将解除本地配对并清理同步进度。该设备同步到本机的记录（含媒体文件）如何处理？")
            .setPositiveButton("保留记录并移除") { _, _ ->
                performRemove(d, deleteRecords = false, fullRemove = true)
            }
            .setNeutralButton("删除记录并移除") { _, _ ->
                performRemove(d, deleteRecords = true, fullRemove = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 取消配对确认：同样由用户选择是否连同该设备同步来的记录一起删除 */
    private fun confirmUnpair(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle("取消配对")
            .setMessage("与「${d.displayName}」解除配对？\n该设备同步到本机的记录（含媒体文件）如何处理？")
            .setPositiveButton("保留记录并解除") { _, _ ->
                performRemove(d, deleteRecords = false, fullRemove = false)
            }
            .setNeutralButton("删除记录并解除") { _, _ ->
                performRemove(d, deleteRecords = true, fullRemove = false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 执行移除/解除配对。
     * @param fullRemove true = 从列表彻底移除（清同步水位，走 removeDevice）；
     *   false = 仅取消配对（保留水位，重新配对后增量续传，走 unpair）
     * @param deleteRecords true = 同时删除该设备同步到本机的全部记录（含媒体文件）
     */
    private fun performRemove(d: LanDevice, deleteRecords: Boolean, fullRemove: Boolean) {
        selected.remove(d.deviceId)
        if (fullRemove) LanSyncManager.removeDevice(d) else LanSyncManager.unpair(d.deviceId)
        val action = if (fullRemove) "已移除" else "已解除配对"
        if (!deleteRecords) {
            Toast.makeText(this, "$action「${d.displayName}」，同步记录已保留", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val (n, f) = repo.deleteRemoteDevice(d.deviceId)
            val msg = if (f > 0)
                "$action「${d.displayName}」并删除其同步记录 $n 条（$f 个文件未能删除，可能已被手动移走）"
            else
                "$action「${d.displayName}」并删除其同步记录 $n 条"
            Toast.makeText(this@DevicesActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /** 未配对设备点击：配对 / 加入黑名单 */
    private fun showUnpairedMenu(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle(d.displayName)
            .setItems(arrayOf("配对", "加入黑名单")) { _, which ->
                when (which) {
                    0 -> showPairDialog(d)
                    1 -> confirmBlock(d)
                }
            }
            .show()
    }

    // ---------------- 配对 ----------------

    private fun showPairDialog(d: LanDevice) {
        val s = LanSyncManager.settings()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(this).apply {
            setText(s.deviceName)
            setSelection(text.length)
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME_LENGTH))
        }
        val codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "输入对方显示的 6 位配对码"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(TextView(this@DevicesActivity).apply {
                text = "本机名称（对方列表里显示的名字，可修改）"
                textSize = 13f
            })
            addView(nameInput)
            addView(TextView(this@DevicesActivity).apply {
                text = "对方配对码"
                textSize = 13f
            })
            addView(codeInput)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("配对「${d.displayName}」")
            .setMessage("请在对方设备上打开「共享剪贴板」，屏幕上会显示配对码")
            .setView(container)
            // 按钮监听到 show 之后重写：只有配对成功才关闭弹窗，失败保留现场可直接重试
            .setPositiveButton("配对", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
            // 名称有修改则先保存，对方配对成功后显示的就是新名字
            val name = nameInput.text.toString().trim()
            if (name.isNotEmpty() && name != s.deviceName) {
                s.deviceName = name
                LanSyncManager.refreshName()
                refreshDiag()
            }
            val token = codeInput.text.toString().trim()
            if (token.length != 6) {
                Toast.makeText(this, "配对码是 6 位数字", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 配对中禁用按钮防重复点击（对方可能弹窗确认，最长等 35 秒）
            btn.isEnabled = false
            lifecycleScope.launch {
                val err = LanSyncManager.pair(d, token)
                if (err == null) {
                    Toast.makeText(this@DevicesActivity, "配对成功", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@launch
                }
                btn.isEnabled = true
                val msg = when (err) {
                    is LanSyncManager.PairError.BadToken ->
                        "配对码错误\n\n请核对对方设备屏幕上显示的 6 位配对码（不是本机的码）"
                    is LanSyncManager.PairError.SharingOff ->
                        "对方未开启「共享本机剪贴板」\n\n请在对方设备上打开该开关"
                    is LanSyncManager.PairError.Offline ->
                        "设备不在线\n\n请点「重新扫描」后重试"
                    is LanSyncManager.PairError.Rejected ->
                        "对方拒绝了本次配对"
                    is LanSyncManager.PairError.NeedConfirm ->
                        "对方未响应配对请求\n\n请让对方留意通知栏的「配对请求」通知，或让对方开启「自动同意配对请求」"
                    is LanSyncManager.PairError.Blocked ->
                        "该设备在你的黑名单中\n\n请先在「黑名单」中将其移出"
                    is LanSyncManager.PairError.BlockedBy ->
                        "对方已把你加入黑名单\n\n等对方移出黑名单后可重新配对"
                    is LanSyncManager.PairError.ConnectFail ->
                        "连不上对方\n\n${err.detail}"
                }
                // 失败详情可能较长，用弹窗完整显示（输入弹窗保持打开，关掉本提示即可重试）
                MaterialAlertDialogBuilder(this@DevicesActivity)
                    .setTitle("配对失败")
                    .setMessage(msg)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun showDeviceMenu(d: LanDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle(d.displayName)
            .setItems(arrayOf("立即同步", "删除该设备同步来的记录", "取消配对", "加入黑名单")) { _, which ->
                when (which) {
                    0 -> syncDevices(listOf(d))
                    1 -> confirmDeleteRemote(listOf(d))
                    2 -> confirmUnpair(d)
                    3 -> confirmBlock(d)
                }
            }
            .show()
    }

    // ---------------- 批量操作 ----------------

    private fun bindBottomBar() {
        findViewById<Button>(R.id.btnSyncSelected).setOnClickListener {
            val merged = mergedDevices()
            val skipped = merged.count { it.deviceId in selected && it.viaRelay && !it.online }
            // 仅云端可达的设备没有局域网拉取通道：中继是实时推送，无需也无法手动同步
            val targets = merged.filter { it.deviceId in selected && !(it.viaRelay && !it.online) }
            if (skipped > 0) {
                Toast.makeText(
                    this,
                    "已跳过 $skipped 台仅云端设备（复制内容会自动同步）",
                    Toast.LENGTH_SHORT
                ).show()
            }
            if (targets.isNotEmpty()) syncDevices(targets)
        }
        findViewById<Button>(R.id.btnDeleteSelected).setOnClickListener {
            // 用合并后的列表：纯云端设备也能删除其同步来的记录
            confirmDeleteRemote(mergedDevices().filter { it.deviceId in selected })
        }
        findViewById<Button>(R.id.btnCancelSelection).setOnClickListener {
            selected.clear()
            refreshList()
        }
    }

    private fun syncDevices(targets: List<LanDevice>) {
        val actionable = targets.filter { it.online }
        if (actionable.isEmpty()) {
            Toast.makeText(this, "所选设备都不在线", Toast.LENGTH_SHORT).show()
            return
        }
        // 先选同步范围，再执行
        showSyncScopeDialog { scope -> doSync(actionable, scope) }
    }

    // ---------------- 同步范围选择 ----------------

    /** 三种同步范围：最近 N 条 / 某一天 / 全部 */
    private fun showSyncScopeDialog(onPicked: (LanSyncManager.SyncScope) -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("选择同步范围")
            .setItems(arrayOf("同步最近 N 条", "同步某一天", "全部同步")) { _, which ->
                when (which) {
                    0 -> showRecentCountDialog(onPicked)
                    1 -> showSyncDayPicker(onPicked)
                    2 -> onPicked(LanSyncManager.SyncScope.All)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 最近 N 条：常用档位 + 自定义输入 */
    private fun showRecentCountDialog(onPicked: (LanSyncManager.SyncScope) -> Unit) {
        val counts = intArrayOf(10, 20, 50, 100, 200)
        val labels = counts.map { "$it 条" }.toTypedArray() + "自定义…"
        MaterialAlertDialogBuilder(this)
            .setTitle("同步最近多少条？")
            .setItems(labels) { _, which ->
                if (which < counts.size) {
                    onPicked(LanSyncManager.SyncScope.Recent(counts[which]))
                } else {
                    showCustomCountDialog(onPicked)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomCountDialog(onPicked: (LanSyncManager.SyncScope) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "条数"
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("自定义条数")
            .setView(container)
            .setPositiveButton("同步") { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n == null || n <= 0) {
                    Toast.makeText(this, "请输入有效条数", Toast.LENGTH_SHORT).show()
                } else {
                    onPicked(LanSyncManager.SyncScope.Recent(n))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择某一天：默认今天，不允许选未来日期 */
    private fun showSyncDayPicker(onPicked: (LanSyncManager.SyncScope) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val dayStart = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                onPicked(LanSyncManager.SyncScope.Day(dayStart))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    private fun doSync(
        actionable: List<LanDevice>,
        scope: LanSyncManager.SyncScope
    ) {
        lifecycleScope.launch {
            val results = LanSyncManager.syncDevices(actionable, scope)
            val maxMb = LanSyncManager.settings().syncMaxSizeMb
            val sb = StringBuilder()
            results.forEach { (id, r) ->
                val name = devices.firstOrNull { it.deviceId == id }?.displayName ?: id
                r.onSuccess { res ->
                    sb.append("$name：新增 ${res.added} 条，去重 ${res.skipped} 条\n")
                    // 接收方过滤的跳过原因逐个提示
                    if (res.skippedStoreFail > 0) {
                        sb.append("　⚠️ ${res.skippedStoreFail} 条媒体存储失败（检查存储路径权限/空间）\n")
                    }
                    if (res.skippedTooBig > 0) {
                        sb.append("　⚠️ ${res.skippedTooBig} 条超过大小上限（${maxMb}M），已跳过\n")
                    }
                    if (res.skippedType > 0) {
                        sb.append("　⚠️ ${res.skippedType} 条类型未勾选，已跳过\n")
                    }
                }
                r.onFailure {
                    val reason = when (it) {
                        is NeedPairingException -> "需要配对（点击设备输入配对码）"
                        is SharingOffException -> "对方关闭了共享"
                        is UnpairedException -> "对方已取消与你的配对（本机已自动解除配对状态）"
                        is EncryptionRequiredException -> "对方不支持加密传输（对方也需升级到新版本，或关闭本机的加密传输开关）"
                        is LanSyncManager.BlockedException -> "该设备在你的黑名单中（点上方「黑名单」可移出）"
                        is BlockedByException -> "对方已把你加入黑名单，无法操作"
                        else -> it.message ?: "连接失败"
                    }
                    sb.append("$name：失败（$reason）\n")
                }
            }
            // 多台设备结果较长，用弹窗完整显示
            MaterialAlertDialogBuilder(this@DevicesActivity)
                .setTitle("同步结果")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun confirmDeleteRemote(targets: List<LanDevice>) {
        if (targets.isEmpty()) return
        val names = targets.joinToString("、") { it.displayName }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除同步记录")
            .setMessage("将删除从「$names」同步到本机的全部记录（含媒体文件），本机原创记录不受影响。确定？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    var total = 0
                    var fileFailures = 0
                    targets.forEach {
                        val (n, f) = repo.deleteRemoteDevice(it.deviceId)
                        total += n
                        fileFailures += f
                    }
                    val msg = if (fileFailures > 0)
                        "已删除 $total 条（$fileFailures 个文件未能删除，可能已被手动移走）"
                    else "已删除 $total 条"
                    Toast.makeText(this@DevicesActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
