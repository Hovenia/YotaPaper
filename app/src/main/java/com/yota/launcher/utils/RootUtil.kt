package com.yota.launcher.utils

import android.util.Log
import com.topjohnwu.superuser.Shell

object RootUtil {

    // 在工具类加载时配置全局 Shell
    init {
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
    }

    /**
     * 检查当前是否具有 Root 权限（Shell 是否以 root 身份运行）
     */
    fun isRootAvailable(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot
        } catch (e: Exception) {
            Log.e("RootUtil", "isRootAvailable failed", e)
            false
        }
    }

    fun forceStopPackage(packageName: String): Boolean {
        return forceStopPackages(listOf(packageName))
    }

    /**
     * 使用 Libsu 全局长连接 Root Shell 批量强行停止应用。
     */
    fun forceStopPackages(packageNames: List<String>): Boolean {
        if (packageNames.isEmpty()) return true

        // 【关键修复】显式获取 Shell 并强制检查是否为 Root。
        // 这一步会强制阻塞并唤起 Magisk 授权弹窗。
        val shell = Shell.getShell()
        if (!shell.isRoot) {
            Log.e("RootUtil", "Root permission denied or not available.")
            return false // 用户拒绝，或者被 KernelSU 静默拒绝
        }

        val commands = packageNames.map { "am force-stop $it" }.toTypedArray()

        return try {
            val result = Shell.cmd(*commands).exec()
            result.isSuccess
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}