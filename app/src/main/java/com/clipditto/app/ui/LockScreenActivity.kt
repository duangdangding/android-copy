package com.clipditto.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.clipditto.app.R
import com.clipditto.app.util.AppLockManager

/**
 * 加锁解锁页：输入密码解锁；未设置密码时不应进入本页（GuardedActivity 不拦截）。
 * 忘记密码可清空重置。
 */
class LockScreenActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SHOW_PANEL = "show_panel_after_unlock"

        private fun buildIntent(context: Context, showPanel: Boolean): Intent {
            val intent = Intent(context, LockScreenActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (showPanel) {
                intent.putExtra(EXTRA_SHOW_PANEL, true)
            }
            return intent
        }

        /** 从 Activity 页面启动解锁；解锁成功后回主页 */
        fun startUnlock(context: Context) {
            context.startActivity(buildIntent(context, showPanel = false))
        }

        /** 从悬浮窗启动解锁；解锁成功后直接展开悬浮面板，不打开主页 */
        fun startUnlockForPanel(context: Context) {
            context.startActivity(buildIntent(context, showPanel = true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(com.clipditto.app.util.WindowEffect.themeRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_screen)
        com.clipditto.app.util.WindowEffect.apply(this)
        // 无工具栏：仅做窗口沉浸式，状态栏沿用窗口背景
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvTitle = findViewById<TextView>(R.id.tvLockTitle)
        val btnUnlock = findViewById<View>(R.id.btnUnlock)
        val tvReset = findViewById<TextView>(R.id.tvResetPassword)

        tvTitle.text = "应用已加锁"

        // 点击输入框自动弹出软键盘
        etPassword.setOnClickListener { showKeyboard(etPassword) }
        showKeyboard(etPassword)

        val showPanelAfterUnlock = intent.getBooleanExtra(EXTRA_SHOW_PANEL, false)

        btnUnlock.setOnClickListener {
            val input = etPassword.text.toString()
            if (AppLockManager.verify(this, input)) {
                AppLockManager.onUnlocked()
                if (showPanelAfterUnlock) {
                    // 从悬浮窗进来：解锁后直接展开悬浮面板，不打开主页
                    com.clipditto.app.service.ClipboardService.instance?.showPanelFromUnlock()
                } else {
                    // 从主页/设置页进来：解锁后回主页（避免按返回键回到已 finish 的上一页）
                    startActivity(Intent(this, MainActivity::class.java))
                }
                finish()
            } else {
                etPassword.setText("")
                Toast.makeText(this, "密码错误，请重新输入", Toast.LENGTH_SHORT).show()
                showKeyboard(etPassword)
            }
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                btnUnlock.performClick()
                true
            } else false
        }

        // 忘记密码：二次确认后重置清空（清除密码，解除加锁）
        tvReset.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("忘记密码")
                .setMessage("清空密码后应用将不再加锁，需要重新设置密码才能再次启用。确定重置？")
                .setPositiveButton("重置") { _, _ ->
                    AppLockManager.reset(this)
                    Toast.makeText(this, "已重置，加锁已解除", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onBackPressed() {
        // 已加锁时返回键直接退出应用，不能绕过解锁
        moveTaskToBack(true)
    }

    private fun showKeyboard(editText: EditText) {
        editText.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }
}
