package com.clipditto.app.ui

import android.app.AlertDialog
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
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.R
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.sync.LanDevice
import com.clipditto.app.sync.LanSyncManager
import com.clipditto.app.sync.NeedPairingException
import com.clipditto.app.sync.SharingOffException
import com.clipditto.app.sync.UnpairedException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)

        repo = ClipRepository(this)
        LanSyncManager.init(this)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

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

        bindSwitches()
        bindBottomBar()

        findViewById<Button>(R.id.btnRescan).setOnClickListener {
            LanSyncManager.discovery.restartDiscovery()
            Toast.makeText(this, "正在重新扫描局域网…", Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.tvPort).setOnClickListener { showPortDialog() }
        findViewById<TextView>(R.id.tvMyName).setOnClickListener { showRenameDialog() }

        findViewById<Button>(R.id.btnAddIp).setOnClickListener { showAddIpDialog() }
        findViewById<Button>(R.id.btnSweep).setOnClickListener {
            if (LanSyncManager.discovery.sweeping.value) {
                Toast.makeText(this, "深度扫描进行中…", Toast.LENGTH_SHORT).show()
            } else {
                LanSyncManager.discovery.sweepSubnet()
                Toast.makeText(this, "正在遍历本网段所有地址（约 10 秒）…", Toast.LENGTH_SHORT).show()
            }
        }

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
        AlertDialog.Builder(this)
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
        findViewById<TextView>(R.id.tvDiag).text = "$line1\n$line2"
        findViewById<TextView>(R.id.tvPort).text =
            "服务端口：${s.serverPort}（点击修改，需两台设备保持一致）"
        findViewById<TextView>(R.id.tvMyName).text =
            "本机名称：${s.deviceName}（点击修改，对方列表里显示此名）"
    }

    // ---------------- 本机名称 ----------------

    private fun showRenameDialog(prefill: String? = null, onSaved: (() -> Unit)? = null) {
        val s = LanSyncManager.settings()
        val input = EditText(this).apply {
            setText(prefill ?: s.deviceName)
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
                if (name.length > MAX_NAME_LENGTH) {
                    Toast.makeText(this, "名称不能超过 $MAX_NAME_LENGTH 个字", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                if (name != s.deviceName) {
                    s.deviceName = name
                    LanSyncManager.refreshName()
                }
                refreshDiag()
                onSaved?.invoke()
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
            setSelection(inputTextLength())
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
                refreshDiag()
                Toast.makeText(this, "端口已改为 $port，服务已重启", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认 8765") { _, _ ->
                s.serverPort = 8765
                LanSyncManager.restartServer()
                refreshDiag()
            }
            .show()
    }

    private fun EditText.inputTextLength() = text.length

    companion object {
        /** 设备名称长度上限 */
        private const val MAX_NAME_LENGTH = 20
    }

    override fun onResume() {
        super.onResume()
        LanSyncManager.discoveryScreenActive = true
        refreshSwitches()
        // 注册配对确认弹窗：其他设备请求配对时在此页面弹出同意/拒绝
        LanSyncManager.pairApprovalUiHandler = { requester -> askPairApproval(requester) }
    }

    override fun onPause() {
        super.onPause()
        LanSyncManager.discoveryScreenActive = false
        LanSyncManager.pairApprovalUiHandler = null
    }

    /** 配对确认弹窗：在 HTTP 服务线程上被调用，阻塞等待用户选择（最多 30 秒） */
    private fun askPairApproval(requester: LanDevice): Boolean {
        if (isFinishing || isDestroyed) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val approved = java.util.concurrent.atomic.AtomicBoolean(false)
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@runOnUiThread
            }
            val modelSuffix = requester.model?.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""
            AlertDialog.Builder(this)
                .setTitle("配对请求")
                .setMessage("「${requester.displayName}$modelSuffix」请求与本机配对，配对后可以访问你共享的剪贴板内容。\n\n是否同意？")
                .setCancelable(false)
                .setPositiveButton("同意配对") { _, _ ->
                    approved.set(true)
                    latch.countDown()
                }
                .setNegativeButton("拒绝") { _, _ -> latch.countDown() }
                .show()
        }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return approved.get()
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
    }

    private fun refreshSwitches() {
        val s = LanSyncManager.settings()
        findViewById<SwitchCompat>(R.id.swDiscoverable).isChecked = s.discoverable
        findViewById<SwitchCompat>(R.id.swSharing).isChecked = s.sharing
        findViewById<SwitchCompat>(R.id.swAutoSync).isChecked = s.autoSync
        findViewById<SwitchCompat>(R.id.swAutoAccept).isChecked = s.autoAcceptPair
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

    // ---------------- 列表与多选 ----------------

    private fun refreshList() {
        adapter.submit(devices, selected.toSet(), syncing)
        findViewById<TextView>(R.id.tvEmpty).visibility =
            if (devices.isEmpty()) View.VISIBLE else View.GONE
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
            !d.online -> Toast.makeText(this, "设备不在线", Toast.LENGTH_SHORT).show()
            !d.paired -> showPairDialog(d)
            else -> showDeviceMenu(d)
        }
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
        AlertDialog.Builder(this)
            .setTitle("配对「${d.displayName}」")
            .setMessage("请在对方设备上打开「共享剪贴板」，屏幕上会显示配对码")
            .setView(container)
            .setPositiveButton("配对") { _, _ ->
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
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val err = LanSyncManager.pair(d, token)
                    val msg = when (err) {
                        null -> "配对成功"
                        is LanSyncManager.PairError.BadToken ->
                            "配对码错误\n\n请核对对方设备屏幕上显示的 6 位配对码（不是本机的码）"
                        is LanSyncManager.PairError.SharingOff ->
                            "对方未开启「共享本机剪贴板」\n\n请在对方设备上打开该开关"
                        is LanSyncManager.PairError.Offline ->
                            "设备不在线\n\n请点「重新扫描」后重试"
                        is LanSyncManager.PairError.Rejected ->
                            "对方拒绝了本次配对"
                        is LanSyncManager.PairError.NeedConfirm ->
                            "等待对方确认超时\n\n请让对方打开「设备页」后重试，或让对方开启「自动同意配对请求」"
                        is LanSyncManager.PairError.ConnectFail ->
                            "连不上对方\n\n${err.detail}"
                    }
                    if (err == null) {
                        Toast.makeText(this@DevicesActivity, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        // 失败详情可能较长，用弹窗完整显示
                        AlertDialog.Builder(this@DevicesActivity)
                            .setTitle("配对失败")
                            .setMessage(msg)
                            .setPositiveButton("知道了", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeviceMenu(d: LanDevice) {
        AlertDialog.Builder(this)
            .setTitle(d.displayName)
            .setItems(arrayOf("立即同步", "删除该设备同步来的记录", "取消配对")) { _, which ->
                when (which) {
                    0 -> syncDevices(listOf(d))
                    1 -> confirmDeleteRemote(listOf(d))
                    2 -> LanSyncManager.unpair(d.deviceId)
                }
            }
            .show()
    }

    // ---------------- 批量操作 ----------------

    private fun bindBottomBar() {
        findViewById<Button>(R.id.btnSyncSelected).setOnClickListener {
            val targets = devices.filter { it.deviceId in selected }
            syncDevices(targets)
        }
        findViewById<Button>(R.id.btnDeleteSelected).setOnClickListener {
            confirmDeleteRemote(devices.filter { it.deviceId in selected })
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
        lifecycleScope.launch {
            val results = LanSyncManager.syncDevices(actionable)
            val sb = StringBuilder()
            results.forEach { (id, r) ->
                val name = devices.firstOrNull { it.deviceId == id }?.displayName ?: id
                r.onSuccess { sb.append("$name：新增 ${it.added} 条，去重 ${it.skipped} 条\n") }
                r.onFailure {
                    val reason = when (it) {
                        is NeedPairingException -> "需要配对（点击设备输入配对码）"
                        is SharingOffException -> "对方关闭了共享"
                        is UnpairedException -> "对方已取消与你的配对（本机已自动解除配对状态）"
                        else -> it.message ?: "连接失败"
                    }
                    sb.append("$name：失败（$reason）\n")
                }
            }
            // 多台设备结果较长，用弹窗完整显示
            AlertDialog.Builder(this@DevicesActivity)
                .setTitle("同步结果")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    private fun confirmDeleteRemote(targets: List<LanDevice>) {
        if (targets.isEmpty()) return
        val names = targets.joinToString("、") { it.displayName }
        AlertDialog.Builder(this)
            .setTitle("删除同步记录")
            .setMessage("将删除从「$names」同步到本机的全部记录（含媒体文件），本机原创记录不受影响。确定？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    var total = 0
                    targets.forEach { total += repo.deleteRemoteDevice(it.deviceId) }
                    Toast.makeText(this@DevicesActivity, "已删除 $total 条", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
