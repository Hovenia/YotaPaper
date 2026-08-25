#!/usr/bin/env bash
# Yota Paper - one-click debug build (macOS / Linux)
set -euo pipefail
cd "$(dirname "$0")/.."

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"

if [ -z "$SDK_DIR" ]; then
    for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "/opt/android-sdk"; do
        if [ -d "$candidate" ]; then
            SDK_DIR="$candidate"
            break
        fi
    done
fi

if [ -z "$SDK_DIR" ]; then
    echo "[ERROR] Android SDK not found. Please set ANDROID_HOME and retry."
    exit 1
fi

echo "[INFO] Android SDK: $SDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] Java not found in PATH. JDK 17+ is required."
    exit 1
fi

echo "[INFO] Building debug APK..."
./gradlew assembleDebug --no-daemon

echo ""
echo "[OK] APK: app/build/outputs/apk/debug/app-debug.apk"
