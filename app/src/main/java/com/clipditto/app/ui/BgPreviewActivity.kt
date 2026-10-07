package com.clipditto.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.clipditto.app.R
import com.clipditto.app.service.ClipboardService
import com.clipditto.app.util.AppSettings
import com.clipditto.app.util.CustomBackground

/**
 * 背景预览页：选择图片后进入，全屏实时预览背景效果。
 * 处理管线：原图 → 选取区域裁剪 → 旋转 → 显示模式（拉伸/平铺/自定义尺寸） → 透明度。
 * 「选取区域」是独立的裁剪步骤（在原图上拖框），拉伸/平铺/旋转/自定义尺寸
 * 都作用于裁剪后的区域，未裁剪时即作用于原图。
 * 所有调整在点「确定」前不写回设置。所有面向用户的提示内容必须使用中文。
 */
class BgPreviewActivity : AppCompatActivity() {

    // 预览中的候选参数（确定前不写回设置）
    private var mode = AppSettings.BG_MODE_STRETCH
    private var rotation = 0
    private var region = floatArrayOf(0f, 0f, 1f, 1f)
    private var alpha = 100
    private var scaleW = 100
    private var scaleH = 100

    private var editingRegion = false

    private lateinit var previewArea: View
    private lateinit var controlsPanel: View
    private lateinit var scalePanel: View
    private lateinit var regionLayer: View
    private lateinit var regionView: BgRegionView
    private lateinit var btnRotate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bg_preview)

        if (!CustomBackground.hasImage(this)) {
            Toast.makeText(this, "请先在设置里选择背景图片", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        previewArea = findViewById(R.id.previewArea)
        controlsPanel = findViewById(R.id.controlsPanel)
        scalePanel = findViewById(R.id.scalePanel)
        regionLayer = findViewById(R.id.regionLayer)
        regionView = findViewById(R.id.bgRegionView)
        btnRotate = findViewById(R.id.btnRotate)

        // 以已保存的参数为起点
        mode = AppSettings.getBgMode(this)
        rotation = AppSettings.getBgRotation(this)
        region = AppSettings.getBgRegion(this)
        alpha = AppSettings.getBgAlpha(this)
        scaleW = AppSettings.getBgScaleW(this)
        scaleH = AppSettings.getBgScaleH(this)

        findViewById<ImageButton>(R.id.btnPreviewBack).setOnClickListener { finish() }

        // ---- 显示模式（作用于裁剪后的区域/原图） ----
        findViewById<Button>(R.id.btnModeStretch).setOnClickListener {
            mode = AppSettings.BG_MODE_STRETCH
            refreshPreview()
        }
        findViewById<Button>(R.id.btnModeTile).setOnClickListener {
            mode = AppSettings.BG_MODE_TILE
            refreshPreview()
        }
        findViewById<Button>(R.id.btnModeCustom).setOnClickListener {
            mode = AppSettings.BG_MODE_CUSTOM
            refreshPreview()
        }

        // ---- 选取区域：独立的裁剪步骤，在原图上拖框 ----
        findViewById<Button>(R.id.btnModeRegion).setOnClickListener { enterRegionEdit() }

        // ---- 自定义尺寸：宽度/高度滑杆（10%~200%） ----
        setupSeek(R.id.seekScaleW, R.id.tvScaleW, scaleW, 10, 200) {
            scaleW = it
            refreshPreview()
        }
        setupSeek(R.id.seekScaleH, R.id.tvScaleH, scaleH, 10, 200) {
            scaleH = it
            refreshPreview()
        }

        // ---- 背景图透明度（10%~100%） ----
        setupSeek(R.id.seekAlpha, R.id.tvAlpha, alpha, 10, 100) {
            alpha = it
            refreshPreview()
        }

        // ---- 旋转方向（作用于裁剪后的区域/原图，与选区互不干扰） ----
        btnRotate.setOnClickListener {
            rotation = (rotation + 90) % 360
            refreshPreview()
        }

        // ---- 确定：统一写回设置并立即生效 ----
        findViewById<Button>(R.id.btnConfirm).setOnClickListener {
            AppSettings.setBgMode(this, mode)
            AppSettings.setBgRotation(this, rotation)
            AppSettings.setBgRegion(this, region[0], region[1], region[2], region[3])
            AppSettings.setBgAlpha(this, alpha)
            AppSettings.setBgScaleW(this, scaleW)
            AppSettings.setBgScaleH(this, scaleH)
            AppSettings.setBgEnabled(this, true)
            // 悬浮面板正在显示时立即更新背景
            ClipboardService.refreshAppearance(this)
            Toast.makeText(this, "背景已应用", Toast.LENGTH_SHORT).show()
            finish()
        }

        // ---- 框选层按钮 ----
        findViewById<Button>(R.id.btnRegionReset).setOnClickListener {
            regionView.resetSelection()
        }
        findViewById<Button>(R.id.btnRegionCancel).setOnClickListener {
            // 取消框选：保持原选区，回到预览
            exitRegionEdit()
            refreshPreview()
        }
        findViewById<Button>(R.id.btnRegionDone).setOnClickListener {
            region = regionView.getNormalizedRegion()
            exitRegionEdit()
            refreshPreview()
        }

        // 框选状态下按返回键 = 退出框选，不直接关页
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (editingRegion) {
                    exitRegionEdit()
                    refreshPreview()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        refreshPreview()
    }

    /**
     * 初始化一条「滑杆 + 百分比数值」：范围 [min, max]，拖动实时回调新值并刷新数值显示。
     */
    private fun setupSeek(
        seekId: Int,
        labelId: Int,
        initial: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) {
        val seek = findViewById<SeekBar>(seekId)
        val label = findViewById<TextView>(labelId)
        seek.max = max - min
        seek.progress = (initial - min).coerceIn(0, max - min)
        label.text = "${min + seek.progress}%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p
                label.text = "$v%"
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** 按候选参数重铺预览背景并刷新控件状态 */
    private fun refreshPreview() {
        previewArea.background =
            CustomBackground.buildDrawable(this, mode, rotation, region, alpha, scaleW, scaleH)
        btnRotate.text = "旋转：${rotation}°"
        // 自定义尺寸的长宽滑杆只在该模式下显示
        scalePanel.visibility =
            if (mode == AppSettings.BG_MODE_CUSTOM) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnModeStretch).text =
            if (mode == AppSettings.BG_MODE_STRETCH) "✓ 拉伸" else "拉伸"
        findViewById<Button>(R.id.btnModeTile).text =
            if (mode == AppSettings.BG_MODE_TILE) "✓ 平铺" else "平铺"
        findViewById<Button>(R.id.btnModeCustom).text =
            if (mode == AppSettings.BG_MODE_CUSTOM) "✓ 自定义尺寸" else "自定义尺寸"
        // 选取区域是裁剪工具而非模式，显示当前是否已裁剪
        val cropped = region[0] > 0f || region[1] > 0f || region[2] < 1f || region[3] < 1f
        findViewById<Button>(R.id.btnModeRegion).text =
            if (cropped) "✓ 选取区域" else "选取区域"
    }

    /** 进入框选层：在原图上拖框选择要作为背景的区域 */
    private fun enterRegionEdit() {
        editingRegion = true
        regionView.initialRegion = region
        regionView.bitmap = CustomBackground.sourceBitmap(this)
        controlsPanel.visibility = View.GONE
        regionLayer.visibility = View.VISIBLE
    }

    private fun exitRegionEdit() {
        editingRegion = false
        regionLayer.visibility = View.GONE
        controlsPanel.visibility = View.VISIBLE
    }
}
