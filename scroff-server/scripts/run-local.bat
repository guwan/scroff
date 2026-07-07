@echo off
REM =============================================================
REM Scroff Server - Local Development Runner (Windows)
REM =============================================================
REM Usage:
REM   scripts\run-local.bat
REM
REM What it does:
REM   1. Ensures src\main\resources\application-local.yml exists.
REM      First run will copy from .example template and ask you to
REM      fill in your local DB password, then re-run.
REM   2. Sets SPRING_PROFILES_ACTIVE=local (env var) so Spring Boot
REM      loads application-local.yml. This is more reliable than
REM      passing -D to gradle.
REM   3. Runs `gradlew.bat bootRun`.
REM
REM Stop: Ctrl+C
REM =============================================================
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "SERVER_DIR=%SCRIPT_DIR%\.."
for %%I in ("%SERVER_DIR%") do set "SERVER_DIR=%%~fI"
set "RES_DIR=%SERVER_DIR%\src\main\resources"
set "LOCAL_YML=%RES_DIR%\application-local.yml"
set "EXAMPLE_YML=%RES_DIR%\application-local.yml.example"

cd /d "%SERVER_DIR%"

REM Tee output to a debug log AND stdout (sandbox-friendly)
set "DEBUG_LOG=%TEMP%\run-local-%RANDOM%.log"
> "%DEBUG_LOG%" echo [DEBUG] SCRIPT_DIR = %SCRIPT_DIR%
>> "%DEBUG_LOG%" echo [DEBUG] SERVER_DIR = %SERVER_DIR%
>> "%DEBUG_LOG%" echo [DEBUG] LOCAL_YML  = %LOCAL_YML%
if exist "%LOCAL_YML%" (>> "%DEBUG_LOG%" echo [DEBUG] LOCAL_YML exists) else (>> "%DEBUG_LOG%" echo [DEBUG] LOCAL_YML NOT FOUND)
echo [DEBUG] Log: %DEBUG_LOG%
type "%DEBUG_LOG%"

REM 1. Check local config
if not exist "%LOCAL_YML%" (
    echo ============================================================
    echo  [SETUP] application-local.yml not found.
    echo  [SETUP] Copying from .example template ...
    echo ============================================================
    copy /Y "%EXAMPLE_YML%" "%LOCAL_YML%" >nul
    echo.
    echo   -^> Created: %LOCAL_YML%
    echo.
    echo   *** Please open the file and replace YOUR_LOCAL_DB_PASSWORD
    echo       with your real MariaDB password, use quotes around the value.
    echo       Then run this script again.
    echo.
    exit /b 1
)

REM 2. Sanity check: warn if placeholder still there
findstr /C:"YOUR_LOCAL_DB_PASSWORD" "%LOCAL_YML%" >nul 2>&1
if not errorlevel 1 (
    echo ============================================================
    echo  [WARN] application-local.yml still contains placeholder:
    echo         YOUR_LOCAL_DB_PASSWORD
    echo  [WARN] Please replace it with your real DB password.
    echo ============================================================
    exit /b 1
)

REM 3. Set profile via env var (most reliable for Spring Boot).
REM    Also pass -D as a fallback.
set "SPRING_PROFILES_ACTIVE=local"

echo ============================================================
echo  Scroff Server - Local Dev
echo ============================================================
echo   Profile    : %SPRING_PROFILES_ACTIVE%  via env var
echo   Local yml  : %LOCAL_YML%
echo   Stop       : Ctrl+C
echo.
echo   Verify: after startup, you should see in the log:
echo     'The following 1 profile is active: "local"'
echo     '...Tomcat started on port 8880...'
echo.

call gradlew.bat bootRun -Dspring.profiles.active=local
endlocal
