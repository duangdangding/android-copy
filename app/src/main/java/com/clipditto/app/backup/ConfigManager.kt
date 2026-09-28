package com.clipditto.app.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 配置导出 / 导入：把应用各模块的 SharedPreferences 打包成一个 JSON 文件，
 * 可保存到用户在设置里选择的文件夹，换机或重装后导入恢复。
 */
object ConfigManager {

    /** 参与导出导入的配置文件（SharedPreferences 名） */
    private val PREF_FILES = listOf("settings", "lan_sync", "relay_sync", "file_share")

    /** 导出全部配置到目标 Uri，返回配置项总数 */
    suspend fun export(context: Context, target: Uri): Int = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        val prefsObj = JSONObject()
        var count = 0
        PREF_FILES.forEach files@{ name ->
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val fileObj = JSONObject()
            all.forEach items@{ (key, value) ->
                val entry = JSONObject()
                when (value) {
                    is String -> { entry.put("t", "s"); entry.put("v", value) }
                    is Int -> { entry.put("t", "i"); entry.put("v", value) }
                    is Long -> { entry.put("t", "l"); entry.put("v", value) }
                    is Boolean -> { entry.put("t", "b"); entry.put("v", value) }
                    is Float -> { entry.put("t", "f"); entry.put("v", value.toDouble()) }
                    is Set<*> -> {
                        entry.put("t", "ss")
                        entry.put("v", org.json.JSONArray(value.filterIsInstance<String>()))
                    }
                    else -> return@items
                }
                fileObj.put(key, entry)
                count++
            }
            prefsObj.put(name, fileObj)
        }
        root.put("prefs", prefsObj)
        context.contentResolver.openOutputStream(target, "wt")?.use { os ->
            os.write(root.toString(2).toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("无法写入配置文件")
        count
    }

    /** 从 Uri 导入配置（覆盖同名配置项），返回导入的配置项总数 */
    suspend fun import(context: Context, source: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use { ins ->
            ins.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("无法读取配置文件")
        val root = JSONObject(text)
        val prefsObj = root.optJSONObject("prefs")
            ?: throw IllegalStateException("不是有效的配置文件")
        var count = 0
        prefsObj.keys().forEach files@{ name ->
            // 只恢复认识的配置文件，忽略未知内容
            if (name !in PREF_FILES) return@files
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            val fileObj = prefsObj.getJSONObject(name)
            fileObj.keys().forEach items@{ key ->
                val entry = fileObj.getJSONObject(key)
                when (entry.optString("t")) {
                    "s" -> editor.putString(key, entry.optString("v"))
                    "i" -> editor.putInt(key, entry.optInt("v"))
                    "l" -> editor.putLong(key, entry.optLong("v"))
                    "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                    "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                    "ss" -> {
                        val arr = entry.optJSONArray("v")
                        val set = mutableSetOf<String>()
                        if (arr != null) for (i in 0 until arr.length()) set.add(arr.getString(i))
                        editor.putStringSet(key, set)
                    }
                    else -> return@items
                }
                count++
            }
            editor.apply()
        }
        count
    }
}
