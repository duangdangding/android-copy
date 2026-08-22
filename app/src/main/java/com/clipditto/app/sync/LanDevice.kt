package com.clipditto.app.sync

/**
 * 局域网中的一台设备。可能来自 NSD 实时发现（在线），也可能来自本地配对记录（可能离线）。
 */
data class LanDevice(
    val deviceId: String,
    var name: String,
    /** 设备型号（如 Xiaomi 2410DPN6CC），仅展示用；旧版配对记录里可能缺失（null） */
    var model: String? = null,
    var host: String? = null,
    var port: Int = 0,
    /** 对方是否开启了剪贴板共享（来自 NSD TXT /info） */
    var sharing: Boolean = false,
    /** 是否已与本机完成配对（持有配对码） */
    var paired: Boolean = false,
    /** 本机保存的对方配对码 */
    var token: String? = null,
    /** 当前是否在线（本次扫描是否发现） */
    var online: Boolean = false,
    /** 本机从该设备同步内容的最新时间戳 */
    var lastSync: Long = 0L
) {
    val displayName: String get() = if (name.isBlank()) "未知设备" else name
}
