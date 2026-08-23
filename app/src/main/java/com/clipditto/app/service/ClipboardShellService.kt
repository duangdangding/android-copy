package com.clipditto.app.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.UserHandle
import com.clipditto.app.IClipboardBridge
import rikka.shizuku.SystemServiceHelper

/**
 * 运行在 Shizuku（shell 身份）进程中的桥接服务。
 * shell 读剪贴板不受"必须持有窗口焦点"的限制，因此完全不需要 1px 悬浮窗抢焦点，
 * 输入法闪烁、选区闪屏、生物识别打断等问题在此通道下全部不存在。
 *
 * 注意：不能用传入 Context 的 ClipboardManager——该 Context 的包名是本 App，
 * 而进程 uid 是 shell，包名与 uid 不匹配会被 ClipboardService 拒绝（安全校验）。
 * 这里直接对 IClipboard binder 手工 transact，callingPackage 用 com.android.shell。
 *
 * getPrimaryClip 的事务码与参数随 Android 版本变化（AOSP IClipboard.aidl 核实）：
 * - API 29-30：code 3，(String pkg, int userId)
 * - API 31-32：code 4，(String pkg, int userId)
 * - API 33：   code 4，(String pkg, String attributionTag, int userId)
 * - API 34+：  code 4，(String pkg, String attributionTag, int userId, int deviceId)
 *
 * Shizuku 约定：服务类必须提供 (Context) 构造函数，由 Shizuku 以 shell 进程上下文实例化。
 */
class ClipboardShellService() : IClipboardBridge.Stub() {

    private var context: Context? = null

    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    private val clipboardBinder: IBinder? by lazy {
        SystemServiceHelper.getSystemService(Context.CLIPBOARD_SERVICE)
    }

    override fun readClipboard(): ClipData? {
        // Android 10 以下没有读取限制，直接用系统 API（Context 版本即可）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val ctx = context ?: return null
            return runCatching {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            }.getOrNull()
        }
        val binder = clipboardBinder ?: throw IllegalStateException("clipboard 系统服务不可用")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(SHELL_PACKAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.writeString(null) // attributionTag，API 33 加入
            }
            data.writeInt(UserHandle.myUserId())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                data.writeInt(DEVICE_ID_DEFAULT) // deviceId，API 34 加入
            }
            // API 29-30：getPrimaryClip 是第 3 个方法；API 31 起前面插入了 setPrimaryClipAsPackage
            val code = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) 3 else 4
            if (!binder.transact(code, data, reply, 0)) {
                throw IllegalStateException("clipboard transact 返回 false")
            }
            reply.readException()
            return if (reply.readInt() != 0) ClipData.CREATOR.createFromParcel(reply) else null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val DESCRIPTOR = "android.content.IClipboard"
        private const val SHELL_PACKAGE = "com.android.shell"
        private const val DEVICE_ID_DEFAULT = 0
    }
}
