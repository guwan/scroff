# Scroff Win10/11 构建脚本
# 自动定位项目并发布 64 位 .NET 8 版本（Win 10/11 64位）
#
# 用法：
#   .\build-win10.ps1                            # 默认 Release | win-x64
#   .\build-win10.ps1 -Configuration Debug
#   .\build-win10.ps1 -Rid win-x64 -SelfContained $false

param(
    [string]$Configuration = "Release",
    [string]$Rid = "win-x64",
    [bool]$SelfContained = $false
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $projectRoot "windows\Scroff\Scroff.csproj"

if (-not (Test-Path $project)) {
    Write-Error "找不到项目文件: $project"
    exit 1
}

# --- 定位 dotnet -----------------------------------------------------------
$dotnet = $null
$cmd = Get-Command dotnet -ErrorAction SilentlyContinue
if ($cmd) {
    $dotnet = $cmd.Source
} else {
    $candidates = @(
        "${env:ProgramFiles}\dotnet\dotnet.exe",
        "${env:ProgramFiles(x86)}\dotnet\dotnet.exe",
        "$env:LOCALAPPDATA\Microsoft\dotnet\dotnet.exe"
    ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if ($candidates) { $dotnet = $candidates }
}

if (-not $dotnet) {
    Write-Error @"
未找到 dotnet CLI。请安装 .NET 8 SDK：
  https://dotnet.microsoft.com/zh-cn/download/dotnet/8.0
"@
    exit 1
}

# --- 发布 ------------------------------------------------------------------
$selfContainedArg = if ($SelfContained) { "true" } else { "false" }
$outputDir = Join-Path $projectRoot "windows\publish"

Write-Host "dotnet    : $dotnet" -ForegroundColor Cyan
Write-Host "项目     : $project" -ForegroundColor Cyan
Write-Host "配置     : $Configuration | $Rid | self-contained=$selfContainedArg" -ForegroundColor Cyan
Write-Host "输出目录  : $outputDir" -ForegroundColor Cyan
Write-Host ""

& $dotnet publish $project `
    -c $Configuration `
    -r $Rid `
    --self-contained $selfContainedArg `
    -o $outputDir
if ($LASTEXITCODE -ne 0) {
    Write-Error "发布失败 (ExitCode=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

Write-Host ""
Write-Host "发布成功！" -ForegroundColor Green
Write-Host "产物目录: $outputDir" -ForegroundColor Green
Write-Host "将整个目录拷贝到 Win 10/11 64位 目标机器即可运行。" -ForegroundColor Green
if (-not $SelfContained) {
    Write-Host "（目标机器需安装 .NET 8 Desktop Runtime：https://dotnet.microsoft.com/zh-cn/download/dotnet/8.0）" -ForegroundColor Yellow
}
