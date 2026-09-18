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

    // ---------- 工具 ----------
    /** 域名匹配：防止 "notdouyin.com"、"jd.com.evil.cn" 之类的子串/伪造误报 */
    private fun domainPattern(domain: String) = Regex(
        """https?://([\w-]+\.)*${Regex.escape(domain)}(/[^\s"'<>]*)?""",
        RegexOption.IGNORE_CASE
    )

    /** 包裹符匹配：要求成对出现，中间为口令体（字母数字符号，无空格、长度受限） */
    private fun wrapperPattern(chars: String, min: Int = 4, max: Int = 100) = Regex(
        """[$chars][^$chars\s]{$min,$max}[$chars]"""
    )

    private fun matchesAny(text: String, t: String, patterns: List<Regex>, keywords: List<String>) =
        keywords.any { it in t } || patterns.any { it.containsMatchIn(text) }

    // ---------- 各平台规则（顺序即优先级） ----------

    private val douyinRules = listOf(
        "抖音" to listOf(domainPattern("douyin.com")),
        // ππ 成对（口令常见为 ππxx...xxππ）
        "抖音-π" to listOf(Regex("""ππ\S{4,80}ππ""")),
        // ## 口令
        "抖音-#" to listOf(Regex("""##[^#\s]{4,80}##"""))
    )

    private val kuaishouRules = listOf(
        "快手" to listOf(domainPattern("kuaishou.com"), domainPattern("gifshow.com")),
        // 🎋 成对（去掉裸 "快手" 与裸 "Ω"，误报太高）
        "快手-🎋" to listOf(Regex("""🎋[^🎋\s]{4,80}🎋""")),
        // Ω 仅在 "结尾Ω" 的口令形态出现才算
        "快手-Ω" to listOf(Regex("""\S{4,80}Ω$"""))
    )

    private val pddRules = listOf(
        "拼多多" to listOf(domainPattern("pinduoduo.com"), domainPattern("yangkeduo.com")),
        // ⇥⇤ 必须成对，单独的 ⇥ / ⇤ 不算
        "拼多多-⇥" to listOf(Regex("""⇥[^⇥⇤\s]{4,80}⇤"""))
    )

    private val jingfenRules = listOf(
        "京粉" to listOf(Regex("""https?://jingfen\.jd\.com/[^\s"'<>]*""", RegexOption.IGNORE_CASE))
    )

    private val jdRules = listOf(
        "京东" to listOf(domainPattern("jd.com"), domainPattern("3.cn")),
        "京东-! " to listOf(Regex("""！[^！\s]{6,80}！""")),
        "京东-变形" to listOf(Regex("""jℹng|⤴"""))
    )

    private val taobaoRules = listOf(
        "淘宝" to listOf(
            domainPattern("taobao.com"), domainPattern("tmall.com"),
            domainPattern("tb.cn"), domainPattern("m.tb.cn"),
            domainPattern("lu.com") // 阿里妈妈短链之一，按需增删
        ),
        "淘口令" to listOf(Regex("""[€￥₤₴][^€￥₤₴\s]{4,80}[€￥₤₴]"""))
    )

    /** 识别文本属于哪个平台的口令；识别不出返回 null */
    fun detect(text: String?): Platform? {
        if (text.isNullOrBlank()) return null
        val t = text.lowercase()

        return when {
            matchesAny(text, t, douyinRules.flatMap { it.second }, douyinRules.map { it.first }) -> Platform.DOUYIN
            matchesAny(text, t, kuaishouRules.flatMap { it.second }, kuaishouRules.map { it.first }) -> Platform.KUAISHOU
            matchesAny(text, t, pddRules.flatMap { it.second }, pddRules.map { it.first }) -> Platform.PDD
            // 京粉必须在京东之前判断
            matchesAny(text, t, jingfenRules.flatMap { it.second }, jingfenRules.map { it.first }) -> Platform.JINGFEN
            matchesAny(text, t, jdRules.flatMap { it.second }, jdRules.map { it.first }) -> Platform.JD
            matchesAny(text, t, taobaoRules.flatMap { it.second }, taobaoRules.map { it.first }) -> Platform.TAOBAO
            else -> null
        }
    }

    /** 识别文本属于哪个平台的口令；识别不出返回 null */
//    fun detect(text: String?): Platform? {        if (text.isNullOrBlank()) return null
//        val t = text.lowercase()
//
//        return when {
//            // 抖音：关键词 / 短链 / ππ 包裹 / ## 包裹
//            t.contains("抖音") || t.contains("douyin.com") ||
//                t.contains("ππ") || Regex("##[^#]{4,}##").containsMatchIn(text) ->
//                Platform.DOUYIN
//
//            // 快手：关键词 / 短链 / 常见包裹符
//            t.contains("快手") || t.contains("kuaishou.com") ||
//                t.contains("🎋") || t.contains("Ω") ||
//                Regex("[₽₵][^₽₵\\s]{6,}[₽₵]").containsMatchIn(text) ->
//                Platform.KUAISHOU
//
//            // 拼多多：关键词 / 短链 / ⇥⇤ 包裹
//            t.contains("拼多多") || t.contains("pinduoduo.com") ||
//                t.contains("⇥") || t.contains("⇤") ->
//                Platform.PDD
//
//            // 京粉：关键词 / 官网域名（需在京东之前判断，jingfen.jd.com 也含 jd.com）
//            t.contains("京粉") || t.contains("jingfen.jd.com") ->
//                Platform.JINGFEN
//
//            // 京东：关键词 / 短链 / ！包裹 / 变形拼音标记
//            t.contains("京东") || t.contains("jd.com") ||
//                t.contains("jℹng") || t.contains("⤴") ||
//                Regex("！[!-~]{6,}！").containsMatchIn(text) ->
//                Platform.JD
//
//            // 淘宝/天猫：关键词 / ￥€₤ 包裹
//            t.contains("淘宝") || t.contains("天猫") || t.contains("taobao.com") ||
//                t.contains("tmall.com") ||
//                Regex("[￥€₤₴][^￥€₤₴\\s]{4,}[￥€₤₴]").containsMatchIn(text) ->
//                Platform.TAOBAO
//
//            else -> null
//        }
//    }

    /** 通过来源 App 包名反推平台（文字特征识别失败时的备用判断） */
    fun fromPackage(sourcePackage: String?): Platform? {
        if (sourcePackage.isNullOrBlank()) return null
        return Platform.entries.firstOrNull { platform ->
            sourcePackage in platform.packageNames
        }
    }
}
