﻿#!/usr/bin/env pwsh
# Scroff Server - Build Script (PowerShell + Gradle Kotlin DSL)
# Usage (run in project root):
#   .\build-server.ps1                # default bootJar
#   .\build-server.ps1 -Clean         # clean first then build
#   .\build-server.ps1 -OutputDir dist

[CmdletBinding()]
param(
    [switch]$Clean,
    [string]$OutputDir = 'dist',
    [string]$ServerDir = 'scroff-server'
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# 1. Check gradlew exists
$gradlew = Join-Path $ServerDir 'gradlew.bat'
if (-not (Test-Path $gradlew)) {
    Write-Error "Cannot find $gradlew, please check $ServerDir directory."
    exit 1
}

# 2. Check JDK 17 (wrap to bypass Stop on stderr)
$jdk = Get-Command java -ErrorAction SilentlyContinue
if ($jdk) {
    $prevPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $raw = (& java -version 2>&1 | Out-String)
    $ErrorActionPreference = $prevPref
    if ($raw -match 'version "(\d+)') {
        $v = [int]$Matches[1]
        if ($v -lt 17) {
            Write-Warning "Detected Java $v, Spring Boot 3.x requires JDK 17+"
        } else {
            Write-Host "Java $v OK" -ForegroundColor DarkGray
        }
    }
} else {
    Write-Warning "Java not detected, please install JDK 17 and add to PATH"
}

# 3. Set gradle user home inside server dir
$gradleHome = Join-Path $ServerDir '.gradle'
if (-not (Test-Path $gradleHome)) {
    New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null
}
$env:GRADLE_USER_HOME = (Resolve-Path $gradleHome).Path
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'

# 3.5 Clean up old Maven residue
$oldTarget = Join-Path $ServerDir 'target'
if (Test-Path $oldTarget) {
    Write-Host "Found old Maven target/, cleaning..." -ForegroundColor Yellow
    Remove-Item $oldTarget -Recurse -Force
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Scroff Server - Gradle Build" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ("Server  : {0}" -f $ServerDir) -ForegroundColor Cyan
$prevPref2 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVerLine = (& java -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $prevPref2
Write-Host ("Java    : {0}" -f $javaVerLine) -ForegroundColor Cyan
Write-Host ("Output  : {0}" -f $OutputDir) -ForegroundColor Cyan
Write-Host ""

# 4. Run build
$tasks = @('bootJar', '-x', 'test')
if ($Clean) {
    $tasks = @('clean', 'bootJar', '-x', 'test')
}

$logFile = Join-Path $PSScriptRoot 'build\.tmp\gradle.log'
$null = New-Item -ItemType Directory -Path (Split-Path $logFile) -Force
$serverFull = (Resolve-Path $ServerDir).Path
$gradleName = Split-Path $gradlew -Leaf
$quotedTasks = $tasks | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
$argLine = $quotedTasks -join ' '
Write-Host ("  -> Run: cd {0} && {1} {2}" -f $serverFull, $gradleName, $argLine) -ForegroundColor DarkGray
cmd /c "cd /d `"$serverFull`" && $gradleName $argLine 2>&1" |
    Tee-Object -FilePath $logFile |
    ForEach-Object { Write-Host $_ }
$gradleExit = $LASTEXITCODE
Write-Host (">>> gradle exit code: {0}" -f $gradleExit) -ForegroundColor Yellow
if ($gradleExit -ne 0) {
    Write-Error "Gradle build failed (ExitCode=$gradleExit). Log: $logFile"
    exit $gradleExit
}

# 5. Copy jar to output dir
$jarSrc = Join-Path $ServerDir 'build\libs\scroff-server.jar'
if (-not (Test-Path $jarSrc)) {
    Write-Error "Jar not found: $jarSrc"
    exit 1
}

$outDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $PSScriptRoot $OutputDir
}
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$destJar = Join-Path $outDir 'scroff-server.jar'
Copy-Item $jarSrc $destJar -Force

# Sync application.yml
$ymlSrc = Join-Path $ServerDir 'src\main\resources\application.yml'
if (Test-Path $ymlSrc) {
    Copy-Item $ymlSrc (Join-Path $outDir 'application.yml') -Force
}

# Sync deploy scripts
$deploySrc = Join-Path $ServerDir 'deploy\deploy-ubuntu.sh'
if (Test-Path $deploySrc) {
    Copy-Item $deploySrc (Join-Path $outDir 'deploy-ubuntu.sh') -Force
}

# Sync systemd unit file (for manual install path)
$unitSrc = Join-Path $ServerDir 'deploy\scroff-server.service'
if (Test-Path $unitSrc) {
    Copy-Item $unitSrc (Join-Path $outDir 'scroff-server.service') -Force
}

# Calculate jar size
$jarBytes = (Get-Item $destJar).Length
$jarSizeMB = [math]::Round($jarBytes / 1048576, 2)
$jarLine = "  [OK] scroff-server.jar ($jarSizeMB MB)"
$outPath = (Resolve-Path $outDir).Path
$scpLine = "  scp '$outPath\*' user@server:/tmp/"

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host " Build successful" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host $jarLine -ForegroundColor Green
Write-Host "  [OK] application.yml" -ForegroundColor Green
Write-Host "  [OK] deploy-ubuntu.sh" -ForegroundColor Green
Write-Host "  [OK] scroff-server.service" -ForegroundColor Green
Write-Host ""
Write-Host ("Output dir: " + $outPath) -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Yellow
Write-Host $scpLine -ForegroundColor Yellow
Write-Host "  ssh user@server 'sudo bash /tmp/deploy-ubuntu.sh'" -ForegroundColor Yellow