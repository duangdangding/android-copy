package com.clipditto.app.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.widget.ImageView
import com.clipditto.app.data.ClipItem
import com.clipditto.app.data.ClipType
import com.clipditto.app.util.MediaFiles

/** 详情弹窗的媒体预览：图片/视频解码出预览图，其余类型隐藏。
 *  解码逻辑与 HistoryAdapter 列表缩略图保持一致 */
object DetailPreview {

    /** 解码失败（权限失效/文件损坏/格式异常）时静默隐藏预览，绝不影响详情弹窗本身 */
    fun bind(imageView: ImageView, item: ClipItem) {
        val bmp = runCatching { decode(imageView, item) }.getOrNull()
        if (bmp != null) {
            imageView.setImageBitmap(bmp)
            imageView.visibility = View.VISIBLE
        } else {
            imageView.visibility = View.GONE
        }
    }

    private fun decode(imageView: ImageView, item: ClipItem) = when (item.type) {
        ClipType.IMAGE -> item.filePath?.let { path ->
            MediaFiles.openInput(imageView.context, path)
                ?.use { BitmapFactory.decodeStream(it) }
        }
        ClipType.VIDEO -> item.filePath?.let { path ->
            runCatching {
                val r = MediaMetadataRetriever()
                // filePath 可能是本地路径或 content:// 文档 URI（同步下载的）
                if (MediaFiles.isContentUri(path)) {
                    r.setDataSource(imageView.context, Uri.parse(path))
                } else {
                    r.setDataSource(path)
                }
                val frame = r.frameAtTime
                r.release()
                frame
            }.getOrNull()
        }
        else -> null
    }
}
