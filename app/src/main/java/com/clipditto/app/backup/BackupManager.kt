package com.clipditto.app.backup

import android.content.Context
import android.net.Uri
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 导入：
 * 备份 = zip 包，内含 clips.json（全部记录）+ media/ 下的媒体文件。
 * 导入 = 解 zip，媒体文件放回私有目录并改写路径，记录合并进数据库。
 */
object BackupManager {

    private const val JSON_ENTRY = "clips.json"
    private const val MEDIA_PREFIX = "media/"

    private val gson = Gson()

    /** 导出到用户选择的 Uri，返回备份的记录数 */
    suspend fun export(context: Context, repo: ClipRepository, target: Uri): Int =
        withContext(Dispatchers.IO) {
            val items = repo.getAll()
            context.contentResolver.openOutputStream(target)?.use { os ->
                ZipOutputStream(os.buffered()).use { zip ->
                    // 1. 元数据：把 filePath 改写为 zip 内的相对路径，便于跨设备导入
                    val exportItems = items.map { item ->
                        item.filePath?.let { path ->
                            item.copy(filePath = MEDIA_PREFIX + File(path).name)
                        } ?: item
                    }
                    zip.putNextEntry(ZipEntry(JSON_ENTRY))
                    zip.write(gson.toJson(exportItems).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // 2. 媒体文件
                    items.forEach { item ->
                        val path = item.filePath ?: return@forEach
                        val file = File(path)
                        if (file.exists()) {
                            zip.putNextEntry(ZipEntry(MEDIA_PREFIX + file.name))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            } ?: throw IllegalStateException("无法写入备份文件")
            items.size
        }

    /** 从用户选择的 Uri 导入，返回导入的记录数 */
    suspend fun import(context: Context, repo: ClipRepository, source: Uri): Int =
        withContext(Dispatchers.IO) {
            var json: String? = null
            val mediaFiles = mutableMapOf<String, File>()

            context.contentResolver.openInputStream(source)?.use { ins ->
                ZipInputStream(ins.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        when {
                            entry.name == JSON_ENTRY ->
                                json = zip.readBytes().toString(Charsets.UTF_8)
                            entry.name.startsWith(MEDIA_PREFIX) -> {
                                val out = File(repo.mediaDir, File(entry.name).name)
                                out.outputStream().use { zip.copyTo(it) }
                                mediaFiles[entry.name] = out
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("无法读取备份文件")

            val text = json ?: throw IllegalStateException("备份文件中没有 clips.json")
            val type = object : TypeToken<List<ClipItem>>() {}.type
            val items: List<ClipItem> = gson.fromJson(text, type)

            // 路径改写为本机实际路径后合并入库
            val restored = items.map { item ->
                item.filePath?.let { rel ->
                    val local = mediaFiles[rel]
                    if (local != null) item.copy(id = 0, filePath = local.absolutePath)
                    else item.copy(id = 0, filePath = null)
                } ?: item.copy(id = 0)
            }
            repo.insertAll(restored)
            restored.size
        }
}
