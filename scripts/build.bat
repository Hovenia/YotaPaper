@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem Yota Paper - one-click debug build
rem Auto-detects Android SDK, then runs Gradle wrapper.
rem ---------------------------------------------------------------------------

cd /d "%~dp0.."

set "SDK_DIR="

if not "%ANDROID_HOME%"=="" set "SDK_DIR=%ANDROID_HOME%"
if "%SDK_DIR%"=="" if not "%ANDROID_SDK_ROOT%"=="" set "SDK_DIR=%ANDROID_SDK_ROOT%"

if "%SDK_DIR%"=="" if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"
if "%SDK_DIR%"=="" if exist "C:\Android\Sdk" set "SDK_DIR=C:\Android\Sdk"
rem Fallback: SDK kept inside the repo, next to it, or next to its parent folder.
if "%SDK_DIR%"=="" if exist "%~dp0..\android-sdk" set "SDK_DIR=%~dp0..\android-sdk"
if "%SDK_DIR%"=="" if exist "%~dp0..\..\android-sdk" set "SDK_DIR=%~dp0..\..\android-sdk"

if "%SDK_DIR%"=="" (
    echo [ERROR] Android SDK not found.
    echo Please set ANDROID_HOME to your Android SDK directory and retry.
    exit /b 1
)

echo [INFO] Android SDK: %SDK_DIR%
set "ANDROID_HOME=%SDK_DIR%"
set "ANDROID_SDK_ROOT=%SDK_DIR%"

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java not found in PATH. JDK 17+ is required.
    exit /b 1
)

echo [INFO] Building debug APK...
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo [ERROR] Build failed.
    exit /b 1
)

echo.
echo [OK] APK: app\build\outputs\apk\debug\app-debug.apk
endlocal
