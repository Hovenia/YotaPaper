@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem Yota Paper - one-click deploy: build, install to device, grant usage access
rem ---------------------------------------------------------------------------

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

call "%~dp0build.bat"
if errorlevel 1 exit /b 1

set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
set "ADB=%SDK_DIR%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo [INFO] Installing %APK% ...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo [ERROR] Install failed. Is a device connected and authorized?
    exit /b 1
)

echo [INFO] Granting usage-stats permission ...
"%ADB%" shell appops set com.yota.launcher GET_USAGE_STATS allow

echo [INFO] Launching Yota Paper ...
"%ADB%" shell am start -n com.yota.launcher/.LauncherActivity

echo [OK] Deployed.
endlocal
