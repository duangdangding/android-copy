package com.clipditto.app.util

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.clipditto.app.service.BootReceiver
// checkAppLock 扩展函数中使用 com.clipditto.app.ui.LockScreenActivity
import java.security.MessageDigest

/**
 * 应用加锁管理：
 * 1. 密码经盐 + SHA-256 加密保存（明文与明文哈希都不落盘）；
 * 2. 忘记密码可通过清空加锁状态重置（同时清掉密码）；
 * 3. 支持手动加锁、解锁，以及设置"自动加锁时间"（超时自动锁）。
 *
 * 拦截方式：所有 Activity 继承 [GuardedActivity]，在 onResume 前检查是否处于加锁状态。
 */
object AppLockManager {

    private const val KEY_LOCKED = "lock_locked"
    private const val KEY_PASSWORD = "lock_password_hash"
    private const val KEY_SALT = "lock_password_salt"
    private const val KEY_AUTO_LOCK_MINUTES = "lock_auto_lock_minutes"

    /** 自动加锁时间选项（分钟），0 表示关闭自动加锁 */
    val AUTO_LOCK_OPTIONS = intArrayOf(0, 1, 5, 10, 30)

    // ---- 锁状态（进程内） ----

    /** 进程是否处于已解锁状态（进程被杀后重新进入默认锁定） */
    @Volatile
    private var unlocked = false

    /** 最近一次前台可见时间（用于自动加锁计时） */
    @Volatile
    private var lastForegroundTimeMs: Long = 0L

    /** 前台可见次数：LockScreenActivity 解锁后先忽略一次拦截，避免回到主页又被拦下 */
    @Volatile
    var skipNextCheckFor = 0L

    /** 全局是否启用加锁（有密码 + 用户开启）；无密码视为不启用 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_PASSWORD, null) != null

    /** 当前是否处于加锁状态（未解锁即视为锁定） */
    fun isLocked(context: Context): Boolean =
        isEnabled(context) && !unlocked

    /** 手动加锁：立即进入锁定状态 */
    fun lock() {
        unlocked = false
    }

    /** 解锁成功（校验密码通过后调用） */
    fun onUnlocked() {
        unlocked = true
        lastForegroundTimeMs = SystemClock.elapsedRealtime()
    }

    /** 忘记密码 / 不再需要加锁：清除密码与锁定状态 */
    fun reset(context: Context) {
        prefs(context).edit()
            .remove(KEY_PASSWORD)
            .remove(KEY_SALT)
            .remove(KEY_LOCKED)
            .apply()
        unlocked = false
    }

    /** 当前前台页面变化：用于自动加锁计时 */
    fun onForeground(context: Context) {
        if (!isEnabled(context)) return
        // 从后台回到前台：先检查是否超过自动加锁时间
        val minutes = getAutoLockMinutes(context)
        if (unlocked && minutes > 0) {
            val elapsed = (SystemClock.elapsedRealtime() - lastForegroundTimeMs) / 60000L
            if (elapsed >= minutes) {
                unlocked = false
            }
        }
        lastForegroundTimeMs = SystemClock.elapsedRealtime()
    }

    /** 应用退到后台时记录时间（供自动加锁用） */
    fun onBackground() {
        lastForegroundTimeMs = SystemClock.elapsedRealtime()
    }

    // ---- 密码管理 ----

    /** 设置密码：生成盐 + 哈希保存 */
    fun setPassword(context: Context, password: String) {
        val salt = randomHex(16)
        prefs(context).edit()
            .putString(KEY_SALT, salt)
            .putString(KEY_PASSWORD, sha256(salt + password))
            .apply()
    }

    /** 修改密码：先校验旧密码，成功设置新密码并解锁 */
    fun changePassword(context: Context, oldPassword: String, newPassword: String): Boolean {
        if (!verify(context, oldPassword)) return false
        setPassword(context, newPassword)
        onUnlocked()
        return true
    }

    /** 校验密码：与保存的哈希比对 */
    fun verify(context: Context, password: String): Boolean {
        val salt = prefs(context).getString(KEY_SALT, null) ?: return false
        val saved = prefs(context).getString(KEY_PASSWORD, null) ?: return false
        return sha256(salt + password) == saved
    }

    // ---- 自动加锁时间 ----

    /** 自动加锁分钟数，0 = 关闭 */
    fun getAutoLockMinutes(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_LOCK_MINUTES, 0)

    fun setAutoLockMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_AUTO_LOCK_MINUTES, minutes).apply()
        // 调整自动锁时间后重置计时起点
        lastForegroundTimeMs = SystemClock.elapsedRealtime()
    }

    // ---- 工具 ----

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars.random() }.joinToString("")
    }

    // ---- 全局生命周期注册 ----

    /** 在 Application 中调用，注册所有页面的加锁拦截 */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is com.clipditto.app.ui.LockScreenActivity) {
                    // 防止刚解锁回主页/设置页时被自己的拦截卡死
                    val skip = skipNextCheckFor
                    if (skip > 0 && System.currentTimeMillis() < skip) {
                        skipNextCheckFor = 0
                        onForeground(activity)
                        return
                    }
                }
                onForeground(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/**
 * 每个 Activity 在 onResume 开头调用；若已加锁则跳解锁页并结束自己，返回 true 表示已拦截。
 */
fun AppCompatActivity.checkAppLock(): Boolean {
    if (this !is com.clipditto.app.ui.LockScreenActivity && AppLockManager.isLocked(this)) {
        AppLockManager.onForeground(this)
        com.clipditto.app.ui.LockScreenActivity.startUnlock(this)
        finish()
        return true
    }
    AppLockManager.onForeground(this)
    return false
}
