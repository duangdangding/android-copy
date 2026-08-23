package com.clipditto.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * 复制信号的强度分级：
 * - [WEAK_WINDOW]：窗口切换/弹窗（可能只是弹了个输入框，与复制无关）
 * - [SELECTION]：选中文字（可能要复制，但此刻剪贴板尚未写入）
 * - [ACTION]：点"复制"菜单项 / 复制成功 Toast（复制已发生，应立即读取）
 */
enum class CopySignal { WEAK_WINDOW, SELECTION, ACTION }

/**
 * 无障碍服务：实现"点击记录即粘贴到下层输入框"。
 *
 * 三条通道，按优先级尝试：
 * 0. 事件追踪：用户点过/聚焦过的输入框节点会被记住，直接对它粘贴（最可靠）
 * 1. 窗口查找：在交互窗口里找非本应用的聚焦可编辑节点
 * 2. 树遍历：焦点标记丢失时，遍历窗口树取可见的可编辑节点
 *
 * 粘贴动作本身也是两通道：ACTION_PASTE 不行就 ACTION_SET_TEXT 手动拼接。
 */
class PasteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            // 追踪前台窗口：复制动作发生时，最后处于前台的 App 即为内容来源
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                val isIme = isImePackage(pkg)
                val isSysDialog = isSystemDialogPackage(pkg)
                // 系统弹窗（生物识别/应用锁/通知栏）出现：记录时间，
                // 此期间所有读取都要推迟——抢焦点会打断指纹/人脸认证
                if (isSysDialog) lastSystemDialogAt = SystemClock.uptimeMillis()
                // 输入法窗口和系统弹窗都不算前台 App，否则会误记为复制内容的来源
                if (!pkg.isNullOrBlank() && pkg != packageName && !isIme && !isSysDialog) {
                    lastForegroundPackage = pkg
                }
                // 窗口切换/弹出菜单（复制菜单弹出或关闭）时，剪贴板可能刚变化。
                // 这只是"弱信号"：弹普通对话框（如输入框）也会触发。
                // 输入法窗口和系统弹窗的弹出/切换与复制无关，直接跳过
                if (event.packageName != packageName && !isIme && !isSysDialog) {
                    copyTrigger?.invoke(CopySignal.WEAK_WINDOW)
                }
            }
            // 追踪用户聚焦/点击/选中的输入框，记住节点供粘贴使用
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (event.packageName == packageName) return
                val source = event.source ?: return
                val editable = source.isEditable
                if (editable) {
                    lastEditableNode = AccessibilityNodeInfo.obtain(source)
                    // 输入框刚被聚焦/点击：键盘即将弹出，记下时间供弱信号抑制判断
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                        event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
                    ) {
                        lastEditableFocusedAt = SystemClock.uptimeMillis()
                    }
                }
                source.recycle()
                when (event.eventType) {
                    // 点击"复制"菜单项等（复制已发生）：应立即读取，菜单在点击瞬间
                    // 已关闭，此时抢焦点不会闪。点输入框是要弹键盘，不触发
                    AccessibilityEvent.TYPE_VIEW_CLICKED ->
                        if (!editable) copyTrigger?.invoke(CopySignal.ACTION)
                    // 选中文字（选区非折叠）才可能复制；打字时的光标移动
                    // （fromIndex == toIndex）不触发，避免边打字边抢焦点造成卡顿。
                    // 记下选区时间：选区活跃期间（操作菜单弹出中）不立即读剪贴板，
                    // 由 ClipboardService 推迟到选区稳定后合并读取，避免闪屏
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                        if (event.fromIndex != event.toIndex) {
                            lastSelectionAt = SystemClock.uptimeMillis()
                            copyTrigger?.invoke(CopySignal.SELECTION)
                        }
                }
            }
            // 很多 App 复制成功会弹 Toast（"已复制" 等），是可靠的复制信号
            // （Toast 事件的类型值与 TYPE_NOTIFICATION_STATE_CHANGED 相同）。
            // 注意：普通系统通知也走这个事件，必须用 className 过滤出 Toast，
            // 否则每次来通知都会抢焦点，干扰用户当前操作
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val isToast = event.className?.toString()
                    ?.contains("Toast", ignoreCase = true) == true
                if (isToast && event.packageName != packageName) {
                    copyTrigger?.invoke(CopySignal.ACTION)
                }
            }
        }
    }

    override fun onInterrupt() {}

    /** 已启用输入法的包名缓存（1 分钟刷新一次），用于过滤键盘弹出/切换事件 */
    private var imePackages: Set<String> = emptySet()
    private var imePackagesAt = 0L

    private fun isImePackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        if (imePackages.isEmpty() || now - imePackagesAt > 60_000) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.enabledInputMethodList.map { it.packageName }.toSet()
            imePackagesAt = now
        }
        return pkg in imePackages
    }

    /**
     * 系统弹窗类包名：生物识别/指纹人脸解锁、应用锁（MIUI 安全中心）、
     * 通知栏、权限弹窗等。这类窗口出现时抢焦点会打断认证/操作，绝不轮询。
     */
    private fun isSystemDialogPackage(pkg: String?): Boolean {
        pkg ?: return false
        return pkg == "com.android.systemui" ||
            pkg.contains("security") ||   // com.miui.securitycenter / securitycore 等应用锁
            pkg.contains("biometric") ||
            pkg.contains("faceunlock")
    }

    override fun onDestroy() {
        instance = null
        lastEditableNode = null
        super.onDestroy()
    }

    companion object {
        var instance: PasteAccessibilityService? = null
            private set

        /** 最近一次处于前台的 App 包名（不含本应用），用于推断剪贴板来源 */
        @Volatile
        var lastForegroundPackage: String? = null
            private set

        /** 检测到可能的复制行为时回调（由 ClipboardService 注册，内部有节流）。
         *  参数为信号强度，见 [CopySignal] */
        @Volatile
        var copyTrigger: ((CopySignal) -> Unit)? = null

        /** 最近一次输入框被聚焦/点击的时间（输入法即将弹出） */
        @Volatile
        private var lastEditableFocusedAt = 0L

        /** 最近一次出现非折叠文字选区的时间（选中/拖动调整选区都会刷新） */
        @Volatile
        private var lastSelectionAt = 0L

        /** 最近一次系统弹窗（生物识别/应用锁/通知栏等）出现的时间 */
        @Volatile
        private var lastSystemDialogAt = 0L

        /**
         * 系统弹窗刚出现（3 秒内）：生物识别/应用锁/权限弹窗正在显示，
         * 此时抢焦点读剪贴板会打断认证（指纹/人脸失败），所有读取都要推迟。
         */
        fun systemDialogRecently(): Boolean =
            SystemClock.uptimeMillis() - lastSystemDialogAt < 3_000

        /**
         * 文字选区刚出现或正在调整（操作菜单/浮动工具栏正在显示）。
         * 此时抢焦点读剪贴板会把菜单/选区挤闪，应推迟到选区稳定后再读。
         */
        fun selectionRecently(): Boolean =
            SystemClock.uptimeMillis() - lastSelectionAt < 2_000

        /** 输入框刚聚焦的 2 秒内：键盘正在弹出动画中，抢焦点会把键盘挤掉 */
        fun imeAnimatingIn(): Boolean =
            SystemClock.uptimeMillis() - lastEditableFocusedAt < 2_000

        /** 键盘窗口当前可见（已稳定打开，非弹出动画中） */
        fun imeVisible(): Boolean = runCatching {
            instance?.windows?.any {
                it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        }.getOrNull() == true

        /** 用户最近聚焦/点击过的输入框节点（事件追踪） */
        @Volatile
        private var lastEditableNode: AccessibilityNodeInfo? = null

        val isEnabled: Boolean
            get() = instance != null

        /**
         * 把 [insertText] 粘贴到下层 App 输入框的光标处。
         * @return null 表示成功；否则返回失败原因（用于提示）
         */
        fun pasteIntoFocusedInput(insertText: String): String? {
            val service = instance ?: return "无障碍服务未连接"

            // 通道 0：事件记住的输入框（用户最后点过的那个）
            val stored = lastEditableNode
            var storedAlive = false
            if (stored != null) {
                val node = AccessibilityNodeInfo.obtain(stored)
                storedAlive = runCatching { node.refresh() }.getOrDefault(false)
                if (storedAlive && node.isEditable) {
                    return doPaste(node, insertText)
                }
                node.recycle()
            }

            // 通道 1/2：窗口查找 + 树遍历
            val windowsCount = service.windows.size
            val candidates = LinkedHashSet<AccessibilityNodeInfo>()
            // 活跃窗口放最前（最可能就是用户正在输入的窗口）
            service.rootInActiveWindow
                ?.takeIf { it.packageName != service.packageName }
                ?.let { candidates.add(it) }
            service.windows
                .mapNotNull { it.root }
                .filter { it.packageName != service.packageName }
                .forEach { candidates.add(it) }

            if (candidates.isEmpty()) {
                return "读取不到其他应用的窗口（无障碍设置里检查「检索窗口内容」）[诊断: 窗口=$windowsCount]"
            }
            // 逐窗口统计：包名=可编辑数/总节点数，找到就粘贴
            val stats = StringBuilder()
            for (root in candidates) {
                runCatching { root.refresh() }
                var total = 0
                val editables = collectEditables(root) { total++ }
                stats.append("${root.packageName?.toString()?.substringAfterLast('.')}=${editables.size}/$total; ")
                // 优先输入焦点路径
                var focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                while (focused != null && !focused.isEditable) {
                    focused = focused.parent
                }
                val target = focused
                    ?: editables.firstOrNull { it.isFocused }
                    ?: editables.singleOrNull()
                    ?: editables.firstOrNull()
                if (target != null) return doPaste(target, insertText)
            }
            return "没有找到可用的输入框 [诊断: 记住节点=${stored != null} 存活=$storedAlive " +
                "窗口=$windowsCount 能力=${service.serviceInfo?.capabilities} " +
                "事件类型=${service.serviceInfo?.eventTypes} 各窗口(可编辑/总节点): $stats]"
        }

        /** 广度遍历收集窗口内所有可见可编辑节点；onNode 回调统计总节点数 */
        private fun collectEditables(
            root: AccessibilityNodeInfo,
            onNode: () -> Unit = {}
        ): List<AccessibilityNodeInfo> {
            val editables = ArrayList<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val n = queue.removeFirst()
                onNode()
                if (n.isEditable && n.isVisibleToUser && n.isEnabled) editables.add(n)
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { queue.add(it) }
                }
            }
            return editables
        }

        /** 执行粘贴：标准 ACTION_PASTE 优先，失败回退 ACTION_SET_TEXT 光标处拼接 */
        private fun doPaste(node: AccessibilityNodeInfo, insertText: String): String? {
            // 通道 A：标准粘贴（从系统剪贴板）
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return null

            // 通道 B：手动在光标处拼接文本
            val current = node.text?.toString() ?: ""
            val selStart = node.textSelectionStart.takeIf { it >= 0 } ?: current.length
            val selEnd = node.textSelectionEnd.takeIf { it >= selStart } ?: selStart
            val newText = current.substring(0, selStart) + insertText + current.substring(selEnd)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newText
                )
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return null
            node.recycle()
            return "目标输入框不支持自动粘贴"
        }
    }
}
