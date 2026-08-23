package com.clipditto.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipditto.app.App
import com.clipditto.app.R
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.clipditto.app.data.ClipType
import com.clipditto.app.ui.HistoryAdapter
import com.clipditto.app.ui.ItemActionButtons
import com.clipditto.app.ui.MainActivity
import com.clipditto.app.util.FuzzySearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 核心服务：
 * 1. 前台服务常驻，监听系统剪贴板变化并存入数据库；
 * 2. 悬浮球：单击展开/收起剪贴板列表面板，可拖动；
 * 3. 点击列表项：文字 -> 直接粘贴到当前输入框（需无障碍），媒体 -> 放回系统剪贴板。
 *
 * Android 10+ 后台无法直接读剪贴板，这里用"1px 不可见聚焦悬浮窗"临时夺取窗口焦点后再读取。
 */
class ClipboardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private lateinit var clipboard: ClipboardManager
    private lateinit var repo: ClipRepository

    private var ballView: View? = null
    private var panelView: View? = null
    private var focusTrickView: View? = null

    /** 自己往剪贴板写内容时置为 true，避免监听器再次入库 */
    @Volatile
    private var ignoreNextChange = false

    /** 暂停监听期间发生过复制 */
    @Volatile
    private var dirtyWhilePaused = false

    /** 恢复监听时的剪贴板"基线"：暂停期间复制的内容永不补录 */
    @Volatile
    private var baselineSig: String? = null

    /** 最近一次已处理的剪贴板签名：签名未变则跳过，避免重复入库/置顶。
     *  持久化存储：进程重启后也记得，删除记录后残留的剪贴板内容不会被重新捕获 */
    private var lastHandledSig: String?
        get() = getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_HANDLED_SIG, null)
        set(value) = getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_HANDLED_SIG, value).apply()

    /** 上次轮询时间（节流用） */
    @Volatile
    private var lastPollAt = 0L

    /** 上一次轮询是否读到了新内容：命中则跳过一次补读，减少焦点悬浮窗的添加/移除次数 */
    @Volatile
    private var lastPollFoundNew = false

    /**
     * 无障碍服务检测到可能的复制行为时回调。
     * 部分 ROM（如 HyperOS）不下发系统剪贴板变化回调，只能靠事件驱动轮询兜底。
     *
     * 按信号强度处理：
     * - [CopySignal.ACTION]（点复制项/复制 Toast）：复制已发生，立即读取。
     *   连续复制时每条都能被捕获；仅键盘正在弹出动画时推迟，避免打断动画
     * - [CopySignal.SELECTION]（选中文字）：剪贴板尚未写入，且操作菜单正在显示，
     *   立即抢焦点会闪屏——推迟到选区稳定后合并读取
     * - [CopySignal.WEAK_WINDOW]（窗口切换/弹窗）：可能只是弹了个输入框，
     *   键盘弹出或可见时放弃；选区活跃时推迟
     */
    private fun onPossibleClipboardCopy(signal: CopySignal) {
        if (!isMonitorEnabled()) {
            dirtyWhilePaused = true
            return
        }
        // Shizuku 通道：读剪贴板不抢窗口焦点，输入法/选区/系统弹窗全部无需避让，
        // 直接延迟读取兜底（复制事件先于剪贴板写入）
        if (ShizukuClipboard.isChannelActive()) {
            val now0 = SystemClock.uptimeMillis()
            if (now0 - lastPollAt < 350) return
            lastPollAt = now0
            handler.postDelayed({
                pollClipboard()
                handler.postDelayed({ if (!lastPollFoundNew) pollClipboard() }, 900)
            }, 300)
            return
        }
        when (signal) {
            CopySignal.SELECTION -> {
                scheduleSettledPoll()
                return
            }
            CopySignal.WEAK_WINDOW -> {
                if (PasteAccessibilityService.imeAnimatingIn() ||
                    PasteAccessibilityService.imeVisible() ||
                    PasteAccessibilityService.systemDialogRecently()
                ) {
                    Log.d(TAG, "输入法/系统弹窗激活中，跳过弱信号轮询")
                    return
                }
                if (PasteAccessibilityService.selectionRecently()) {
                    scheduleSettledPoll()
                    return
                }
            }
            CopySignal.ACTION -> {
                // 键盘正在弹出动画 / 系统弹窗（生物识别、应用锁）显示中：
                // 推迟读取，避免打断（内容不会丢，稍后补读）
                if (PasteAccessibilityService.imeAnimatingIn() ||
                    PasteAccessibilityService.systemDialogRecently()
                ) {
                    scheduleSettledPoll()
                    return
                }
                // 注意：ACTION 不因"选区活跃"推迟——点复制的瞬间菜单已关闭，
                // 连续复制时每条都要立即读取，否则中间内容会被覆盖丢失
            }
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastPollAt < 350) return // 节流：350ms 内最多触发一轮
        lastPollAt = now
        // 复制事件先于剪贴板写入，延迟读两次提高命中率；
        // 第一次已读到新内容则第二次跳过（每次读取都要添加/移除焦点悬浮窗，可能打断输入法）。
        // 延迟执行时再次检查：400ms 内键盘/系统弹窗可能刚弹出（对话框先弹、焦点后到）
        handler.postDelayed({
            if (PasteAccessibilityService.imeAnimatingIn() ||
                PasteAccessibilityService.systemDialogRecently()
            ) {
                scheduleSettledPoll()
                return@postDelayed
            }
            pollClipboard()
            handler.postDelayed({
                if (!lastPollFoundNew &&
                    !PasteAccessibilityService.imeAnimatingIn() &&
                    !PasteAccessibilityService.systemDialogRecently()
                ) {
                    pollClipboard()
                }
            }, 900)
        }, 400)
    }

    /** 选区稳定后的合并读取：是否已排队 */
    @Volatile
    private var settledPollPending = false

    /**
     * 选区活跃/键盘弹出/系统弹窗（生物识别、应用锁）期间不抢焦点，
     * 等稳定后合并读一次。到点时条件仍不满足则继续顺延。
     * 键盘稳定打开（非动画中）不阻止读取——聊天 App 里键盘常驻时复制也要能捕获。
     */
    private fun scheduleSettledPoll() {
        if (settledPollPending) return
        settledPollPending = true
        handler.postDelayed({
            settledPollPending = false
            if (PasteAccessibilityService.selectionRecently() ||
                PasteAccessibilityService.imeAnimatingIn() ||
                PasteAccessibilityService.systemDialogRecently()
            ) {
                scheduleSettledPoll() // 条件仍不满足，继续顺延
                return@postDelayed
            }
            pollClipboard()
        }, 2_100)
    }

    /** 读一次剪贴板，签名变了才走入库流程 */
    private fun pollClipboard() {
        readClipboardSafely { clip ->
            val sig = sigOf(clip)
            lastPollFoundNew = sig != null && sig != lastHandledSig
            if (lastPollFoundNew) {
                Log.d(TAG, "轮询发现新内容，入库")
                handleClip(clip)
            }
        }
    }

    /** 是否监听系统剪贴板（默认开启）；关闭后复制的内容不入库 */
    private fun isMonitorEnabled(): Boolean =
        getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(BootReceiver.KEY_MONITOR_ENABLED, true)

    private fun setMonitorEnabled(enabled: Boolean) {
        getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(BootReceiver.KEY_MONITOR_ENABLED, enabled)
            .apply()
    }

    /** 当前剪贴板内容的签名（文字或 Uri），用于识别"暂停期间复制的内容" */
    private fun sigOf(clip: ClipData?): String? {
        if (clip == null || clip.itemCount == 0) return null
        val item = clip.getItemAt(0) ?: return null
        return item.text?.toString() ?: item.uri?.toString()
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        Log.d(TAG, "监听到剪贴板变化")
        if (ignoreNextChange) {
            ignoreNextChange = false
            Log.d(TAG, "本次变化来自自身写入，跳过")
            return@OnPrimaryClipChangedListener
        }
        if (!isMonitorEnabled()) {
            dirtyWhilePaused = true
            Log.d(TAG, "监听已暂停，标记脏数据")
            return@OnPrimaryClipChangedListener
        }
        readClipboardSafely()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        repo = ClipRepository(this)
        Log.d(TAG, "服务 onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFY_ID, buildNotification())
        clipboard.addPrimaryClipChangedListener(clipListener)
        // HyperOS/MIUI 上系统剪贴板回调可能不下发，改由无障碍事件驱动轮询兜底
        PasteAccessibilityService.copyTrigger = { signal -> onPossibleClipboardCopy(signal) }
        showBall()
        Log.d(TAG, "服务已启动，剪贴板监听已注册")
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        PasteAccessibilityService.copyTrigger = null
        hideBall()
        hidePanel()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 前台通知 ----------------

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ---------------- 剪贴板读取 ----------------

    /** 上一次读取未完成时排队的读取动作（避免连续复制时事件被丢弃） */
    @Volatile
    private var pendingRead: (() -> Unit)? = null

    private fun readClipboardSafely(onRead: (ClipData?) -> Unit = { handleClip(it) }) {
        // Shizuku 通道：以 shell 身份读取，完全不抢窗口焦点，无任何界面副作用
        if (ShizukuClipboard.isChannelActive()) {
            val clip = ShizukuClipboard.readClipboard()
            // 已连接时 null 即"剪贴板为空"，直接结束；未连接（异步绑定中）才回退焦点读取
            if (clip != null || ShizukuClipboard.isBound()) {
                Log.d(TAG, "Shizuku 通道读取：${if (clip == null) "空" else "items=${clip.itemCount}"}")
                onRead(clip)
                return
            }
        }
        // Android 10 以下可直接读取；10+ 需要窗口焦点，用 1px 不可见聚焦悬浮窗取巧
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onRead(clipboard.primaryClip)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "无悬浮窗权限，无法读取")
            return
        }
        if (focusTrickView != null) {
            // 上一次读取还在进行：排队而不是丢弃，结束后紧接着再读一次。
            // 连续复制多条时，如果不排队，中间几次复制事件会被直接丢掉
            Log.d(TAG, "上一次读取未完成，本次排队")
            pendingRead = { readClipboardSafely(onRead) }
            return
        }

        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不加 NOT_FOCUSABLE：需要焦点才能读剪贴板；
            // NOT_TOUCHABLE：触摸直接穿透，1px 窗口绝不拦截下层 App 的点击
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 0f
            gravity = Gravity.TOP or Gravity.START
            // 关键：不能影响下层 App 的键盘状态，否则焦点被抢时键盘被收起、
            // 输入框可能随之隐藏，无障碍树里就找不到可编辑节点了
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        focusTrickView = view
        runCatching { wm.addView(view, params) }
        handler.postDelayed({
            val clip = clipboard.primaryClip
            Log.d(TAG, "读取结果：${if (clip == null) "null（焦点未授予？）" else "items=${clip.itemCount}"}")
            if (clip == null) {
                // 焦点可能尚未授予本窗口，稍等重试一次再放弃
                handler.postDelayed({ finishRead(onRead) }, 150)
            } else {
                onRead(clip)
                finishRead(null)
            }
        }, 200)
    }

    /** 结束一次读取：移除聚焦悬浮窗，并执行排队中的下一次读取 */
    private fun finishRead(onRead: ((ClipData?) -> Unit)?) {
        if (onRead != null) {
            val clip = clipboard.primaryClip
            Log.d(TAG, "重试读取结果：${if (clip == null) "仍为 null" else "items=${clip.itemCount}"}")
            onRead(clip)
        }
        focusTrickView?.let { runCatching { wm.removeView(it) } }
        focusTrickView = null
        val pending = pendingRead
        pendingRead = null
        pending?.invoke()
    }

    private fun handleClip(clip: ClipData?) {
        if (clip == null || clip.itemCount == 0) {
            Log.w(TAG, "handleClip：内容为空，不入库")
            return
        }
        // 与"恢复监听基线"一致：说明是暂停期间复制的内容，丢弃一次，永不补录
        val sig = sigOf(clip)
        if (sig != null) lastHandledSig = sig
        if (sig != null && sig == baselineSig) {
            baselineSig = null
            Log.d(TAG, "命中暂停基线，丢弃不补录")
            return
        }
        val item = clip.getItemAt(0) ?: return
        // 来源 App：优先用无障碍服务追踪的最后前台包名；
        // 无障碍不可用（如被 MIUI 杀掉）时回退到使用情况统计推断
        val source = guessSourcePackage()

        scope.launch {
            val text = item.text?.toString()
                ?: item.htmlText?.let { android.text.Html.fromHtml(it).toString() }
            Log.d(TAG, "handleClip：uri=${item.uri != null} text=${text?.take(20)}")

            when {
                item.uri != null -> saveUriClip(item.uri, clip.description, source)
                !text.isNullOrBlank() -> saveTextClip(text, source)
                else -> Log.w(TAG, "handleClip：既无 uri 也无 text，跳过")
            }
        }
    }

    /** 推断剪贴板内容来源 App 的包名 */
    private fun guessSourcePackage(): String? {
        PasteAccessibilityService.lastForegroundPackage
            ?.takeIf { it != packageName }
            ?.let { return it }
        // 回退：使用情况统计（需用户授予「使用情况访问」权限，未授权时返回空）
        return runCatching {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 10 * 60_000L, end)
            val ev = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if ((ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) &&
                    ev.packageName != packageName
                ) {
                    last = ev.packageName
                }
            }
            last
        }.getOrNull()
    }

    private suspend fun saveTextClip(text: String, sourceApp: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // 内容相同则不新增，把已有记录顶到最前
        val dup = repo.findDuplicateText(trimmed)
        if (dup != null) {
            Log.d(TAG, "文字去重：已有相同记录，置顶 id=${dup.id}")
            repo.touch(dup.id)
            return
        }
        val id = repo.insert(
            ClipItem(
                type = ClipType.TEXT,
                text = trimmed,
                mimeType = ClipDescription.MIMETYPE_TEXT_PLAIN,
                sourceApp = sourceApp
            )
        )
        Log.d(TAG, "文字入库成功 id=$id")
    }

    private suspend fun saveUriClip(uri: Uri, desc: ClipDescription?, sourceApp: String?) {
        val mime = desc?.let { d ->
            (0 until d.mimeTypeCount).map { d.getMimeType(it) }
                .firstOrNull { it != ClipDescription.MIMETYPE_TEXT_PLAIN }
        } ?: contentResolver.getType(uri)

        val type = when {
            mime?.startsWith("image/") == true -> ClipType.IMAGE
            mime?.startsWith("video/") == true -> ClipType.VIDEO
            mime?.startsWith("audio/") == true -> ClipType.AUDIO
            else -> ClipType.FILE
        }
        val file = repo.saveUriContent(uri, mime) ?: run {
            Log.w(TAG, "媒体内容保存失败 uri=$uri mime=$mime")
            return
        }
        // 内容相同（类型+大小一致）则不新增：删掉新文件，把已有记录顶到最前
        val dup = repo.findDuplicateMedia(type, file)
        if (dup != null) {
            Log.d(TAG, "媒体去重：已有相同记录，置顶 id=${dup.id}")
            file.delete()
            repo.touch(dup.id)
            return
        }
        val id = repo.insert(
            ClipItem(
                type = type,
                text = uri.lastPathSegment ?: file.name,
                filePath = file.absolutePath,
                mimeType = mime,
                sourceApp = sourceApp
            )
        )
        Log.d(TAG, "媒体入库成功 id=$id type=$type")
    }

    // ---------------- 悬浮球 ----------------

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showBall() {
        if (ballView != null || !Settings.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_ball, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (Math.abs(dx) > 12 || Math.abs(dy) > 12)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }

        ballView = view
        ballParams = params
        runCatching { wm.addView(view, params) }
    }

    private var ballParams: WindowManager.LayoutParams? = null

    /** 面板弹出后把悬浮球重新置顶（同类型窗口后添加的在上层，不移除重加球会被面板盖住） */
    private fun bringBallToFront() {
        val view = ballView ?: return
        val params = ballParams ?: return
        runCatching {
            wm.removeView(view)
            wm.addView(view, params)
        }
    }

    private fun hideBall() {
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        ballParams = null
    }

    // ---------------- 悬浮面板 ----------------

    private fun togglePanel() {
        if (panelView != null) hidePanel() else showPanel()
    }

    @SuppressLint("InflateParams")
    private fun showPanel() {
        if (panelView != null || !Settings.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_floating_panel, null)

        val dm = resources.displayMetrics
        val prefs = getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
        val defWidth = (dm.widthPixels * 0.92f).toInt()
        val defListH = (360 * dm.density).toInt()
        val savedW = prefs.getInt("panel_w", defWidth).coerceIn(dm.widthPixels / 2, dm.widthPixels)
        val savedH = prefs.getInt("panel_h", defListH).coerceIn((200 * dm.density).toInt(), (dm.heightPixels * 0.75f).toInt())
        val savedX = prefs.getInt("panel_x", (dm.widthPixels - savedW) / 2)
        val savedY = prefs.getInt("panel_y", (dm.heightPixels * 0.15f).toInt())

        val params = WindowManager.LayoutParams(
            savedW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 不抢焦点：光标留在下层 App 的输入框里，点列表项直接粘贴到光标处；
            // 不拦外部触摸（外部点击正常落到下层 App）；
            // 收起面板统一由悬浮球单击 / 「收起」按钮控制，
            // 不用 WATCH_OUTSIDE_TOUCH，避免点悬浮球时先触发"外部触摸收起"又被重新打开
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        val recycler = view.findViewById<RecyclerView>(R.id.panelRecycler)
        val tvEmpty = view.findViewById<TextView>(R.id.tvPanelEmpty)
        recycler.layoutManager = LinearLayoutManager(this)
        // 应用记住的列表高度
        recycler.layoutParams.height = savedH

        // ---- 标题栏拖动：移动面板位置 ----
        val header = view.findViewById<View>(R.id.panelHeader)
        header.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = params.x; startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit().putInt("panel_x", params.x).putInt("panel_y", params.y).apply()
                        return true
                    }
                }
                return false
            }
        })

        // ---- 右下角手柄拖动：调整面板宽度和列表高度 ----
        val resizeHandle = view.findViewById<View>(R.id.panelResize)
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startW = 0; var startH = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startW = params.width; startH = recycler.layoutParams.height
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.width = (startW + (event.rawX - downX).toInt())
                            .coerceIn(dm.widthPixels / 2, dm.widthPixels)
                        recycler.layoutParams.height = (startH + (event.rawY - downY).toInt())
                            .coerceIn((200 * dm.density).toInt(), (dm.heightPixels * 0.75f).toInt())
                        recycler.requestLayout()
                        runCatching { wm.updateViewLayout(view, params) }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        prefs.edit()
                            .putInt("panel_w", params.width)
                            .putInt("panel_h", recycler.layoutParams.height)
                            .apply()
                        return true
                    }
                }
                return false
            }
        })
        val adapter = HistoryAdapter(
            onClick = { item -> onItemPicked(item) },
            onLongClick = { item -> showPanelItemMenu(item) },
            // 点「打开」跳浏览器后自动收起面板
            onOpenUrl = { hidePanel() }
        )
        recycler.adapter = adapter
        panelAdapter = adapter
        panelEmptyView = tvEmpty

        view.findViewById<Button>(R.id.btnPanelClose).setOnClickListener { hidePanel() }
        view.findViewById<Button>(R.id.btnPanelApp).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        // 监听开关：暂停后复制的内容不入库
        val btnMonitor = view.findViewById<Button>(R.id.btnPanelMonitor)
        fun refreshMonitorBtn() {
            btnMonitor.text = if (isMonitorEnabled()) "暂停" else "恢复"
        }
        refreshMonitorBtn()
        btnMonitor.setOnClickListener {
            val now = !isMonitorEnabled()
            setMonitorEnabled(now)
            refreshMonitorBtn()
            if (now) {
                // 恢复监听：把当前剪贴板设为基线，暂停期间复制的内容直接丢弃，不会补录
                // （面板默认不持焦点，走 1px 聚焦悬浮窗读取）
                readClipboardSafely { clip ->
                    baselineSig = sigOf(clip)
                    dirtyWhilePaused = false
                }
            }
            Toast.makeText(
                this,
                if (now) "已恢复剪贴板监听" else "已暂停监听，复制内容将不会记录",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 模糊搜索框：面板默认不持焦点，点搜索框时临时切换为可聚焦以弹出键盘
        panelQuery = ""
        val etSearch = view.findViewById<EditText>(R.id.etPanelSearch)
        etSearch.setOnTouchListener { v, _ ->
            makePanelFocusable(true)
            v.post {
                v.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            }
            false   // 不消费事件，EditText 正常定位光标
        }
        etSearch.addTextChangedListener { text ->
            panelQuery = text?.toString() ?: ""
            applyPanelFilter()
        }

        panelView = view
        panelParams = params
        runCatching { wm.addView(view, params) }
        // 保证悬浮球永远在面板之上
        bringBallToFront()
        // 面板打开后主动读一次剪贴板（走 1px 聚焦悬浮窗），把监听可能漏掉的内容补进来；
        // 暂停监听时不读取；暂停期间复制过内容则把当前剪贴板设为基线，不补录
        handler.postDelayed({
            if (panelView != null && isMonitorEnabled()) {
                if (dirtyWhilePaused) {
                    readClipboardSafely { clip ->
                        dirtyWhilePaused = false
                        baselineSig = sigOf(clip)
                    }
                } else {
                    ignoreNextChange = false
                    // 与轮询共用签名闸门：已处理过的内容（即使记录被删）不再重复入库
                    pollClipboard()
                }
            }
        }, 300)
        // 面板打开期间实时订阅数据库：复制入库 / 去重置顶 / 同步写入都会立即刷新列表
        panelFlowJob?.cancel()
        panelFlowJob = scope.launch {
            repo.clips.collectLatest { list ->
                panelFullList = list
                applyPanelFilter()
            }
        }
    }

    private var panelParams: WindowManager.LayoutParams? = null

    /** 切换面板是否持有窗口焦点（搜索框输入时需要焦点弹出键盘，其余时间不抢焦点） */
    private fun makePanelFocusable(focusable: Boolean) {
        val view = panelView ?: return
        val params = panelParams ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private var panelAdapter: HistoryAdapter? = null
    private var panelEmptyView: TextView? = null
    private var panelFullList: List<ClipItem> = emptyList()
    private var panelQuery: String = ""

    /** 面板打开期间订阅数据库变化：新复制/去重置顶/局域网同步写入都实时刷新列表 */
    private var panelFlowJob: Job? = null

    private fun refreshPanel() {
        scope.launch {
            panelFullList = repo.getAll()
            applyPanelFilter()
        }
    }

    /** 按搜索词过滤面板列表（模糊匹配） */
    private fun applyPanelFilter() {
        val filtered = panelFullList.filter { FuzzySearch.matches(panelQuery, it.text) }
        panelAdapter?.submit(filtered)
        panelEmptyView?.apply {
            text = if (panelQuery.isBlank()) "暂无记录" else "没有匹配「$panelQuery」的记录"
            visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun hidePanel() {
        panelFlowJob?.cancel()
        panelFlowJob = null
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelParams = null
        panelAdapter = null
        panelEmptyView = null
    }

    // ---------------- 点击记录 -> 粘贴 ----------------

    private fun onItemPicked(item: ClipItem) {
        when (item.type) {
            ClipType.TEXT -> pasteText(item.text ?: return)
            else -> copyMediaToClipboard(item)
        }
    }

    /** 文字：写入系统剪贴板后，通过无障碍服务直接粘贴到下层输入框的光标处。
     *  面板保持打开、不抢焦点，可连续点选多条记录连续粘贴。
     *  直贴失败时自动降级：收起面板让下层 App 恢复焦点，再重试一次。 */
    private fun pasteText(text: String) {
        ignoreNextChange = true
        clipboard.setPrimaryClip(ClipData.newPlainText("clipditto", text))
        // 若正在搜索（面板持有焦点），先恢复不聚焦，让下层输入框重新获得光标
        makePanelFocusable(false)
        handler.postDelayed({
            val err = PasteAccessibilityService.pasteIntoFocusedInput(text)
            if (err != null && panelView != null) {
                // 降级：面板可能遮挡/影响了窗口树读取，收起面板等焦点回归后重试
                hidePanel()
                handler.postDelayed({
                    val err2 = PasteAccessibilityService.pasteIntoFocusedInput(text)
                    if (err2 != null) {
                        showPasteFailure(err, err2)
                    }
                }, 800)
            } else if (err != null) {
                showPasteFailure(err, null)
            }
        }, 150)
    }

    /** 粘贴失败的提示：微信等被系统保护的应用给出专门引导，其余显示诊断信息 */
    private fun showPasteFailure(err1: String, err2: String?) {
        val target = PasteAccessibilityService.lastForegroundPackage
        if (target == "com.tencent.mm") {
            // MIUI/HyperOS 对微信窗口内容做了无障碍读取保护，自动粘贴无解，降级引导
            Toast.makeText(
                this,
                "微信限制自动粘贴，已复制到剪贴板，请长按输入框选择「粘贴」",
                Toast.LENGTH_LONG
            ).show()
        } else {
            showDebugDialog(
                "自动粘贴失败",
                "已复制到系统剪贴板\n\n失败原因：\n${err2 ?: err1}"
            )
        }
    }

    /** 以服务悬浮窗形式弹窗（Toast 显示不下长文本时使用） */
    private fun showDebugDialog(title: String, message: String) {
        runCatching {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }.onFailure {
            Toast.makeText(this, "$title：$message", Toast.LENGTH_LONG).show()
        }
    }

    /** 面板里长按记录：详情 / 收藏置顶 / 删除 菜单 */
    private fun showPanelItemMenu(item: ClipItem) {
        val favLabel = if (item.favorite) "取消收藏" else "★ 收藏置顶"
        runCatching {
            val dialog = android.app.AlertDialog.Builder(this)
                .setItems(arrayOf("查看详情", favLabel, "删除")) { _, which ->
                    if (which == 0) {
                        showPanelItemDetail(item)
                        return@setItems
                    }
                    scope.launch {
                        when (which) {
                            1 -> {
                                repo.toggleFavorite(item)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@ClipboardService,
                                        if (item.favorite) "已取消收藏" else "已收藏置顶",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            2 -> {
                                repo.delete(item)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ClipboardService, "已删除", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        refreshPanel()
                    }
                }
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        }
    }

    /** 面板详情弹窗：显示完整内容（悬浮窗形式，长文本内部滚动、可选择复制），
     *  底部固定「粘贴」+ 与列表项一致的动作按钮（打开平台/浏览器） */
    private fun showPanelItemDetail(item: ClipItem) {
        runCatching {
            val view = LayoutInflater.from(this).inflate(R.layout.dialog_detail, null)
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

            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("详情")
                .setView(view)
                .setNegativeButton("关闭", null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)

            // 动作按钮：与列表项完全一致的识别和跳转逻辑，跳转后收起面板
            ItemActionButtons.bind(
                view.findViewById(R.id.btnDetailOpenUrl),
                view.findViewById(R.id.btnDetailOpenAlt),
                view.findViewById(R.id.btnDetailOpenBrowser),
                item
            ) {
                dialog.dismiss()
                hidePanel()
            }

            // 粘贴 = 点击列表项：文字直贴下层输入框 / 媒体放回系统剪贴板
            // 详情里操作完后关闭所有弹窗（详情弹窗 + 列表面板）
            view.findViewById<Button>(R.id.btnDetailPaste).setOnClickListener {
                dialog.dismiss()
                hidePanel()
                onItemPicked(item)
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
    }

    /** 图片 / 文件 / 视频：通过 FileProvider 放回系统剪贴板 */
    private fun copyMediaToClipboard(item: ClipItem) {
        val path = item.filePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "文件已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        ignoreNextChange = true
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, item.text ?: file.name, uri))
        makePanelFocusable(false)
        Toast.makeText(this, "已复制，请到目标位置粘贴", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_STOP = "com.clipditto.app.action.STOP"
        private const val NOTIFY_ID = 1001
        private const val TAG = "ClipDitto"
        private const val KEY_LAST_HANDLED_SIG = "last_handled_sig"

        var instance: ClipboardService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, ClipboardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ClipboardService::class.java).setAction(ACTION_STOP))
        }
    }
}
