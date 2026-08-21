package com.clipditto.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** 应用 / 数据库 存储占用统计 */
object StorageStats {

    data class Stats(
        val appTotalBytes: Long,   // 应用私有目录总占用
        val mediaBytes: Long,      // 其中媒体文件（图片/视频/文件）占用
        val dbBytes: Long,         // 数据库文件占用（含 wal/shm）
        val recordCount: Int       // 记录条数
    )

    suspend fun collect(context: Context, recordCount: Int): Stats =
        withContext(Dispatchers.IO) {
            val dataDir = context.filesDir.parentFile   // /data/data/<pkg>
            val mediaDir = File(context.filesDir, "media")
            val dbFile = context.getDatabasePath("clipditto.db")
            val dbBytes = dbFile.length() +
                File(dbFile.path + "-wal").let { if (it.exists()) it.length() else 0L } +
                File(dbFile.path + "-shm").let { if (it.exists()) it.length() else 0L }
            Stats(
                appTotalBytes = dirSize(dataDir),
                mediaBytes = dirSize(mediaDir),
                dbBytes = dbBytes,
                recordCount = recordCount
            )
        }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { f -> if (f.isFile) total += f.length() }
        return total
    }

    fun format(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.CHINA, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
