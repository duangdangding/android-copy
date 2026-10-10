package com.clipditto.app.util

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType

/**
 * 模糊搜索：包含匹配 + 子序列匹配（如 "剪板" 可命中 "剪贴板"），忽略大小写。
 * 查询为纯英文字母且目标含汉字时，额外支持拼音匹配：
 * 首拼（"jtb" 命中 "剪贴板"）与全拼（"jiantie" / "jiantieban"）。
 */
object FuzzySearch {

    /**
     * 目标的拼音形式：syllables 与文本字符一一对应（汉字取其第一个读音、无声调，
     * 多音字只认常见音——pinyin4j 不做分词，"重庆" 会得到 "zhongqing"，可接受）；
     * full 为全拼拼接串，initials 为首字母串。
     */
    class PinyinForm internal constructor(
        val syllables: List<String>,
        val full: String,
        val initials: String,
        private val offsets: IntArray // full 串中每个音节的起始下标，末尾元素为 full.length
    ) {
        /** 把 full 串中的字母区间 [letterStart, letterEnd) 映射回文本字符区间（闭区间），无交集返回 null */
        fun charSpan(letterStart: Int, letterEnd: Int): IntRange? {
            if (letterStart >= letterEnd) return null
            var first = -1
            var last = -1
            for (i in syllables.indices) {
                if (offsets[i + 1] > letterStart && offsets[i] < letterEnd) {
                    if (first < 0) first = i
                    last = i
                }
            }
            return if (first < 0) null else first..last
        }
    }

    private val PINYIN_FORMAT = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    // 拼音转换缓存：同一段文本只转换一次，避免搜索时每次击键全量重算。
    // 按字符总量限额而非条数——记录数超过缓存条数时，一次过滤遍历会边查边挤掉前面的条目
    // （缓存抖动），导致每条记录每次击键都重转；按总量控制则无论多少条记录都不会抖动
    private const val MAX_CACHE_CHARS = 500_000 // 约 1.5~3 MB 内存

    private val cache = LinkedHashMap<String, PinyinForm>(64, 0.75f, true)
    private var cacheChars = 0

    fun matches(query: String, target: String?): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (target == null) return false
        val t = target.lowercase()
        if (t.contains(q)) return true
        // 子序列模糊匹配：查询中的字符按顺序在目标中出现即可
        var i = 0
        for (c in t) {
            if (i < q.length && c == q[i]) i++
        }
        if (i == q.length) return true
        // 纯字母查询：再按拼音匹配（首拼 / 全拼，均为包含匹配）
        if (isLetterQuery(q) && hasHan(t)) {
            val form = pinyinFormOf(target)
            if (form.initials.contains(q) || form.full.contains(q)) return true
        }
        return false
    }

    /** 纯小写英文字母的查询才走拼音匹配（query 需已 trim + lowercase） */
    fun isLetterQuery(q: String): Boolean = q.isNotEmpty() && q.all { it in 'a'..'z' }

    /** 文本是否含汉字（无汉字时拼音串就是原文小写，不必转换） */
    fun hasHan(text: String): Boolean = text.any { it in '\u4E00'..'\u9FFF' }

    /** 取文本的拼音形式（带 LRU 缓存） */
    @Synchronized
    fun pinyinFormOf(text: String): PinyinForm {
        cache[text]?.let { return it }
        val form = buildForm(text)
        cache[text] = form
        cacheChars += text.length
        // 超出预算时从最久未访问的开始淘汰（保底留 32 条，且不淘汰刚插入的条目）
        val it = cache.entries.iterator()
        while (cacheChars > MAX_CACHE_CHARS && cache.size > 32 && it.hasNext()) {
            val e = it.next()
            if (e.key !== text) {
                cacheChars -= e.key.length
                it.remove()
            }
        }
        return form
    }

    private fun buildForm(text: String): PinyinForm {
        val syllables = ArrayList<String>(text.length)
        val full = StringBuilder(text.length * 3)
        val initials = StringBuilder(text.length)
        val offsets = IntArray(text.length + 1)
        text.forEachIndexed { i, c ->
            offsets[i] = full.length
            val s = syllableOf(c)
            syllables.add(s)
            full.append(s)
            initials.append(s[0])
        }
        offsets[text.length] = full.length
        return PinyinForm(syllables, full.toString(), initials.toString(), offsets)
    }

    private fun syllableOf(c: Char): String {
        if (c in '\u4E00'..'\u9FFF') {
            runCatching {
                PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT)?.firstOrNull()
            }.getOrNull()?.let { return it }
        }
        return c.lowercase()
    }
}
