package com.clipditto.app.util

/** 从文本中提取网址 */
object UrlUtils {

    // 匹配 http/https 链接，遇到空白和常见中英文标点结尾停止
    private val URL_REGEX = Regex("""https?://[^\s，。；、！？）】》"']+""")

    /** 返回文本中的第一个网址；没有则返回 null */
    fun firstUrl(text: String?): String? =
        text?.let { URL_REGEX.find(it)?.value }
}
