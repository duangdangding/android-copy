package com.clipditto.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
                if (!pkg.isNullOrBlank() && pkg != packageName) {
                    lastForegroundPackage = pkg
                }
                // 窗口切换/弹出菜单（复制菜单弹出或关闭）时，剪贴板可能刚变化
                if (event.packageName != packageName) copyTrigger?.invoke()
            }
            // 追踪用户聚焦/点击/选中的输入框，记住节点供粘贴使用
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (event.packageName == packageName) return
                val source = event.source ?: return
                if (source.isEditable) {
                    lastEditableNode = AccessibilityNodeInfo.obtain(source)
                }
                source.recycle()
                // 点击（如点了"复制"菜单项）、选中文本后，剪贴板可能即将写入
                if (event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    copyTrigger?.invoke()
                }
            }
            // 很多 App 复制成功会弹 Toast（"已复制" 等），是可靠的复制信号
            // （Toast 事件的类型值与 TYPE_NOTIFICATION_STATE_CHANGED 相同）
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (event.packageName != packageName) copyTrigger?.invoke()
            }
        }
    }

    override fun onInterrupt() {}

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

        /** 检测到可能的复制行为时回调（由 ClipboardService 注册，内部有节流） */
        @Volatile
        var copyTrigger: (() -> Unit)? = null

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
