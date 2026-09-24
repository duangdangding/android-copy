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

/**
 * 一次待确认的传入文件：body 已由 LanSyncServer 完整落到临时文件，
 * 正在等待用户确认（或自动接收）。id 用于确认弹窗/通知按钮回传。
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

    /** 接收请求通知 id 起始段（避开同步结果通知的 1002） */
    private const val NOTIFY_BASE = 3100

    private lateinit var appContext: Context
    private lateinit var lanSettings: LanSettings
    lateinit var settings: FileShareSettings
        private set
    lateinit var discovery: FileShareDiscovery
        private set
    private var initialized = false

    /** 等待确认的传输：transferId → 等待句柄 */
    private data class Waiter(
        val latch: CountDownLatch,
        val result: AtomicBoolean,
        val notifyId: Int
    )

    private val pending = ConcurrentHashMap<String, Waiter>()
    private val notifySeq = AtomicInteger(0)

    /** 文件共享页在前台时注册的确认弹窗处理器（在 HTTP 服务线程上被调用，不得阻塞） */
    @Volatile
    var confirmUiHandler: ((PendingTransfer) -> Unit)? = null

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
     * 自动接收 → 直接通过；页面在前台 → 弹窗确认；否则 → 通知栏「接收/拒绝」。
     * 最多等待 [CONFIRM_TIMEOUT_SEC] 秒，超时按拒收处理。
     */
    private fun awaitAccept(
        fileName: String, fileSize: Long, mime: String?,
        fromDeviceId: String?, fromDeviceName: String
    ): Boolean {
        if (settings.autoReceive) return true
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
            // 前台弹窗路径：不需要通知
            val w = Waiter(CountDownLatch(1), AtomicBoolean(false), 0)
            pending[p.id] = w
            return try {
                // 弹窗展示失败（页面已销毁等）时按拒收处理
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
            NOTIFY_BASE + notifySeq.incrementAndGet() % 1000
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

    /** 弹窗/通知按钮回调：解出对应传输的等待锁 */
    fun resolvePending(transferId: String, accept: Boolean) {
        pending[transferId]?.let {
            it.result.set(accept)
            it.latch.countDown()
        }
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
