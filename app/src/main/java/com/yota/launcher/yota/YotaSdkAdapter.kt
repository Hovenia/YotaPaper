package com.yota.launcher.yota

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yota.launcher.LauncherActivity
import com.yotadevices.sdk.Epd
import com.yotadevices.sdk.EpdManager
import java.util.concurrent.Executors

/**
 * Adapter for the Yota SDK (compileOnly full-featured stub).
 * The real implementation is provided by the Yota system shared library
 * com.yotadevices.sdk (declared as <uses-library>).
 */
object YotaSdkAdapter {

    private const val TAG = "YotaSdkAdapter"

    private const val EPD_PARAMS_AUTHORITY = "com.baoliyota.epdparams.paramsprovider"
    private const val EPD_PARAMS_URI = "content://$EPD_PARAMS_AUTHORITY/params"
    private const val EPD_PARAMS_OPTOUT_ACTIVITY = "__paperlauncher_optout__"

    private val yotaDevice: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            Class.forName("com.yotadevices.sdk.EpdManager")
            true
        }.getOrElse { e ->
            Log.w(TAG, "isYotaDevice failed: ${e.message}")
            false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val optOutExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "epd-optout")
    }

    fun isYotaDevice(): Boolean = yotaDevice

    /**
     * 系统刷新管理 App（com.baoliyota.epdparams）会为当前前台 Activity 写入一行
     * 按 Activity 的 EPD 刷新参数。WindowManager 的 EpdUpdateParamsManager 在每次
     * 提交刷新参数时都会用这行参数覆盖应用自己设置的 updateType —— 只要这行存在，
     * 我们的翻页/开屏动画所需的 updateType=4 就会被改成 3 或 0，随后 customAnimation
     * 被系统丢弃，动画彻底失效。
     *
     * 系统 Provider 不支持 delete，所以这里把属于 LauncherActivity 的那一行
     * activity 改成一个不会匹配任何窗口标题的哨兵值，使系统不再覆盖我们的窗口。
     * 在 onCreate 与 onResume 调用：用户每次用完系统刷新管理回来，都会重新插入一行，
     * 因此需要在每次回到前台时再次中和。
     */
    fun optOutOfSystemEpdParams(context: Context) {
        val appContext = context.applicationContext
        val component = ComponentName(appContext, LauncherActivity::class.java).toString()
        val uri = Uri.parse(EPD_PARAMS_URI)
        runCatching {
            val resolver = appContext.contentResolver
            resolver.query(uri, arrayOf("activity"), "activity=?", arrayOf(component), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val values = ContentValues().apply {
                        put("activity", EPD_PARAMS_OPTOUT_ACTIVITY)
                    }
                    resolver.update(uri, values, "activity=?", arrayOf(component))
                    Log.i(TAG, "System EpdParams row neutralized for $component")
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "optOutOfSystemEpdParams failed: ${e.message}")
        }
    }

    /**
     * 在后台单线程执行 [optOutOfSystemEpdParams]，完成后切回主线程回调。
     * 避免冷启动时在 UI 线程上做 ContentProvider 查询/更新（首次访问该系统
     * Provider 可能触发其进程冷启动，阻塞主线程几十到几百毫秒）。
     */
    fun optOutOfSystemEpdParamsAsync(context: Context, onDone: (() -> Unit)? = null) {
        optOutExecutor.execute {
            optOutOfSystemEpdParams(context)
            if (onDone != null) {
                mainHandler.post { onDone() }
            }
        }
    }

    /**
     * Locks / turns off the back EPD screen via EpdManager.lockEpd().
     * On Yota3 the call can throw SecurityException even though the EPD
     * actually locks, so treat that case as success.
     */
    fun lockEpd(): String {
        return runCatching {
            EpdManager.getInstance().lockEpd()
            "已锁背屏"
        }.getOrElse { e ->
            val cause = e
            if (cause is SecurityException) {
                Log.w(TAG, "lockEpd threw SecurityException but EPD lock was requested; treat as success")
                "已锁背屏"
            } else {
                Log.e(TAG, "lockEpd failed", e)
                "锁屏失败: ${cause.javaClass.simpleName} ${cause.message}"
            }
        }
    }

    fun isEpdContext(context: Context): Boolean {
        return runCatching {
            Epd.isEpdContext(context)
        }.getOrElse { e ->
            Log.w(TAG, "isEpdContext failed: ${e.message}")
            false
        }
    }

    fun getDeviceColor(): Int? {
        return runCatching {
            Epd.getDeviceColor()
        }.getOrElse { e ->
            Log.w(TAG, "getDeviceColor failed: ${e.message}")
            null
        }
    }
}
