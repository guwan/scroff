#!/usr/bin/env pwsh
# Android 一键打包脚本
# 默认构建 debug APK；通过参数可切换 release / 输出目录
#
# 用法（在项目根目录执行）：
#   .\build-android.ps1                                 # 默认 debug APK
#   .\build-android.ps1 -Variant release                # release APK（未签名）
#   .\build-android.ps1 -Variant debug -OutputDir dist  # 自定义输出目录

[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'debug',

    [string]$OutputDir = 'android\dist',

    [string]$AndroidDir = 'android'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# 1. 校验 gradlew 存在
$gradlew = Join-Path $AndroidDir 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Write-Error "找不到 $gradlew，请确认 $AndroidDir 目录结构完整。"
    exit 1
}

# 2. 校验 JDK 17（Android Gradle Plugin 8.x 强制要求）
$jdk = Get-Command java -ErrorAction SilentlyContinue
if ($jdk) {
    $v = (& java -version 2>&1 | Select-String -Pattern 'version "(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($v -and [int]$v -lt 17) {
        Write-Warning "检测到 Java 版本 $v，Android Gradle Plugin 8.x 需要 JDK 17 或更高。"
    }
} else {
    Write-Warning "未检测到 java 命令，请确认已安装 JDK 17 并加入 PATH。"
}

# 3. 设置 gradle user home 到沙箱内（避免默认 D:\gradle 写不进沙箱）
$gradleHome = Join-Path $AndroidDir '.gradle'
if (-not (Test-Path $gradleHome)) {
    New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null
}
$env:GRADLE_USER_HOME = (Resolve-Path $gradleHome).Path

# 4. 跑构建
$task = if ($Variant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
Write-Host "Android : $AndroidDir" -ForegroundColor Cyan
Write-Host "变体    : $Variant" -ForegroundColor Cyan
Write-Host "Task    : $task" -ForegroundColor Cyan
Write-Host "输出    : $OutputDir" -ForegroundColor Cyan
Write-Host "Gradle  : $gradleHome" -ForegroundColor Cyan
Write-Host ""

& $gradlew $task --no-daemon -p $AndroidDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "Android 构建失败 (ExitCode=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

# 5. 拷贝 APK 到自定义输出目录
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
$apkSrc = Join-Path $AndroidDir "app\build\outputs\apk\$Variant"
if (-not (Test-Path $apkSrc)) {
    Write-Error "未找到 APK 输出目录: $apkSrc"
    exit 1
}
$apkFiles = Get-ChildItem $apkSrc -Filter '*.apk'
foreach ($apk in $apkFiles) {
    $dest = Join-Path $OutputDir $apk.Name
    Copy-Item $apk.FullName $dest -Force
    Write-Host "  [√] $($apk.Name) ($([math]::Round($apk.Length / 1KB, 1)) KB)" -ForegroundColor Green
}

Write-Host ""
Write-Host "Android 构建成功！" -ForegroundColor Green
Write-Host "产物目录: $(Resolve-Path $OutputDir)" -ForegroundColor Green
switch ($Variant) {
    'debug'   { Write-Host "提示：debug APK 已签名（debug keystore），可直接安装测试。" -ForegroundColor Yellow }
    'release' { Write-Host "提示：release APK 是未签名的，不能直接安装。" -ForegroundColor Yellow
                Write-Host "      如需发布，请用 jarsigner 或 apksigner 用你的 keystore 签名后再安装。" -ForegroundColor Yellow }
}
