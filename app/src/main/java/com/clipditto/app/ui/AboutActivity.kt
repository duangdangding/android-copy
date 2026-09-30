package com.clipditto.app.ui

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.clipditto.app.BuildConfig
import com.clipditto.app.R
import com.clipditto.app.util.EdgeToEdge
import com.clipditto.app.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「关于」页：应用信息、版本更新检查（含下载安装全流程）、项目地址。
 * 发现新版本不弹确认框、不展示更新日志，点按钮直接下载安装；
 * 下载进度与安装权限引导仍在本页完成，主页只做静默检查。
 */
class AboutActivity : AppCompatActivity() {

    /** 最近一次检查到的最新 release（成功且为新版本时用于「立即更新」） */
    private var latestInfo: UpdateChecker.ReleaseInfo? = null

    /** 授权后待安装的更新包（从系统授权页返回时继续安装） */
    private var pendingInstallApk: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        // 沉浸式：Toolbar 背景延伸到状态栏，底部避开手势导航条
        EdgeToEdge.apply(this, findViewById(R.id.toolbar))

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.tvVersion).text =
            "当前版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        // 已检查到新版本时按钮直接下载，否则先检查
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            latestInfo?.let { downloadUpdate(it) } ?: checkUpdate(manual = true)
        }

        bindLink(R.id.tvLinkPc, "https://github.com/duangdangding/pc-lscopy/releases")
        bindLink(R.id.tvLinkAndroid, "https://github.com/duangdangding/android-copy")

        // 进入页面自动检查一次
        checkUpdate(manual = false)
    }

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」授权页返回：已授权则自动继续安装更新包
        pendingInstallApk?.let {
            if (packageManager.canRequestPackageInstalls()) installApk(it)
        }
    }

    /** 项目地址行：下划线标示可点，点击打开浏览器 */
    private fun bindLink(viewId: Int, url: String) {
        findViewById<TextView>(viewId).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }.onFailure {
                    Toast.makeText(this@AboutActivity, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------------- 版本更新 ----------------

    /**
     * 检查更新并刷新「最新版本」行。发现新版本时不弹确认框、不展示更新日志：
     * 手动触发（点按钮）直接开始下载；进入页面的自动检查只更新文字与按钮状态。
     */
    private fun checkUpdate(manual: Boolean) {
        findViewById<TextView>(R.id.tvLatest).text = "最新版本：检查中…"
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { UpdateChecker.fetchLatest() }
            if (isFinishing || isDestroyed) return@launch
            val tv = findViewById<TextView>(R.id.tvLatest)
            when {
                info == null -> {
                    tv.text = "最新版本：检查失败"
                    if (manual) Toast.makeText(
                        this@AboutActivity, "检查更新失败，请稍后重试", Toast.LENGTH_SHORT
                    ).show()
                }
                UpdateChecker.isNewer(info.tag, BuildConfig.VERSION_NAME) -> {
                    latestInfo = info
                    tv.text = "最新版本：${info.tag}（有新版本）"
                    findViewById<Button>(R.id.btnCheckUpdate).text = "立即更新"
                    // 手动触发不二次确认，直接下载
                    if (manual) downloadUpdate(info)
                }
                else -> {
                    latestInfo = null
                    tv.text = "最新版本：${info.tag}（已是最新）"
                    findViewById<Button>(R.id.btnCheckUpdate).text = "检查更新"
                    if (manual) Toast.makeText(
                        this@AboutActivity, "当前已是最新版本", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 带进度下载更新包，完成后调起安装；「取消」中断下载线程 */
    private fun downloadUpdate(info: UpdateChecker.ReleaseInfo) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            .apply { max = 100 }
        val tvProgress = TextView(this).apply {
            textSize = 13f
            text = "正在连接…"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(progress)
            addView(tvProgress)
        }
        val cancelled = AtomicBoolean(false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("下载更新 ${info.tag}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelled.set(true) }
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.download(
                    applicationContext, info.tag, info.apkUrl,
                    onProgress = { done, total ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            if (total > 0) {
                                progress.progress = (done * 100 / total).toInt()
                                tvProgress.text = "已下载 %.1f MB / %.1f MB"
                                    .format(done / 1048576.0, total / 1048576.0)
                            } else {
                                // Content-Length 未知：只显示已下载量
                                tvProgress.text = "已下载 %.1f MB".format(done / 1048576.0)
                            }
                        }
                    },
                    isCancelled = { cancelled.get() }
                )
            }
            runCatching { dialog.dismiss() }
            if (result == null) {
                if (!cancelled.get()) Toast.makeText(
                    this@AboutActivity, "下载失败，请重试", Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            // release 附带 .sha256 校验文件时比对哈希（下载时已流式算出）；旧 release 没有则跳过
            if (info.sha256Url != null) {
                val expected = withContext(Dispatchers.IO) {
                    UpdateChecker.fetchSha256(info.sha256Url)
                }
                if (expected == null || !result.sha256.equals(expected, ignoreCase = true)) {
                    result.delete(applicationContext)
                    Toast.makeText(
                        this@AboutActivity, "安装包校验失败，请重新下载", Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                Toast.makeText(this@AboutActivity, "校验通过", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(
                this@AboutActivity, "已保存到${result.savedTo}", Toast.LENGTH_LONG
            ).show()
            installApk(result.uri)
        }
    }

    /**
     * 调起系统安装器。Android 8+ 需要「安装未知应用」权限：
     * 未授权先引导跳转系统设置，用户授权返回后（onResume）自动继续安装。
     */
    private fun installApk(uri: Uri) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = uri
            MaterialAlertDialogBuilder(this)
                .setTitle("需要安装权限")
                .setMessage("安装更新需要「安装未知应用」权限，请在打开的页面中允许本应用安装")
                .setPositiveButton("去授权") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        pendingInstallApk = null
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(this, "无法调起安装器", Toast.LENGTH_SHORT).show()
            }
    }
}
