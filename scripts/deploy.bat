@echo off
setlocal enabledelayedexpansion

rem ---------------------------------------------------------------------------
rem Yota Paper - one-click deploy: build, install to device, grant usage access
rem ---------------------------------------------------------------------------

call "%~dp0build.bat"
if errorlevel 1 exit /b 1

set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

echo [INFO] Installing %APK% ...
"%ADB%" install -r "%APK%"
if errorlevel 1 (
    echo [ERROR] Install failed. Is a device connected and authorized?
    exit /b 1
)

echo [INFO] Granting usage-stats permission ...
"%ADB%" shell appops set com.yota.paperlauncher GET_USAGE_STATS allow

echo [INFO] Launching Yota Paper ...
"%ADB%" shell am start -n com.yota.launcher/.LauncherActivity

echo [OK] Deployed.
endlocal
