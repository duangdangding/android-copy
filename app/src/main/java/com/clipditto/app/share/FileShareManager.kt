package com.clipditto.app.share

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipditto.app.App
import com.clipditto.app.R
import com.clipditto.app.data.ClipDatabase
import com.clipditto.app.data.TransferRecord
import com.clipditto.app.sync.BatchFile
import com.clipditto.app.sync.FsReceiveHandler
import com.clipditto.app.sync.LanSettings
import com.clipditto.app.sync.LanSyncManager
import com.clipditto.app.util.MediaFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次待确认的传入文件：body 已由 LanSyncServer 完整落到临时文件，
 * 正在等待用户确认（或自动接收）。id 用于待接收列表/通知按钮回传。
 */
class PendingTransfer(
    val id: String,
    val fromDeviceId: String?,
    val fromDeviceName: String,
    val fileName: String,
    val fileSize: Long,
    val mime: String?
)

/**
 * 一次待确认的批量文件清单：发送方先交文件名/大小列表，
 * 用户在待接收列表逐个勾选（后台通知只能整批接收/拒绝）。id 用于列表/通知按钮回传。
 */
class PendingBatch(
    val id: String,
    val fromDeviceId: String?,
    val fromDeviceName: String,
    val files: List<BatchFile>
)

/**
 * 文件共享总控：接收能力常驻——初始化后就把接收处理器注册进 LanSyncServer 的
 * POST /fs/send 路由（传输服务与局域网同步共用端口，由 LanSyncManager 常驻运行），
 * 并持续广播 beacon 让其他设备能发现本机。是否手动确认由「自动接收」开关决定。
 *
 * 设备 id / 名称 / 存储目录沿用局域网同步的设置（LanSettings），不另开配置项。
 */
object FileShareManager {

    private const val TAG = "FileShareManager"

    /** 手动确认等待上限（秒），超时按拒收处理 */
    private const val CONFIRM_TIMEOUT_SEC = 30L

    /** 「本批都接收」临时免确认时长（毫秒，滑动续期：每自动接收一个文件重新计时） */
    private const val TRUST_BATCH_MS = 10 * 60 * 1000L

    /** 批量清单确认等待上限（秒）：用户要逐个勾选文件，给足两分钟 */
    private const val BATCH_CONFIRM_TIMEOUT_SEC = 120L

    /** 已勾选批次的保留时长（毫秒）：清单确认后文件传输最长可间隔这么久 */
    private const val BATCH_KEEP_MS = 30 * 60 * 1000L

    /** 接收请求通知 id 起始段（避开同步结果通知的 1002） */
    private const val NOTIFY_BASE = 3100

    private lateinit var appContext: Context
    private lateinit var lanSettings: LanSettings
    lateinit var settings: FileShareSettings
        private set
    lateinit var discovery: FileShareDiscovery
        private set
    private var initialized = false

    /** 等待确认的传输：transferId → 等待句柄（fromDeviceId 供「本批都接收」临时免确认） */
    private data class Waiter(
        val latch: CountDownLatch,
        val result: AtomicBoolean,
        val notifyId: Int,
        val fromDeviceId: String?
    )

    private val pending = ConcurrentHashMap<String, Waiter>()
    private val notifySeq = AtomicInteger(0)

    /** 「本批都接收」临时免确认：deviceId → 最近一次接收时刻（毫秒，10 分钟滑动有效） */
    private val trustedSenders = ConcurrentHashMap<String, Long>()

    /** 等待确认的批量清单：请求 id → 等待句柄（result 为勾选的文件序号；null = 尚未答复） */
    private data class BatchWaiter(
        val latch: CountDownLatch,
        val result: AtomicReference<List<Int>?>,
        val fileCount: Int,
        val notifyId: Int
    )

    private val pendingBatches = ConcurrentHashMap<String, BatchWaiter>()

    /** 已勾选的批量清单：batchId →（登记时刻，剩余待收文件） */
    private val batchAccepts = ConcurrentHashMap<String, Pair<Long, MutableList<BatchFile>>>()

    /** 文件共享页在前台时注册的确认界面处理器（待接收列表；在 HTTP 服务线程上被调用，不得阻塞） */
    @Volatile
    var confirmUiHandler: ((PendingTransfer) -> Unit)? = null

    /** 文件共享页在前台时注册的批量清单确认处理器（待接收列表逐个勾选；同上不得阻塞） */
    @Volatile
    var batchUiHandler: ((PendingBatch) -> Unit)? = null

    /** 注册进 LanSyncServer 的文件共享处理器（/fs/send 路由回调）；接收常驻，isEnabled 恒为 true */
    private val fsHandler = object : FsReceiveHandler {
        override fun isEnabled(): Boolean = true

        override fun awaitAccept(
            fileName: String, fileSize: Long, mime: String?,
            fromDeviceId: String?, fromDeviceName: String
        ): Boolean =
            this@FileShareManager.awaitAccept(
                fileName, fileSize, mime, fromDeviceId, fromDeviceName
            )

        override fun awaitBatchAccept(
            batchId: String, files: List<BatchFile>,
            fromDeviceId: String?, fromDeviceName: String
        ): List<Int>? = this@FileShareManager.awaitBatchAccept(
            batchId, files, fromDeviceId, fromDeviceName
        )

        override fun consumeBatchAccept(batchId: String, fileName: String, fileSize: Long): Boolean =
            this@FileShareManager.consumeBatchAccept(batchId, fileName, fileSize)

        override fun persist(
            fileName: String, mime: String?,
            fromDeviceId: String?, fromDeviceName: String,
            tmpFile: File
        ): String? = persistReceived(fileName, mime, fromDeviceId, fromDeviceName, tmpFile)
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        settings = FileShareSettings(appContext)
        // 设备信息（id/名称/型号）与文件存储目录沿用局域网同步设置
        LanSyncManager.init(context)
        lanSettings = LanSyncManager.settings()
        // 信标里的 port 必须跟传输服务的实际监听端口一致
        // （含 LanSyncServer 的 ServerSocket(0) 回退），每次发 beacon 时实时读取
        discovery = FileShareDiscovery(appContext, lanSettings) { LanSyncManager.serverPort }
        discovery.start()  // 监听通道常开：始终能看到在线设备
        // 接收常驻：注册处理器、确保传输服务运行、持续广播本机
        LanSyncManager.setFsHandler(fsHandler)
        LanSyncManager.applyState()
        discovery.startBeaconSender()
    }

    // ---------------- 接收确认 ----------------

    /**
     * 决定是否接收一份已落到临时文件的传入文件（在 HTTP 服务线程上调用，可阻塞）。
     * 自动接收 → 直接通过；页面在前台 → 待接收列表确认；否则 → 通知栏「接收/拒绝」。
     * 最多等待 [CONFIRM_TIMEOUT_SEC] 秒，超时按拒收处理。
     */
    private fun awaitAccept(
        fileName: String, fileSize: Long, mime: String?,
        fromDeviceId: String?, fromDeviceName: String
    ): Boolean {
        if (settings.autoReceive) return true
        // 「本批都接收」临时免确认：同一设备 10 分钟内的后续文件直接收（每收一个重新计时）
        if (fromDeviceId != null) {
            val now = System.currentTimeMillis()
            val last = trustedSenders[fromDeviceId]
            if (last != null && now - last <= TRUST_BATCH_MS) {
                trustedSenders[fromDeviceId] = now
                return true
            }
            if (last != null) trustedSenders.remove(fromDeviceId)
        }
        val p = PendingTransfer(
            id = UUID.randomUUID().toString(),
            fromDeviceId = fromDeviceId,
            fromDeviceName = fromDeviceName,
            fileName = fileName,
            fileSize = fileSize,
            mime = mime
        )
        val handler = confirmUiHandler
        if (handler != null) {
            // 前台列表路径：不需要通知
            val w = Waiter(CountDownLatch(1), AtomicBoolean(false), 0, fromDeviceId)
            pending[p.id] = w
            return try {
                // 列表展示失败（页面已销毁等）时按拒收处理
                runCatching { handler(p) }.onFailure { resolvePending(p.id, false) }
                w.latch.await(CONFIRM_TIMEOUT_SEC, TimeUnit.SECONDS) && w.result.get()
            } finally {
                pending.remove(p.id)
            }
        }
        // 后台通知路径：无通知权限时无法询问用户，直接拒收（不让对方干等 30 秒）
        if (!canNotify()) {
            Log.w(TAG, "无通知权限，无法询问用户，拒收: ${p.fileName}")
            return false
        }
        val w = Waiter(
            CountDownLatch(1), AtomicBoolean(false),
            NOTIFY_BASE + notifySeq.incrementAndGet() % 1000, fromDeviceId
        )
        pending[p.id] = w
        return try {
            postRequestNotification(p, w.notifyId)
            w.latch.await(CONFIRM_TIMEOUT_SEC, TimeUnit.SECONDS) && w.result.get()
        } finally {
            pending.remove(p.id)
            cancelNotification(w.notifyId)
        }
    }

    /** 待接收列表/通知按钮回调：解出对应传输的等待锁 */
    fun resolvePending(transferId: String, accept: Boolean) {
        pending[transferId]?.let {
            it.result.set(accept)
            it.latch.countDown()
        }
    }

    /** 「本批都接收」：接收该请求，并临时免确认同一设备 10 分钟内的后续文件 */
    fun resolvePendingAndTrust(transferId: String) {
        pending[transferId]?.let {
            it.fromDeviceId?.let { id -> trustedSenders[id] = System.currentTimeMillis() }
            it.result.set(true)
            it.latch.countDown()
        }
    }

    // ---------------- 批量清单确认 ----------------

    /**
     * 批量清单确认（在 HTTP 服务线程上调用，可阻塞最多 [BATCH_CONFIRM_TIMEOUT_SEC] 秒）：
     * 自动接收/「本批都接收」临时免确认 → 整批通过；页面在前台 → 待接收列表逐个勾选；
     * 否则 → 通知栏整批「全部接收/拒绝」。返回勾选的文件序号；超时返回 null。
     * 勾选结果登记进 [batchAccepts]，后续带 X-Batch-Id 的文件免确认。
     */
    fun awaitBatchAccept(
        batchId: String, files: List<BatchFile>,
        fromDeviceId: String?, fromDeviceName: String
    ): List<Int>? {
        val accepted: List<Int>? = when {
            settings.autoReceive -> files.indices.toList()
            isTrustedNow(fromDeviceId) -> files.indices.toList()
            else -> askBatchAccept(files, fromDeviceId, fromDeviceName)
        }
        if (!accepted.isNullOrEmpty()) {
            batchAccepts[batchId] =
                System.currentTimeMillis() to accepted.map { files[it] }.toMutableList()
        }
        return accepted
    }

    /** 「本批都接收」临时免确认检查（命中则续期） */
    private fun isTrustedNow(fromDeviceId: String?): Boolean {
        if (fromDeviceId == null) return false
        val now = System.currentTimeMillis()
        val last = trustedSenders[fromDeviceId] ?: return false
        return if (now - last <= TRUST_BATCH_MS) {
            trustedSenders[fromDeviceId] = now
            true
        } else {
            trustedSenders.remove(fromDeviceId)
            false
        }
    }

    private fun askBatchAccept(
        files: List<BatchFile>,
        fromDeviceId: String?, fromDeviceName: String
    ): List<Int>? {
        val p = PendingBatch(UUID.randomUUID().toString(), fromDeviceId, fromDeviceName, files)
        val handler = batchUiHandler
        if (handler != null) {
            // 前台列表路径：逐个勾选
            val w = BatchWaiter(CountDownLatch(1), AtomicReference(null), files.size, 0)
            pendingBatches[p.id] = w
            return try {
                // 列表展示失败（页面已销毁等）时按全部拒绝处理
                runCatching { handler(p) }.onFailure { resolvePendingBatch(p.id, emptyList()) }
                if (w.latch.await(BATCH_CONFIRM_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    w.result.get()
                } else {
                    null
                }
            } finally {
                pendingBatches.remove(p.id)
            }
        }
        // 后台通知路径：只能整批接收/拒绝；无通知权限时直接拒批（不让对方干等两分钟）
        if (!canNotify()) {
            Log.w(TAG, "无通知权限，无法询问用户，拒批: ${files.size} 个文件来自 $fromDeviceName")
            return emptyList()
        }
        val w = BatchWaiter(
            CountDownLatch(1), AtomicReference(null), files.size,
            NOTIFY_BASE + notifySeq.incrementAndGet() % 1000
        )
        pendingBatches[p.id] = w
        return try {
            postBatchRequestNotification(p, w.notifyId)
            if (w.latch.await(BATCH_CONFIRM_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                w.result.get()
            } else {
                null
            }
        } finally {
            pendingBatches.remove(p.id)
            cancelNotification(w.notifyId)
        }
    }

    /** 待接收列表回调：提交勾选的文件序号（空列表 = 全部拒绝） */
    fun resolvePendingBatch(transferId: String, accepted: List<Int>) {
        pendingBatches[transferId]?.let {
            it.result.set(accepted)
            it.latch.countDown()
        }
    }

    /** 通知按钮回调：批量请求整批接收/拒绝 */
    fun resolvePendingBatchAll(transferId: String, accept: Boolean) {
        pendingBatches[transferId]?.let { w ->
            w.result.set(if (accept) (0 until w.fileCount).toList() else emptyList())
            w.latch.countDown()
        }
    }

    /** 通知按钮统一入口：单文件与批量请求按 id 分发 */
    fun resolveAny(transferId: String, accept: Boolean) {
        if (pending.containsKey(transferId)) resolvePending(transferId, accept)
        else resolvePendingBatchAll(transferId, accept)
    }

    /** 批量清单命中：该文件在已勾选批次内则消耗一条并返回 true（免确认）；批次清空/过期后移除 */
    fun consumeBatchAccept(batchId: String, fileName: String, fileSize: Long): Boolean {
        val (created, remaining) = batchAccepts[batchId] ?: return false
        if (System.currentTimeMillis() - created > BATCH_KEEP_MS) {
            batchAccepts.remove(batchId)
            return false
        }
        synchronized(remaining) {
            val it = remaining.iterator()
            while (it.hasNext()) {
                val f = it.next()
                if (f.name == fileName && f.size == fileSize) {
                    it.remove()
                    if (remaining.isEmpty()) batchAccepts.remove(batchId)
                    return true
                }
            }
        }
        return false
    }

    // ---------------- 落盘入库 ----------------

    /**
     * 接收成功后：按局域网同步共用的存储设置落盘（SAF 目录树或默认 Download/ClipDitto），
     * 并写入一条传送记录。返回保存路径；失败返回 null。
     */
    private fun persistReceived(
        fileName: String, mime: String?,
        fromDeviceId: String?, fromDeviceName: String,
        tmpFile: File
    ): String? {
        val stored = lanSettings.syncDirUri?.let { tree ->
            MediaFiles.writeToTree(appContext, tree, fileName, mime, tmpFile)
        } ?: MediaFiles.writeToDefaultDir(appContext, fileName, mime, tmpFile)
            ?: return null
        runBlocking {
            runCatching {
                ClipDatabase.get(appContext).transferDao().insert(
                    TransferRecord(
                        fileName = fileName,
                        savedPath = stored,
                        fileSize = tmpFile.length(),
                        mimeType = mime,
                        fromDeviceId = fromDeviceId,
                        fromDeviceName = fromDeviceName
                    )
                )
            }.onFailure { Log.w(TAG, "传送记录入库失败: ${it.message}") }
        }
        return stored
    }

    // ---------------- 通知 ----------------

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 「文件接收请求」通知：带 接收/拒绝 两个动作按钮，点击打开文件共享页 */
    private fun postRequestNotification(p: PendingTransfer, notifyId: Int) {
        fun actionPi(action: String): PendingIntent = PendingIntent.getBroadcast(
            appContext, notifyId,
            Intent(appContext, FileShareActionReceiver::class.java)
                .setAction(action)
                .putExtra(FileShareActionReceiver.EXTRA_TRANSFER_ID, p.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            appContext, notifyId,
            Intent(appContext, com.clipditto.app.ui.FileShareActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(appContext, App.CHANNEL_FILE_SHARE)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle("文件接收请求")
            .setContentText("「${p.fromDeviceName}」想发送 ${p.fileName}（${formatSize(p.fileSize)}）")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(0, "接收", actionPi(FileShareActionReceiver.ACTION_ACCEPT))
            .addAction(0, "拒绝", actionPi(FileShareActionReceiver.ACTION_REJECT))
            .build()
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(notifyId, n)
        }
    }

    private fun cancelNotification(notifyId: Int) {
        if (notifyId <= 0) return
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(notifyId)
        }
    }

    /** 「批量文件接收请求」通知：整批 全部接收/拒绝（逐个勾选需打开应用在前台操作） */
    private fun postBatchRequestNotification(p: PendingBatch, notifyId: Int) {
        fun actionPi(action: String): PendingIntent = PendingIntent.getBroadcast(
            appContext, notifyId,
            Intent(appContext, FileShareActionReceiver::class.java)
                .setAction(action)
                .putExtra(FileShareActionReceiver.EXTRA_TRANSFER_ID, p.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            appContext, notifyId,
            Intent(appContext, com.clipditto.app.ui.FileShareActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val total = p.files.sumOf { it.size }
        val n = NotificationCompat.Builder(appContext, App.CHANNEL_FILE_SHARE)
            .setSmallIcon(R.drawable.ic_clipboard)
            .setContentTitle("批量文件接收请求")
            .setContentText(
                "「${p.fromDeviceName}」想发送 ${p.files.size} 个文件（共 ${formatSize(total)}），打开应用可逐个勾选"
            )
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .addAction(0, "全部接收", actionPi(FileShareActionReceiver.ACTION_ACCEPT))
            .addAction(0, "拒绝", actionPi(FileShareActionReceiver.ACTION_REJECT))
            .build()
        runCatching {
            (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(notifyId, n)
        }
    }

    /** 本机局域网 IPv4 地址（遍历网卡取首个非回环 IPv4；未连接局域网返回 null） */
    fun localIp(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()

    /** 文件大小显示：B / KB / MB / GB */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
