package com.clipditto.app.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/**
 * 媒体文件访问的统一入口。
 *
 * ClipItem.filePath 有两种形态：
 * - 本地绝对路径（/data/...）：本机剪贴板捕获、zip 导入还原的媒体，存私有目录
 * - content:// 文档 URI：同步下载到用户自定义目录（SAF 目录树）的媒体
 *
 * 所有读写/删除/取大小都走这里，调用方无需关心形态差异。
 */
object MediaFiles {

    fun isContentUri(path: String): Boolean = path.startsWith("content://")

    fun exists(context: Context, path: String): Boolean =
        if (isContentUri(path)) query(context, path) != null
        else File(path).exists()

    /** 文件大小（字节）；读不到返回 0 */
    fun length(context: Context, path: String): Long =
        if (isContentUri(path)) query(context, path)?.second ?: 0L
        else File(path).length()

    /** 显示文件名（含扩展名） */
    fun displayName(context: Context, path: String): String =
        if (isContentUri(path)) {
            query(context, path)?.first
                ?: Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: "file"
        } else File(path).name

    fun delete(context: Context, path: String): Boolean =
        if (isContentUri(path)) runCatching {
            // contentResolver.delete 对 SAF 文档 URI 和 MediaStore URI 都有效
            context.contentResolver.delete(Uri.parse(path), null, null) > 0
        }.onFailure { android.util.Log.w("MediaFiles", "删除失败 $path: ${it.message}") }
            .getOrDefault(false)
        else File(path).delete()

    fun openInput(context: Context, path: String): InputStream? =
        if (isContentUri(path)) runCatching {
            context.contentResolver.openInputStream(Uri.parse(path))
        }.getOrNull()
        else runCatching { File(path).inputStream() }.getOrNull()

    /**
     * 把 source 文件写入用户选择的 SAF 目录树，返回新文档的 Uri 字符串；失败返回 null。
     * 同名冲突时系统会自动改名（如 "xxx (1).jpg"），以返回的 Uri 为准。
     */
    fun writeToTree(
        context: Context,
        treeUriStr: String,
        displayName: String,
        mimeType: String?,
        source: File
    ): String? {
        val treeUri = Uri.parse(treeUriStr)
        return runCatching {
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val docUri = DocumentsContract.createDocument(
                context.contentResolver, treeDocUri,
                mimeType ?: "application/octet-stream", displayName
            ) ?: return null
            val ok = context.contentResolver.openOutputStream(docUri, "wt")?.use { os ->
                source.inputStream().use { it.copyTo(os) }
            } != null
            if (!ok) {
                // 写失败要清掉刚建的空文档，避免留下 0 字节垃圾文件
                runCatching { context.contentResolver.delete(docUri, null, null) }
                return null
            }
            docUri.toString()
        }.onFailure { android.util.Log.w("MediaFiles", "写入目录树失败: ${it.message}") }
            .getOrNull()
    }

    /**
     * 默认存储位置：系统 Download/ClipDitto/ 目录（Android 10+ 走 MediaStore，免权限）。
     * Android 9 及以下没有 MediaStore.Downloads，回退应用私有目录（返回本地路径）。
     */
    fun writeToDefaultDir(
        context: Context,
        displayName: String,
        mimeType: String?,
        source: File
    ): String? {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return runCatching {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        mimeType ?: "application/octet-stream"
                    )
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/ClipDitto"
                    )
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                val ok = context.contentResolver.openOutputStream(uri, "wt")?.use { os ->
                    source.inputStream().use { it.copyTo(os) }
                } != null
                if (!ok) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    return null
                }
                uri.toString()
            }.onFailure { android.util.Log.w("MediaFiles", "写入 Download 失败: ${it.message}") }
                .getOrNull()
        }
        // API 26-28 回退：私有目录
        return runCatching {
            val outDir = File(context.filesDir, "media").apply { mkdirs() }
            val out = File(outDir, displayName)
            source.inputStream().use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.absolutePath
        }.getOrNull()
    }

    /** 查询 content URI 的（显示名, 大小）；查不到（已删除/权限失效）返回 null */
    private fun query(context: Context, path: String): Pair<String, Long>? =
        runCatching {
            context.contentResolver.query(
                Uri.parse(path),
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: "file"
                    val size = if (c.isNull(1)) 0L else c.getLong(1)
                    name to size
                } else null
            }
        }.getOrNull()
}
