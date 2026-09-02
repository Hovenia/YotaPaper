package com.yota.launcher.utils

import java.io.DataOutputStream

object RootUtil {

    fun forceStopPackage(packageName: String): Boolean {
        return forceStopPackages(listOf(packageName))
    }

    /**
     * 使用单次 Root 会话批量强行停止多个应用，大幅提升执行速度
     */
    fun forceStopPackages(packageNames: List<String>): Boolean {
        if (packageNames.isEmpty()) return true
        var os: DataOutputStream? = null
        try {
            val process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            for (pkg in packageNames) {
                os.writeBytes("am force-stop $pkg\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            val exitValue = process.waitFor()
            return exitValue == 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try {
                os?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}