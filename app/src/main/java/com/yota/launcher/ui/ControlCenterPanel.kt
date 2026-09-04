package com.yota.launcher.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import com.yota.launcher.LauncherActivity
import com.yota.launcher.R
import com.yota.launcher.data.LauncherConfigStore
import com.yota.launcher.yota.EInkSdk
import com.yota.launcher.yota.YotaSdkAdapter

/**
 * 控制中心 · 主页窗口内浮层（并入 LauncherActivity，桌面场景专用）。
 *
 * 动画与主页 selectPage 完全同一机制：先 EInkSdk.applyPageTurn 武装整屏翻页动画，
 * 再切换本浮层可见性（真实内容变化），在同一已建立窗口内由带动画的那次 EPD
 * 更新一次呈现 —— 连贯、无“新窗口首帧吞动画/动画延后”问题。
 *
 * 规则：**只有这里播动画**（且仅在 controlCenterAnimation 开关开启、高画质刷新
 * 模式下）。其它应用前台时走的兜底 ControlCenterActivity 一律不播动画。
 *
 * UI 逻辑全部在共享的 [ControlCenterUi] 中，这里只是薄壳。
 */
class ControlCenterPanel(private val host: LauncherActivity) {

    private lateinit var ui: ControlCenterUi
    private lateinit var rootView: View

    val isVisible: Boolean
        get() = ::rootView.isInitialized && rootView.visibility == View.VISIBLE

    init {
        rootView = host.layoutInflater.inflate(R.layout.layout_panel, null)
        rootView.isClickable = true
        rootView.isFocusable = true
        rootView.visibility = View.GONE
        (host.window.decorView as? ViewGroup)?.addView(
            rootView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        ui = ControlCenterUi(host) { _ -> closePanel(animateToHome = false) }
        ui.bind(rootView)
    }

    /** 挂到 LauncherActivity：注册媒体/通知广播（onCreate 后调用）。 */
    fun attach() {
        ui.attachReceivers()
    }

    /** LauncherActivity onDestroy 时解挂。 */
    fun detach() {
        ui.detachReceivers()
    }

    /** 桌面下拉手势：浮层打开，播连贯翻页动画（若开关与刷新模式允许）。 */
    fun openPanel() {
        if (isVisible) return
        // 与主页 selectPage 同款：先武装动画，再在同一帧内完成“可见 + 内容刷新”，
        // 让带动画的那一次 EPD 更新一次呈现最新面板（不要在 GONE 时先重建内容，
        // 也不要在动画播完后追加刷新 —— 那都会造成第二段脏帧/二次刷新）。
        if (animationAllowed()) {
            EInkSdk.applyPageTurn(host.window.decorView, EInkSdk.ANIM_VERTICAL_BOTTOM)
        }
        rootView.visibility = View.VISIBLE
        ui.refreshAll()
        host.sendBroadcast(Intent("com.yota.PANEL_OPENED"))

        // 迟到的通知/状态（如通知读取刚同步完）在面板仍可见时补刷
        ui.scheduleNotificationRefresh(500)
    }

    /**
     * 关闭浮层。
     * @param animateToHome true：上拉/返回桌面 —— 播 ANIM_VERTICAL_TOP 收尾，桌面出现连贯；
     *                      false：点面板内入口跳去其它应用 —— 立即隐藏。
     */
    fun closePanel(animateToHome: Boolean) {
        if (!isVisible) return
        // 收起前取消待执行的延迟补刷：避免收起后仍在隐藏视图里重建内容、产生第二段 EPD 刷新
        ui.cancelScheduledRefresh()
        if (animateToHome && animationAllowed()) {
            EInkSdk.applyPageTurn(host.window.decorView, EInkSdk.ANIM_VERTICAL_TOP)
        }
        rootView.visibility = View.GONE
        host.sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
    }

    /** LauncherActivity onResume 时调用：从系统设置页返回后刷新授权/通知状态。 */
    fun onHostResume() {
        if (!isVisible) return
        ui.scheduleNotificationRefresh(300)
    }

    private fun animationAllowed(): Boolean {
        val config = LauncherConfigStore(host).load()
        return YotaSdkAdapter.isYotaDevice() &&
                config.controlCenterAnimation && config.refreshMode == 0
    }
}
