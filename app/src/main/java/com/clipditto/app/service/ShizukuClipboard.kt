package com.clipditto.app.service

import android.content.ClipData
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.clipditto.app.BuildConfig
import com.clipditto.app.IClipboardBridge
import rikka.shizuku.Shizuku

/**
 * Shizuku 剪贴板通道（App 进程侧）：
 * 授权后把读取动作转发到 shell 进程（见 [ClipboardShellService]），
 * 不抢窗口焦点，无任何界面副作用。
 *
 * 生命周期：
 * - [isServerRunning]：Shizuku 服务是否在运行（用户每次重启手机后需重新激活 Shizuku）
 * - [isPermissionGranted]：本 App 是否已被授权
 * - [isBound]：shell 桥接服务是否已连接
 * 三个条件逐层满足时，[readClipboard] 才返回真实数据；否则返回 null，调用方回退焦点读取。
 */
object ShizukuClipboard {

    private const val TAG = "ClipDitto"
    const val REQUEST_CODE_PERMISSION = 2456
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile
    private var bridge: IClipboardBridge? = null

    @Volatile
    private var binding = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.d(TAG, "Shizuku 桥接服务已连接")
            bridge = IClipboardBridge.Stub.asInterface(binder)
            binding = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "Shizuku 桥接服务已断开")
            bridge = null
            binding = false
        }
    }

    // 监听器必须长期持有引用（Shizuku 官方要求，否则会被 GC 回收）
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { bind() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder 已失效（服务停止）")
        bridge = null
        binding = false
    }

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ClipboardShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku_clipboard")
        .debuggable(BuildConfig.DEBUG)
        .version(1)

    /** App 启动时调用一次：监听 Shizuku binder 的到来/死亡 */
    fun init() {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { Log.w(TAG, "Shizuku 初始化失败: ${it.message}") }
    }

    /** Shizuku 服务是否在运行 */
    fun isServerRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 本 App 是否已获得 Shizuku 授权 */
    fun isPermissionGranted(): Boolean =
        isServerRunning() && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** shell 桥接服务是否已连接（连接是异步的） */
    fun isBound(): Boolean = bridge != null

    /** 请求 Shizuku 授权（弹出 Shizuku 授权对话框） */
    fun requestPermission() {
        if (isServerRunning() && !isPermissionGranted()) {
            runCatching { Shizuku.requestPermission(REQUEST_CODE_PERMISSION) }
        }
    }

    /** 绑定桥接服务（幂等，异步连接） */
    @Synchronized
    fun bind() {
        if (binding || bridge != null) return
        if (!isPermissionGranted()) return
        binding = true
        runCatching { Shizuku.bindUserService(serviceArgs, connection) }
            .onFailure {
                Log.w(TAG, "Shizuku 绑定失败: ${it.message}")
                binding = false
            }
    }

    /** 以 shell 身份读剪贴板；未就绪/未连接/失败时返回 null，由调用方决定回退 */
    fun readClipboard(): ClipData? {
        if (!isPermissionGranted()) return null
        val b = bridge ?: run {
            bind()
            return null
        }
        return runCatching { b.readClipboard() }
            .onFailure {
                Log.w(TAG, "Shizuku 读取失败: ${it.message}")
                bridge = null
                bind()
            }
            .getOrNull()
    }
}
