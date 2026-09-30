package com.clipditto.app.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.clipditto.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        val apkUrl: String,
        /** SHA-256 校验文件下载地址（assets 中以 .sha256 结尾的那个；旧 release 没有则为 null） */
        val sha256Url: String?
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
            val assetUrls = json.getAsJsonArray("assets")
                ?.mapNotNull { it.asJsonObject.get("browser_download_url")?.asString }
                ?: return null
            val apkUrl = assetUrls.firstOrNull { it.endsWith(".apk") } ?: return null
            val sha256Url = assetUrls.firstOrNull { it.endsWith(".sha256") }
            ReleaseInfo(tag, json.get("body")?.asString ?: "", apkUrl, sha256Url)
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

    /** 下载完成的结果：安装用 Uri + 下载过程中算出的 SHA-256 + 给用户看的保存位置 */
    class DownloadResult(
        /** 调起安装器使用的 Uri（MediaStore content Uri 或 FileProvider Uri） */
        val uri: Uri,
        /** 下载流式计算出的 SHA-256（64 位小写十六进制） */
        val sha256: String,
        /** 保存位置描述（"系统下载目录"或具体路径），用于提示用户去哪找安装包 */
        val savedTo: String,
        /** 外置私有目录落盘时的文件（仅 Android 8~9 分支非空），校验失败时删文件用 */
        val file: File?
    ) {
        /** 校验失败/放弃安装时删除已下载的安装包 */
        fun delete(context: Context) {
            file?.delete() ?: runCatching {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    /**
     * 流式下载 APK 到系统「下载」目录（文件名 剪贴板_v<tag>.apk），让用户能在文件管理器里找到。
     * Android 10+ 经 MediaStore 写入 Download/（免存储权限）；Android 8~9 无权限写公共目录，
     * 退而写入外置应用私有 Download 目录（文件管理器 Android/data 下可见）。
     * 下载过程中同步计算 SHA-256。GitHub 的下载地址会 302 重定向，HttpURLConnection 默认跟随。
     * @param onProgress (已下载字节, 总字节)；总字节 <=0 表示 Content-Length 未知
     * @param isCancelled 返回 true 时中断下载
     * @return 成功返回下载结果；失败/取消返回 null（半成品已清理）
     */
    fun download(
        context: Context,
        tag: String,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean
    ): DownloadResult? {
        val appContext = context.applicationContext
        val fileName = "剪贴板_v$tag.apk"
        // 目标：优先 MediaStore 系统下载目录；老版本用外置私有目录兜底
        val useMediaStore = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var itemUri: Uri? = null
        var file: File? = null
        val out = if (useMediaStore) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.w(TAG, "创建下载目录条目失败")
                return null
            }
            itemUri = uri
            resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
        } else {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (dir == null) {
                Log.w(TAG, "外置存储不可用")
                return null
            }
            dir.mkdirs()
            val f = File(dir, fileName)
            file = f
            f.outputStream()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        var success = false
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
            val md = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            out.use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
            if (isCancelled()) return null
            // 总长度已知时校验完整性
            if (total > 0 && downloaded != total) {
                Log.w(TAG, "下载不完整: $downloaded/$total")
                return null
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            val result = if (useMediaStore) {
                // 写完去掉 pending 标记，文件才对其他应用可见
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                appContext.contentResolver.update(itemUri!!, done, null, null)
                DownloadResult(itemUri!!, hash, "系统下载目录", null)
            } else {
                val uri = FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.fileprovider", file!!
                )
                DownloadResult(uri, hash, file!!.absolutePath, file)
            }
            success = true
            return result
        } catch (e: Exception) {
            Log.w(TAG, "下载更新包异常: ${e.message}")
            return null
        } finally {
            conn.disconnect()
            // 失败/取消时清理半成品（pending 条目或未写完的文件）
            if (!success) {
                runCatching {
                    if (useMediaStore) itemUri?.let {
                        appContext.contentResolver.delete(it, null, null)
                    } else file?.delete()
                }
            }
        }
    }

    /**
     * 下载 .sha256 校验文本并解析出哈希值（sha256sum 标准格式：`<hash>  <文件名>`）。
     * @return 64 位小写十六进制哈希；网络失败/格式不符返回 null
     */
    fun fetchSha256(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent", "ClipDitto-Android/${BuildConfig.VERSION_NAME}"
            )
            if (conn.responseCode != 200) {
                Log.w(TAG, "下载校验文件失败: HTTP ${conn.responseCode}")
                return null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            // 取第一行第一个空白分隔的 token，须为 64 位十六进制
            val hash = text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                ?.lowercase()
            if (hash != null && hash.matches(Regex("[0-9a-f]{64}"))) hash else {
                Log.w(TAG, "校验文件格式不符: ${text.take(100)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载校验文件异常: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
