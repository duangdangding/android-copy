package com.clipditto.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clips")
data class ClipItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 见 [ClipType] */
    val type: Int,
    /** 文字内容，或媒体/文件的预览文字（文件名等） */
    val text: String? = null,
    /** 图片 / 文件 / 视频 复制到应用私有目录后的路径 */
    val filePath: String? = null,
    val mimeType: String? = null,
    /** 复制来源 App 的包名（取不到则为空） */
    val sourceApp: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val favorite: Boolean = false,
    /** 局域网同步：内容最初来源设备的 deviceId；本机原创为 null */
    val remoteDeviceId: String? = null,
    /** 局域网同步：该内容在来源设备上的记录 id；本机原创为 null */
    val remoteId: Long? = null
)

object ClipType {
    const val TEXT = 0
    const val IMAGE = 1
    const val FILE = 2
    const val VIDEO = 3
    const val AUDIO = 4

    fun label(type: Int): String = when (type) {
        TEXT -> "文"
        IMAGE -> "图"
        FILE -> "件"
        VIDEO -> "视"
        AUDIO -> "音"
        else -> "?"
    }
}
