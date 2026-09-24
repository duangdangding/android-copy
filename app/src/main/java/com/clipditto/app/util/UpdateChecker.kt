package com.clipditto.app.util

import android.content.Context
import android.util.Log
import com.clipditto.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本更新检查与安装包下载（GitHub Releases 渠道，仓库 duangdangding/android-copy）。
 * 所有方法都必须在 IO 线程调用（UI 层自行切线程）。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_API =
        "https://api.github.com/repos/duangdangding/android-copy/releases/latest"

    private val gson = Gson()

    /** 一个 release 的关键信息 */
    class ReleaseInfo(
        /** 版本号，如 "4.2"（与 versionName 一致，已去掉可能的前导 v） */
        val tag: String,
        /** 更新日志（release body） */
        val body: String,
        /** APK 资产下载地址（取 assets 中以 .apk 结尾的那个） */
        val apkUrl: String
    )

    /** 查询最新 release；网络失败/无 APK 资产/解析失败返回 null */
    fun fetchLatest(): ReleaseInfo? {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty(
                "User-Agent", "ClipDitto-Android/${BuildConfig.VERSION_NAME}"
            )
            if (conn.responseCode != 200) {
                Log.w(TAG, "查询最新版本失败: HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = gson.fromJson(body, JsonObject::class.java)
            val tag = json.get("tag_name")?.asString?.removePrefix("v") ?: return null
            val apkUrl = json.getAsJsonArray("assets")
                ?.mapNotNull { it.asJsonObject.get("browser_download_url")?.asString }
                ?.firstOrNull { it.endsWith(".apk") }
                ?: return null
            ReleaseInfo(tag, json.get("body")?.asString ?: "", apkUrl)
        } catch (e: Exception) {
            Log.w(TAG, "查询最新版本异常: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** remote 版本是否高于 current："4.10.1" 按 . 分段逐段比较，非数字段容错为 0 */
    fun isNewer(remote: String, current: String): Boolean {
        val a = remote.split('.')
        val b = current.split('.')
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i).toVersionPart()
            val y = b.getOrNull(i).toVersionPart()
            if (x != y) return x > y
        }
        return false
    }

    /** "10" → 10；"2-beta"/""/null → 取数字前缀，取不到为 0 */
    private fun String?.toVersionPart(): Int =
        this?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0

    /**
     * 流式下载 APK 到 cacheDir/update_<tag>.apk（先写 .download 临时文件，完成后改名）。
     * GitHub 的下载地址会 302 重定向，HttpURLConnection 默认跟随。
     * @param onProgress (已下载字节, 总字节)；总字节 <=0 表示 Content-Length 未知
     * @param isCancelled 返回 true 时中断下载
     * @return 成功返回目标文件；失败/取消返回 null（临时文件已清理）
     */
    fun download(
        context: Context,
        tag: String,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): File? {
        val appContext = context.applicationContext
        val dest = File(appContext.cacheDir, "update_$tag.apk")
        val tmp = File(appContext.cacheDir, "update_$tag.apk.download")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent", "ClipDitto-Android/${BuildConfig.VERSION_NAME}"
            )
            if (conn.responseCode != 200) {
                Log.w(TAG, "下载更新包失败: HTTP ${conn.responseCode}")
                return null
            }
            val total = conn.contentLengthLong
            tmp.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                    out.flush()
                }
            }
            if (isCancelled()) return null
            // 总长度已知时校验完整性
            if (total > 0 && tmp.length() != total) {
                Log.w(TAG, "下载不完整: ${tmp.length()}/$total")
                return null
            }
            dest.delete()
            if (!tmp.renameTo(dest)) return null
            return dest
        } catch (e: Exception) {
            Log.w(TAG, "下载更新包异常: ${e.message}")
            return null
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }
}
