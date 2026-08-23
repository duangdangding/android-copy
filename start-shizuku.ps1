# 一键启动所有已连接 Android 设备的 Shizuku 服务
# 用法：右键用 PowerShell 运行，或命令行执行  powershell -File start-shizuku.ps1
# 前提：设备已连电脑并开启 USB 调试（adb devices 能看到）

$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 列出所有已连接设备（跳过 "List of devices attached" 标题行）
$devices = & $adb devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] }

if (-not $devices) {
    Write-Host "没有发现已连接的设备，请检查数据线和 USB 调试" -ForegroundColor Red
    exit 1
}

foreach ($dev in $devices) {
    Write-Host "== $dev" -ForegroundColor Cyan

    # 检查 Shizuku 是否已安装
    $pkg = & $adb -s $dev shell pm path moe.shizuku.privileged.api 2>$null
    if (-not ($pkg -match "package:")) {
        Write-Host "   Shizuku 未安装，跳过" -ForegroundColor Yellow
        continue
    }

    # 已经在运行则跳过
    $running = & $adb -s $dev shell pidof shizuku_server
    if ($running) {
        Write-Host "   Shizuku 已在运行 (pid $running)" -ForegroundColor Green
        continue
    }

    # 解析原生库目录（v13+ 的启动器是 libshizuku.so，不再有 start.sh）
    $line = & $adb -s $dev shell pm dump moe.shizuku.privileged.api 2>$null |
        Select-String "legacyNativeLibraryDir" | Select-Object -First 1
    if (-not $line) {
        Write-Host "   无法获取安装路径，跳过" -ForegroundColor Red
        continue
    }
    $dir = ($line.ToString() -replace '^[^=]*=', '').Trim()
    $bin = "$dir/arm64/libshizuku.so"

    & $adb -s $dev shell $bin 2>&1 | ForEach-Object { Write-Host "   $_" }
}
