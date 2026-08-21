package com.clipditto.app.util

/**
 * 识别剪贴板内容里的电商/短视频分享口令。
 * 口令格式经常变，这里用「关键词 + 特征包裹符」双重启发式判断。
 */
object TokenUtils {

    enum class Platform(val label: String, val packageNames: List<String>) {
        JD("京东", listOf("com.jingdong.app.mall", "com.jd.jdlite")),
        JINGFEN("京粉", listOf("com.jd.jxj")),
        DOUYIN("抖音", listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite")),
        KUAISHOU("快手", listOf("com.smile.gifmaker", "com.kuaishou.nebula")),
        PDD("拼多多", listOf("com.xunmeng.pinduoduo")),
        TAOBAO("淘宝", listOf("com.taobao.taobao", "com.taobao.litetao"))
    }

    /** 识别文本属于哪个平台的口令；识别不出返回 null */
    fun detect(text: String?): Platform? {        if (text.isNullOrBlank()) return null
        val t = text.lowercase()

        return when {
            // 抖音：关键词 / 短链 / ππ 包裹 / ## 包裹
            t.contains("抖音") || t.contains("douyin.com") ||
                t.contains("ππ") || Regex("##[^#]{4,}##").containsMatchIn(text) ->
                Platform.DOUYIN

            // 快手：关键词 / 短链 / 常见包裹符
            t.contains("快手") || t.contains("kuaishou.com") ||
                t.contains("🎋") || t.contains("Ω") ||
                Regex("[₽₵][^₽₵\\s]{6,}[₽₵]").containsMatchIn(text) ->
                Platform.KUAISHOU

            // 拼多多：关键词 / 短链 / ⇥⇤ 包裹
            t.contains("拼多多") || t.contains("pinduoduo.com") ||
                t.contains("⇥") || t.contains("⇤") ->
                Platform.PDD

            // 京粉：关键词 / 官网域名（需在京东之前判断，jingfen.jd.com 也含 jd.com）
            t.contains("京粉") || t.contains("jingfen.jd.com") ->
                Platform.JINGFEN

            // 京东：关键词 / 短链 / ！包裹 / 变形拼音标记
            t.contains("京东") || t.contains("jd.com") ||
                t.contains("jℹng") || t.contains("⤴") ||
                Regex("！[!-~]{6,}！").containsMatchIn(text) ->
                Platform.JD

            // 淘宝/天猫：关键词 / ￥€₤ 包裹
            t.contains("淘宝") || t.contains("天猫") || t.contains("taobao.com") ||
                t.contains("tmall.com") ||
                Regex("[￥€₤₴][^￥€₤₴\\s]{4,}[￥€₤₴]").containsMatchIn(text) ->
                Platform.TAOBAO

            else -> null
        }
    }

    /** 通过来源 App 包名反推平台（文字特征识别失败时的备用判断） */
    fun fromPackage(sourcePackage: String?): Platform? {
        if (sourcePackage.isNullOrBlank()) return null
        return Platform.entries.firstOrNull { platform ->
            sourcePackage in platform.packageNames
        }
    }
}
