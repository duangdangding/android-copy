package com.clipditto.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.clipditto.app.util.MediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 负责剪贴板记录的读写，以及图片/文件/视频等媒体内容落盘到应用私有目录。
 *
 * 媒体的 filePath 有两种形态（见 [MediaFiles]）：
 * 本地绝对路径（私有目录）或 content:// 文档 URI（同步下载到用户自定义目录），
 * 删除/取大小/读流统一走 MediaFiles。
 */
class ClipRepository(private val context: Context) {

    private val dao = ClipDatabase.get(context).clipDao()

    val clips: Flow<List<ClipItem>> = dao.observeAll()

    /** 媒体文件存放目录（本机捕获的媒体；同步下载的媒体存用户自定义目录） */
    val mediaDir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    suspend fun latest(): ClipItem? = dao.latest()

    /** 插入记录，并按用户设置的数量上限自动清理最旧的非收藏记录（0 = 不限制） */
    suspend fun insert(item: ClipItem): Long = withContext(Dispatchers.IO) {
        val id = dao.insert(item)
        val max = getMaxRecords()
        if (max > 0) {
            val excess = dao.count() - max
            if (excess > 0) {
                dao.oldestNonFavorite(excess).forEach { old ->
                    old.filePath?.let { MediaFiles.delete(context, it) }
                    dao.deleteById(old.id)
                }
            }
        }
        id
    }

    /** 查找相同文本的记录 */
    suspend fun findDuplicateText(text: String): ClipItem? =
        dao.findByText(ClipType.TEXT, text)

    /** 查找相同内容的媒体记录（按类型 + 文件大小比对） */
    suspend fun findDuplicateMedia(type: Int, file: File): ClipItem? =
        withContext(Dispatchers.IO) {
            dao.getByType(type).firstOrNull {
                it.filePath?.let { p -> MediaFiles.length(context, p) == file.length() } == true
            }
        }

    /** 把已有记录的时间戳更新为现在：相同内容不再重复入库，而是顶到列表最前 */
    suspend fun touch(id: Long) =
        dao.touchTimestamp(id, System.currentTimeMillis())

    /** 收藏/取消收藏 */
    suspend fun toggleFavorite(item: ClipItem) = dao.update(item.copy(favorite = !item.favorite))

    /** 读取记录数量上限设置，0 = 不限制 */
    fun getMaxRecords(): Int =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getInt("max_records", 0)

    fun setMaxRecords(max: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("max_records", max)
            .apply()
    }

    suspend fun update(item: ClipItem) = dao.update(item)

    suspend fun delete(item: ClipItem) = withContext(Dispatchers.IO) {
        item.filePath?.let { MediaFiles.delete(context, it) }
        dao.deleteById(item.id)
    }

    /** 时间段内的收藏记录数 */
    suspend fun countFavoritesBetween(start: Long, end: Long): Int =
        dao.countFavoritesBetween(start, end)

    /** 删除 [start, end] 时间段内的记录，并清理对应媒体文件。返回删除条数。
     *  includeFavorites = false 时保留收藏记录，只删其他。 */
    suspend fun deleteBetween(start: Long, end: Long, includeFavorites: Boolean = true): Int =
        withContext(Dispatchers.IO) {
            val items =
                if (includeFavorites) dao.getBetween(start, end)
                else dao.getBetweenNonFavorite(start, end)
            items.forEach { it.filePath?.let { p -> MediaFiles.delete(context, p) } }
            if (includeFavorites) dao.deleteBetween(start, end)
            else dao.deleteBetweenNonFavorite(start, end)
            items.size
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.getAll().forEach { it.filePath?.let { p -> MediaFiles.delete(context, p) } }
        dao.clear()
    }

    suspend fun getAll(): List<ClipItem> = dao.getAll()

    suspend fun count(): Int = dao.count()

    suspend fun getSince(since: Long): List<ClipItem> = dao.getSince(since)

    suspend fun getById(id: Long): ClipItem? = dao.getById(id)

    /** 局域网同步：插入远端记录（调用方需已完成去重判断） */
    suspend fun insertRemote(item: ClipItem): Long = insert(item)

    /**
     * 局域网同步：删除来自某设备的全部记录并清理媒体文件。
     * 返回 (删除条数, 文件删除失败数)——文件失败多为权限失效或已被用户手动移走。
     */
    suspend fun deleteRemoteDevice(deviceId: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val items = dao.getByRemoteDevice(deviceId)
            var fileFailures = 0
            items.forEach { item ->
                item.filePath?.let { p ->
                    if (!MediaFiles.delete(context, p)) fileFailures++
                }
            }
            dao.deleteByRemoteDevice(deviceId)
            items.size to fileFailures
        }

    suspend fun insertAll(items: List<ClipItem>) = withContext(Dispatchers.IO) {
        items.forEach { dao.insert(it.copy(id = 0)) }
    }

    // ---------------- 媒体文件访问（filePath 两种形态统一入口，供服务端等使用） ----------------

    fun mediaExists(path: String): Boolean = MediaFiles.exists(context, path)

    fun mediaLength(path: String): Long = MediaFiles.length(context, path)

    fun mediaName(path: String): String = MediaFiles.displayName(context, path)

    fun openMedia(path: String): InputStream? = MediaFiles.openInput(context, path)

    /**
     * 把剪贴板里的 Uri 内容（图片 / 视频 / 任意文件）复制到应用私有目录，
     * 返回保存后的文件；失败返回 null。
     */
    suspend fun saveUriContent(uri: Uri, mimeType: String?): File? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = mimeType?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: guessExtension(uri) ?: "bin"
            val out = File(mediaDir, "${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            if (out.length() == 0L) {
                out.delete()
                null
            } else out
        }.getOrNull()
    }

    private fun guessExtension(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 1..5 }
}
