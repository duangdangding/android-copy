package com.clipditto.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.clipditto.app.data.ClipItem
import com.clipditto.app.util.TokenUtils
import com.clipditto.app.util.UrlUtils

/**
 * 列表项 / 详情弹窗共用的动作按钮逻辑：
 * 「打开平台App」/「打开备选平台（京东↔京粉）」/「浏览器打开链接」
 *
 * 识别顺序：文字口令特征 > 来源包名反推 > 网址
 */
object ItemActionButtons {

    fun bind(
        openUrl: Button,
        openAlt: Button,
        openBrowser: Button,
        item: ClipItem,
        /** 成功跳转外部 App / 浏览器后的回调（悬浮面板用于收起列表） */
        onOpened: (() -> Unit)? = null
    ) {
        val platform = TokenUtils.detect(item.text) ?: TokenUtils.fromPackage(item.sourceApp)
        val url = UrlUtils.firstUrl(item.text)

        when {
            platform != null -> {
                bindPlatformButton(openUrl, item, platform, onOpened)
                // 京东 ↔ 京粉 互跳：京东内容带「打开京粉」，京粉内容带「打开京东」
                val alt = when (platform) {
                    TokenUtils.Platform.JD -> TokenUtils.Platform.JINGFEN
                    TokenUtils.Platform.JINGFEN -> TokenUtils.Platform.JD
                    else -> null
                }
                if (alt != null) {
                    bindPlatformButton(openAlt, item, alt, onOpened)
                } else {
                    openAlt.visibility = View.GONE
                    openAlt.setOnClickListener(null)
                }
                // 口令/平台内容里带链接时，追加「浏览器」按钮
                bindBrowserButton(openBrowser, url, onOpened)
            }
            url != null -> {
                openAlt.visibility = View.GONE
                openAlt.setOnClickListener(null)
                openBrowser.visibility = View.GONE
                openBrowser.setOnClickListener(null)
                openUrl.text = "打开"
                openUrl.visibility = View.VISIBLE
                openUrl.setOnClickListener { v -> openUrlInBrowser(v, url, onOpened) }
            }
            else -> {
                openUrl.visibility = View.GONE
                openUrl.setOnClickListener(null)
                openAlt.visibility = View.GONE
                openAlt.setOnClickListener(null)
                openBrowser.visibility = View.GONE
                openBrowser.setOnClickListener(null)
            }
        }
    }

    /** 用浏览器打开链接 */
    private fun openUrlInBrowser(view: View, url: String, onOpened: (() -> Unit)?) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            view.context.startActivity(intent)
            onOpened?.invoke()
        }.onFailure {
            Toast.makeText(view.context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    /** 有链接时绑定「浏览器」按钮，否则隐藏 */
    private fun bindBrowserButton(button: Button, url: String?, onOpened: (() -> Unit)?) {
        if (url == null) {
            button.visibility = View.GONE
            button.setOnClickListener(null)
            return
        }
        button.text = "浏览器"
        button.visibility = View.VISIBLE
        button.setOnClickListener { v -> openUrlInBrowser(v, url, onOpened) }
    }

    /** 绑定一个"打开某平台 App"的按钮：先复制口令到剪贴板，再拉起对应 App */
    private fun bindPlatformButton(
        button: Button,
        item: ClipItem,
        platform: TokenUtils.Platform,
        onOpened: (() -> Unit)?
    ) {
        button.text = "打开${platform.label}"
        button.visibility = View.VISIBLE
        button.setOnClickListener { v ->
            val ctx = v.context
            // 先把口令放回系统剪贴板，目标 App 启动时会读取
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("clipditto", item.text ?: ""))
            // 依次尝试正式版 / 极速版包名
            val launch = platform.packageNames
                .asSequence()
                .mapNotNull { ctx.packageManager.getLaunchIntentForPackage(it) }
                .firstOrNull()
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(launch) }
                onOpened?.invoke()
            } else {
                Toast.makeText(ctx, "未安装${platform.label}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
