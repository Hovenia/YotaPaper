# Yota Launcher ProGuard rules

# Keep Yota SDK API references only if any direct references are added later.
-keep class com.yotadevices.sdk.** { *; }

# Xposed module entry (loaded by name from assets/xposed_init).
-keep class com.yota.launcher.xposed.MainHook { *; }
-keep class com.yota.launcher.xposed.EpdPageTurnHook { *; }
-keep class com.yota.launcher.xposed.SwipeTracker { *; }
