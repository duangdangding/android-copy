# release.ps1 — 一键发版脚本
#
# 流程：递增 versionCode、写入 versionName → 本地编译验证 → git 提交 → 打 tag → 推送
# 推送 tag 后 GitHub Actions（.github/workflows/release.yml）自动打包，
# 产物 lscopy_v<tag>.apk 会出现在 https://github.com/duangdangding/android-copy/releases
#
# 用法（在仓库根目录的 PowerShell 里执行）：
#   .\release.ps1 -Version 4.2 -Message "修复了xxx"
#   .\release.ps1 -Version 4.2                 # 提交信息默认为"发布 v4.2"
#   .\release.ps1 -Version 4.2 -SkipBuild      # 跳过本地编译验证（不推荐）
# 如果要重打已存在的 tag（比如修复工作流后重跑）：
#   git tag -d 4.2; git push origin :refs/tags/4.2; .\release.ps1 -Version 4.2 -Message "重跑"

param(
    [Parameter(Mandatory = $true)][string]$Version,   # 版本名，如 4.2（不带 v 前缀，与仓库现有 tag 风格一致）
    [string]$Message = "",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# ===== 0. 检查 tag 是否已存在（存在则停止，避免误重打） =====
git rev-parse -q --verify "refs/tags/$Version" | Out-Null
if ($LASTEXITCODE -eq 0) { throw "tag $Version 已存在。要重打请先执行：git tag -d $Version; git push origin :refs/tags/$Version" }

# ===== 1. 递增 versionCode、写入 versionName =====
$gradleFile = "app/build.gradle"
$content = [System.IO.File]::ReadAllText("$PSScriptRoot\$gradleFile")
if ($content -notmatch 'versionCode\s+(\d+)') { throw "在 $gradleFile 里找不到 versionCode" }
$newCode = [int]$Matches[1] + 1
$content = $content -replace 'versionCode\s+\d+', "versionCode $newCode"
$content = $content -replace 'versionName\s+"[^"]+"', "versionName ""$Version"""
# UTF-8 无 BOM 写回（带 BOM 可能让 Gradle 解析报错）
[System.IO.File]::WriteAllText("$PSScriptRoot\$gradleFile", $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "版本号：versionCode $newCode / versionName $Version"

# ===== 2. 本地编译验证（产物 app/build/outputs/apk/debug/剪贴板_v$Version.apk） =====
if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME -and (Test-Path "E:\environment\jdk21")) {
        $env:JAVA_HOME = "E:\environment\jdk21"   # 本机 JDK 17+ 的兜底路径
    }
    & "$PSScriptRoot\gradle-8.7\bin\gradle.bat" assembleDebug --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "编译失败，已停止。版本号改动可用 git checkout -- app/build.gradle 恢复" }
    Write-Host "本地编译通过"
}

# ===== 3. 提交 + 打 tag + 推送（推送 tag 触发云端打包） =====
git add -A
if (-not $Message) { $Message = "发布 v$Version" }
git commit -m $Message
git tag -a $Version -m "剪贴板 v$Version"
git push origin HEAD
git push origin $Version

Write-Host ""
Write-Host "完成。GitHub Actions 打包中，约 2~3 分钟后到这里下载 lscopy_v$Version.apk："
Write-Host "https://github.com/duangdangding/android-copy/releases/tag/$Version"
