package com.clipditto.app.util

/** 模糊搜索：包含匹配 + 子序列匹配（如 "剪板" 可命中 "剪贴板"），忽略大小写 */
object FuzzySearch {

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
        return i == q.length
    }
}
