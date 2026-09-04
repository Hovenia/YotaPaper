package com.yota.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import com.yota.launcher.R
import com.yota.launcher.yota.EInkSdk

/**
 * 控制中心 · 兜底独立窗口（其它应用在前台时由 ControlCenterService 拉起）。
 *
 * 规则：**任何情况下不播 EPD 翻页动画** —— 新窗口首帧无法承载连贯动画，
 * 既然做不到连贯就干脆瞬时开合，避免“先亮一帧/动画延后”的观感。
 * 主页（桌面）场景请走 ControlCenterPanel（LauncherActivity 窗口内浮层）。
 *
 * UI 逻辑全部在共享的 [ControlCenterUi] 中，这里只是薄壳。
 */
@SuppressLint("NewApi", "MissingPermission", "SetTextI18n")
class ControlCenterActivity : Activity() {

    private lateinit var ui: ControlCenterUi
    private var isClosing = false

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.yota.ACTION_CLOSE_PANEL") dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        EInkSdk.setUpdateMode(window.decorView, 0)

        val root = layoutInflater.inflate(R.layout.layout_panel, null)
        setContentView(root)
        root.setBackgroundColor(Color.WHITE)

        ui = ControlCenterUi(this) { _ -> dismiss() }
        ui.bind(root)
        ui.attachReceivers()

        val filter = IntentFilter("com.yota.ACTION_CLOSE_PANEL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(closeReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent("com.yota.PANEL_OPENED"))
        ui.refreshAll()
        ui.scheduleNotificationRefresh(500)
    }

    override fun onPause() {
        super.onPause()
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(closeReceiver) }
        ui.detachReceivers()
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
    }

    private fun dismiss() {
        if (isClosing) return
        isClosing = true
        ui.cancelScheduledRefresh()
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
        // 瞬时关闭，无延迟无动画
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = dismiss()
}
