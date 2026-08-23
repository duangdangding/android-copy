package com.clipditto.app.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.clipditto.app.IClipboardBridge

/**
 * 运行在 Shizuku（shell 身份）进程中的桥接服务。
 * shell 读剪贴板不受"必须持有窗口焦点"的限制，因此完全不需要 1px 悬浮窗抢焦点，
 * 输入法闪烁、选区闪屏、生物识别打断等问题在此通道下全部不存在。
 *
 * Shizuku 约定：服务类必须提供 (Context) 构造函数，由 Shizuku 以 shell 进程上下文实例化。
 */
class ClipboardShellService() : IClipboardBridge.Stub() {

    private var context: Context? = null

    @Suppress("unused")
    constructor(context: Context) : this() {
        this.context = context
    }

    override fun readClipboard(): ClipData? {
        val ctx = context ?: return null
        return runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        }.getOrNull()
    }
}
