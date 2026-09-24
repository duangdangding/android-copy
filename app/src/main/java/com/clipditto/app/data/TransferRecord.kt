package com.clipditto.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 文件共享的传送记录：只保存「成功接收」的记录
 * （发送、失败、被拒收的不入库）。
 */
@Entity(tableName = "transfer_records")
data class TransferRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 接收到的文件名（以发送方提供的文件名为准） */
    val fileName: String,
    /**
     * 落盘位置，两种形态（同 ClipItem.filePath，读写统一走 MediaFiles）：
     * 本地绝对路径 或 content:// 文档 URI（用户自定义 SAF 目录）。
     */
    val savedPath: String,
    val fileSize: Long,
    val mimeType: String? = null,
    /** 发送方设备 id（对端是旧版/未上报告时为 null） */
    val fromDeviceId: String? = null,
    val fromDeviceName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
