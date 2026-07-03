# Scroff Win7 构建脚本
# 自动定位 MSBuild 并构建 32 位 .NET Framework 4.8 版本（Win7/8/10 兼容）
#
# 用法：
#   .\build-win7.ps1                         # 默认 Release | x86
#   .\build-win7.ps1 -Configuration Debug
#   .\build-win7.ps1 -Platform x86 -Configuration Release

param(
    [string]$Configuration = "Release",
    [string]$Platform = "x86"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $projectRoot "windows\win7\Scroff.Win7.csproj"

if (-not (Test-Path $project)) {
    Write-Error "找不到项目文件: $project"
    exit 1
}

# --- 定位 MSBuild -----------------------------------------------------------
$msbuild = $null

# 1) 优先用 vswhere（VS 2017+ 标准定位方式，能跨盘符/版本找到 MSBuild）
$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (Test-Path $vswhere) {
    $found = & $vswhere -latest -requires Microsoft.Component.MSBuild -find "MSBuild\**\Bin\MSBuild.exe" |
             Where-Object { $_ } | Select-Object -First 1
    if ($found -and (Test-Path $found)) { $msbuild = $found }
}

# 2) 回退：扫描常见安装路径（含 C/D 盘）
if (-not $msbuild) {
    $drives = @(${env:ProgramFiles}, ${env:ProgramFiles(x86)}) | Where-Object { $_ } | Sort-Object -Unique
    foreach ($drive in $drives) {
        $base = Split-Path $drive -Parent  # 例如 C:\Program Files -> C:\
        foreach ($ver in @("2022", "2019")) {
            foreach ($ed in @("Enterprise", "Professional", "Community", "BuildTools")) {
                $c = Join-Path $base "Program Files\Microsoft Visual Studio\$ver\$ed\MSBuild\Current\Bin\MSBuild.exe"
                if (-not (Test-Path $c)) {
                    $c = Join-Path $base "Program Files (x86)\Microsoft Visual Studio\$ver\$ed\MSBuild\Current\Bin\MSBuild.exe"
                }
                if (Test-Path $c) { $msbuild = $c; break }
            }
            if ($msbuild) { break }
        }
        if ($msbuild) { break }
    }
}

if (-not $msbuild) {
    Write-Error @"
未找到 MSBuild。请确认已安装以下任一项（含 '.NET 桌面开发' 工作负载）：
  - Visual Studio 2019 / 2022
  - Visual Studio Build Tools 2019 / 2022
下载: https://visualstudio.microsoft.com/zh-hans/downloads/
"@
    exit 1
}

# --- 构建 -------------------------------------------------------------------
Write-Host "MSBuild  : $msbuild" -ForegroundColor Cyan
Write-Host "项目     : $project" -ForegroundColor Cyan
Write-Host "配置     : $Configuration | $Platform" -ForegroundColor Cyan
Write-Host ""

& $msbuild $project /t:Restore /p:Configuration=$Configuration /p:Platform=$Platform /v:minimal /nologo
if ($LASTEXITCODE -ne 0) {
    Write-Error "NuGet 还原失败 (ExitCode=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

& $msbuild $project /t:Build /p:Configuration=$Configuration /p:Platform=$Platform /v:minimal /nologo
if ($LASTEXITCODE -ne 0) {
    Write-Error "构建失败 (ExitCode=$LASTEXITCODE)"
    exit $LASTEXITCODE
}

$output = Join-Path $projectRoot "windows\win7\bin\$Platform\$Configuration"
if (-not (Test-Path $output)) {
    $output = Join-Path $projectRoot "windows\win7\bin\$Configuration"
}

Write-Host ""
Write-Host "构建成功！" -ForegroundColor Green
Write-Host "发布产物目录: $output" -ForegroundColor Green
Write-Host "将整个目录拷贝到 Win7 目标机器即可运行（.NET Framework 4.8 系统自带，无需额外依赖）。" -ForegroundColor Green
