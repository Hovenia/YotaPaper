package com.yota.launcher.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.topjohnwu.superuser.Shell
import com.yota.launcher.NotificationReaderService
import com.yota.launcher.R
import com.yota.launcher.utils.RootUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制中心 UI 逻辑的唯一实现（单一数据源）。
 *
 * 被两个“薄壳”宿主复用：
 *  - ControlCenterActivity：其它应用前台时的兜底独立窗口；
 *  - ControlCenterPanel：LauncherActivity（主页）窗口内的浮层。
 *
 * 本类只负责布局内容与全部交互逻辑（状态刷新、通知列表、媒体卡、
 * 授权流程、音量/开关……），不关心窗口形态与 EPD 动画 ——
 * 需要关闭面板时通过 [onDismiss] 回调通知宿主，由宿主决定“怎么关/是否播动画”。
 */
@SuppressLint("NewApi", "MissingPermission", "SetTextI18n")
class ControlCenterUi(
    /** 宿主 Activity（浮层场景=LauncherActivity；兜底场景=ControlCenterActivity）。 */
    private val activity: Activity,
    /** 宿主需要收起面板时回调；animateToHome=true 表示“回到桌面”，由宿主决定是否播收尾动画。 */
    private val onDismiss: (animateToHome: Boolean) -> Unit
) {

    data class NotificationItem(
        var key: String,
        val pkg: String,
        var title: String,
        var text: String,
        var time: Long,
        var contentIntent: PendingIntent?,
        var count: Int = 1
    )

    private val activeNotifications = mutableListOf<NotificationItem>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRefresh: Runnable? = null

    private lateinit var rootView: View
    private var btAdapter: BluetoothAdapter? = null
    private var isRedirecting = false

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.yota.MEDIA_UPDATE" -> {
                    val appName = intent.getStringExtra("appName") ?: NotificationReaderService.cachedAppName
                    val title = intent.getStringExtra("title") ?: NotificationReaderService.cachedTitle
                    val artist = intent.getStringExtra("artist") ?: NotificationReaderService.cachedArtist
                    val isPlaying = intent.getBooleanExtra("isPlaying", NotificationReaderService.cachedIsPlaying)
                    updateMediaUI(appName, title, artist, isPlaying)
                }
                "com.yota.MEDIA_CLEAR" -> clearMediaUI()

                "com.yota.NEW_NOTIF" -> {
                    val key = intent.getStringExtra("key") ?: return
                    val pkg = intent.getStringExtra("pkg") ?: ""
                    val title = intent.getStringExtra("title") ?: ""
                    val text = intent.getStringExtra("text") ?: ""
                    val time = intent.getLongExtra("time", System.currentTimeMillis())
                    @Suppress("DEPRECATION")
                    val contentIntent = intent.getParcelableExtra<PendingIntent>("contentIntent")

                    val existingIndex = activeNotifications.indexOfFirst { it.pkg == pkg }
                    if (existingIndex != -1) {
                        val item = activeNotifications[existingIndex]
                        item.key = key
                        item.title = title
                        item.time = time
                        item.contentIntent = contentIntent

                        if (!item.text.endsWith(text)) {
                            item.count++
                            item.text = "[${item.count}条消息] $text"
                        } else {
                            item.text = text
                        }
                        activeNotifications.removeAt(existingIndex)
                        activeNotifications.add(0, item)
                    } else {
                        activeNotifications.add(0, NotificationItem(key, pkg, title, text, time, contentIntent))
                    }

                    if (activeNotifications.size > 10) activeNotifications.removeAt(activeNotifications.lastIndex)
                    refreshNotifications()
                }
                "com.yota.NOTIF_REMOVED" -> {
                    val key = intent.getStringExtra("key") ?: return
                    activeNotifications.removeAll { it.key == key }
                    refreshNotifications()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 生命周期（宿主调用）

    /** 绑定根布局并绑定控件。宿主在 inflate 出 layout_panel 后调用一次。 */
    fun bind(root: View) {
        rootView = root
        btAdapter = BluetoothAdapter.getDefaultAdapter()
        bindControlButtons()
    }

    /** 注册媒体/通知广播（宿主 onCreate/attach 时调用）。 */
    fun attachReceivers() {
        val filter = IntentFilter().apply {
            addAction("com.yota.MEDIA_UPDATE")
            addAction("com.yota.MEDIA_CLEAR")
            addAction("com.yota.NEW_NOTIF")
            addAction("com.yota.NOTIF_REMOVED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(dataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(dataReceiver, filter)
        }
    }

    /** 注销广播（宿主 onDestroy/detach 时调用）。 */
    fun detachReceivers() {
        runCatching { activity.unregisterReceiver(dataReceiver) }
    }

    /** 打开/回到面板时刷新全部状态（时间、电量、开关、音量、媒体、通知）。 */
    fun refreshAll() {
        pullCurrentState()
    }

    /** 只刷新通知列表（授权状态变化/返回设置页后等场景）。 */
    fun refreshNotifications() {
        refreshNotifUI()
    }

    /** 延迟补刷一次通知（等广播数据/系统状态就绪）；再次调用会先取消上一次。 */
    fun scheduleNotificationRefresh(delayMs: Long) {
        pendingRefresh?.let { rootView.removeCallbacks(it) }
        val runnable = Runnable { refreshNotifUI() }
        pendingRefresh = runnable
        rootView.postDelayed(runnable, delayMs)
    }

    /** 取消尚未执行的延迟补刷（收起面板前调用，避免隐藏后仍去重建内容造成二次脏帧）。 */
    fun cancelScheduledRefresh() {
        pendingRefresh?.let { rootView.removeCallbacks(it) }
        pendingRefresh = null
    }

    // ------------------------------------------------------------------ 控件绑定

    private fun bindControlButtons() {
        val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager

        val btnWifi = rootView.findViewById<View>(R.id.btn_wifi)
        val btnBluetooth = rootView.findViewById<View>(R.id.btn_bluetooth)
        val btnSaver = rootView.findViewById<TextView>(R.id.btn_saver)

        btnWifi?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = wifiManager.isWifiEnabled
            wifiManager.isWifiEnabled = !currentState
            updateTogglesUI(wifiManager, btAdapter, powerManager)
            rootView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnWifi?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
            true
        }

        btnBluetooth?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = btAdapter?.isEnabled == true
            if (currentState) btAdapter?.disable() else btAdapter?.enable()
            updateTogglesUI(wifiManager, btAdapter, powerManager)
            rootView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnBluetooth?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
            true
        }

        btnSaver?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val isSaverOn = powerManager.isPowerSaveMode
            val newState = if (isSaverOn) 0 else 1

            btnSaver.setBackgroundResource(if (isSaverOn) R.drawable.bg_btn_off else R.drawable.bg_btn_on)
            btnSaver.setTextColor(if (isSaverOn) Color.BLACK else Color.WHITE)

            Thread {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global low_power $newState && am broadcast -a android.os.action.POWER_SAVE_MODE_CHANGED"))
                }
            }.start()

            rootView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnSaver?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onDismiss(false)
            }
            true
        }

        rootView.findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onDismiss(false)
        }

        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeBar = rootView.findViewById<SeekBar>(R.id.volume_slider)
        val tvVolumePercent = rootView.findViewById<TextView>(R.id.tv_volume_percent)

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar?.max = maxVol
        volumeBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    tvVolumePercent?.text = "${(progress * 100) / maxVol}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        rootView.findViewById<View>(R.id.btn_media_prev)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            simulateClickFeedback(it, changeColor = true)
        }
        rootView.findViewById<TextView>(R.id.btn_media_play)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            simulateClickFeedback(it, changeColor = true)
        }
        rootView.findViewById<View>(R.id.btn_media_next)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT)
            simulateClickFeedback(it, changeColor = true)
        }

        // 核心修复：扩大点击范围到整个卡片，加入双重保险强制拉起逻辑
        rootView.findViewById<View>(R.id.card_media)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            runCatching {
                val pendingIntent = NotificationReaderService.cachedIntent
                if (pendingIntent != null) {
                    // 如果软件老实提供了，正常跳转
                    pendingIntent.send()
                } else {
                    // 如果软件没提供，强制通过包名暴力拉起
                    val pkg = NotificationReaderService.cachedPkg
                    if (pkg.isNotEmpty()) {
                        val launchIntent = activity.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            activity.startActivity(launchIntent)
                        }
                    }
                }
                onDismiss(false)
            }
        }

        rootView.findViewById<View>(R.id.btn_clear_notif)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            NotificationReaderService.instance?.cancelAllNotifications()
            activeNotifications.clear()
            refreshNotifUI()
        }
    }

    private fun simulateClickFeedback(view: View, changeColor: Boolean = false) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (changeColor && view is TextView) {
            val originalColor = view.currentTextColor
            view.setTextColor(Color.GRAY)
            view.postDelayed({ view.setTextColor(originalColor) }, 200)
        }
    }

    private fun sendMediaCommand(keyCode: Int) {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ------------------------------------------------------------------ 状态刷新

    private fun pullCurrentState() {
        val now = Date()
        rootView.findViewById<TextView>(R.id.tv_time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        rootView.findViewById<TextView>(R.id.tv_date)?.text = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(now)

        @Suppress("DEPRECATION")
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        rootView.findViewById<TextView>(R.id.tv_battery)?.text = "电量: $batLevel%"

        val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        updateTogglesUI(wifiManager, btAdapter, powerManager)

        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        rootView.findViewById<SeekBar>(R.id.volume_slider)?.progress = curVol
        rootView.findViewById<TextView>(R.id.tv_volume_percent)?.text = "${(curVol * 100) / maxVol}%"

        if (NotificationReaderService.isMediaAppActive) {
            updateMediaUI(NotificationReaderService.cachedAppName, NotificationReaderService.cachedTitle, NotificationReaderService.cachedArtist, NotificationReaderService.cachedIsPlaying)
        } else {
            clearMediaUI()
        }
        refreshNotifUI()
    }

    @Suppress("DEPRECATION")
    private fun updateTogglesUI(wifiManager: WifiManager, btAdapter: BluetoothAdapter?, powerManager: PowerManager) {
        val btnWifi = rootView.findViewById<View>(R.id.btn_wifi)
        val tvWifiTitle = rootView.findViewById<TextView>(R.id.tv_wifi_title)
        val tvWifiSub = rootView.findViewById<TextView>(R.id.tv_wifi_sub)

        val btnBt = rootView.findViewById<View>(R.id.btn_bluetooth)
        val tvBtTitle = rootView.findViewById<TextView>(R.id.tv_bt_title)
        val tvBtSub = rootView.findViewById<TextView>(R.id.tv_bt_sub)

        val btnSaver = rootView.findViewById<TextView>(R.id.btn_saver)

        tvWifiSub?.visibility = View.VISIBLE
        tvBtSub?.visibility = View.VISIBLE

        if (wifiManager.isWifiEnabled) {
            btnWifi?.setBackgroundResource(R.drawable.bg_btn_on)
            tvWifiTitle?.setTextColor(Color.WHITE)
            tvWifiSub?.setTextColor(Color.WHITE)
            val info = wifiManager.connectionInfo
            if (info != null && info.networkId != -1 && !info.ssid.isNullOrEmpty() && info.ssid != "<unknown ssid>") {
                tvWifiSub?.text = info.ssid.replace("\"", "")
            } else {
                tvWifiSub?.text = "已开启"
            }
        } else {
            btnWifi?.setBackgroundResource(R.drawable.bg_btn_off)
            tvWifiTitle?.setTextColor(Color.BLACK)
            tvWifiSub?.setTextColor(Color.BLACK)
            tvWifiSub?.text = "已关闭"
        }

        if (btAdapter?.isEnabled == true) {
            btnBt?.setBackgroundResource(R.drawable.bg_btn_on)
            tvBtTitle?.setTextColor(Color.WHITE)
            tvBtSub?.setTextColor(Color.WHITE)
            tvBtSub?.text = "已开启"
        } else {
            btnBt?.setBackgroundResource(R.drawable.bg_btn_off)
            tvBtTitle?.setTextColor(Color.BLACK)
            tvBtSub?.setTextColor(Color.BLACK)
            tvBtSub?.text = "已关闭"
        }

        if (powerManager.isPowerSaveMode) {
            btnSaver?.setBackgroundResource(R.drawable.bg_btn_on)
            btnSaver?.setTextColor(Color.WHITE)
        } else {
            btnSaver?.setBackgroundResource(R.drawable.bg_btn_off)
            btnSaver?.setTextColor(Color.BLACK)
        }
    }

    private fun updateMediaUI(appName: String, title: String, artist: String, isPlaying: Boolean = false) {
        rootView.findViewById<View>(R.id.card_media)?.visibility = View.VISIBLE
        rootView.findViewById<TextView>(R.id.tv_media_app_name)?.text = appName
        rootView.findViewById<TextView>(R.id.tv_media_title)?.text = title
        rootView.findViewById<TextView>(R.id.tv_media_artist)?.text = artist
        rootView.findViewById<TextView>(R.id.btn_media_play)?.text = if (isPlaying) "||" else "▷"
    }

    private fun clearMediaUI() {
        rootView.findViewById<View>(R.id.card_media)?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 通知读取权限

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners")
        return listeners?.contains(activity.packageName) == true
    }

    private fun grantNotificationPermissionViaRoot() {
        if (!RootUtil.isRootAvailable()) {
            openNotificationSettings()
            return
        }
        val component = "${activity.packageName}/${NotificationReaderService::class.java.name}"
        val current = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners") ?: ""
        if (current.contains(component)) {
            refreshNotifUI()
            return
        }
        val newValue = if (current.isEmpty()) component else "$current:$component"
        Thread {
            try {
                val result = Shell.cmd("settings put secure enabled_notification_listeners \"$newValue\"").exec()
                mainHandler.post {
                    if (result.isSuccess) refreshNotifUI() else openNotificationSettings()
                }
            } catch (_: Exception) {
                mainHandler.post { openNotificationSettings() }
            }
        }.start()
    }

    private fun openNotificationSettings() {
        if (isRedirecting) return
        if (isNotificationAccessGranted()) {
            refreshNotifUI()
            return
        }
        isRedirecting = true
        try {
            // 两处宿主都不是以“结果码”方式等待返回的（浮层场景宿主是 LauncherActivity），
            // 统一用“跳转 + 定时解除防抖 + 宿主 onResume 时补刷”的方式。
            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            rootView.postDelayed({ isRedirecting = false }, 1500)
        } catch (_: Exception) {
            isRedirecting = false
        }
    }

    // ------------------------------------------------------------------ 通知列表

    private fun refreshNotifUI() {
        // 隐藏时只维护数据、不重建视图：避免在面板收起后仍触发窗口脏帧/二次 EPD 更新
        if (rootView.visibility != View.VISIBLE) return
        val container = rootView.findViewById<LinearLayout>(R.id.notification_container) ?: return
        val clearBtn = rootView.findViewById<View>(R.id.btn_clear_notif)

        container.removeAllViews()

        if (!isNotificationAccessGranted()) {
            clearBtn?.visibility = View.GONE
            val authBtn = TextView(activity).apply {
                text = "⚠ 点击开启「通知读取」权限\n才能显示消息和音乐卡片"
                textSize = 14f
                setTextColor(Color.parseColor("#E53935"))
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
                setOnClickListener {
                    simulateClickFeedback(this)
                    if (RootUtil.isRootAvailable()) grantNotificationPermissionViaRoot() else openNotificationSettings()
                }
            }
            container.addView(authBtn)
            return
        }

        if (activeNotifications.isEmpty()) {
            clearBtn?.visibility = View.GONE
            val emptyTv = TextView(activity).apply {
                text = "暂无通知"
                textSize = 14f
                setTextColor(Color.GRAY)
                setPadding(0, dp(20), 0, dp(20))
                gravity = Gravity.CENTER
            }
            container.addView(emptyTv)
        } else {
            clearBtn?.visibility = View.VISIBLE
            for (item in activeNotifications) {
                val notifView = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))

                    val header = LinearLayout(this@ControlCenterUi.activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val iconView = ImageView(this@ControlCenterUi.activity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
                            try {
                                setImageDrawable(activity.packageManager.getApplicationIcon(item.pkg))
                            } catch(_: Exception) {
                                setImageResource(android.R.drawable.sym_def_app_icon)
                            }
                            val matrix = ColorMatrix().apply { setSaturation(0f) }
                            colorFilter = ColorMatrixColorFilter(matrix)
                        }
                        addView(iconView)

                        val titleView = TextView(this@ControlCenterUi.activity).apply {
                            this.text = item.title
                            textSize = 15f
                            setTextColor(Color.BLACK)
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(titleView)

                        val timeView = TextView(this@ControlCenterUi.activity).apply {
                            this.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.time))
                            textSize = 12f
                            setTextColor(Color.GRAY)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                rightMargin = dp(12)
                            }
                        }
                        addView(timeView)

                        val closeXBtn = TextView(this@ControlCenterUi.activity).apply {
                            text = "✕"
                            textSize = 14f
                            setTextColor(Color.GRAY)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                            setOnClickListener {
                                simulateClickFeedback(this, changeColor = true)
                                activity.sendBroadcast(Intent("com.yota.CANCEL_NOTIF").putExtra("key", item.key))
                                activeNotifications.remove(item)
                                refreshNotifUI()
                            }
                        }
                        addView(closeXBtn)
                    }
                    addView(header)

                    val textView = TextView(this@ControlCenterUi.activity).apply {
                        this.text = item.text
                        textSize = 13f
                        setTextColor(Color.DKGRAY)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            topMargin = dp(4)
                            leftMargin = dp(26)
                        }
                    }
                    addView(textView)

                    val divider = View(this@ControlCenterUi.activity).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) }
                        setBackgroundColor(Color.parseColor("#E0E0E0"))
                    }
                    addView(divider)

                    setOnClickListener {
                        simulateClickFeedback(this)
                        item.contentIntent?.let {
                            runCatching { it.send() }
                            onDismiss(false)
                        }
                    }
                }
                container.addView(notifView)
            }
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
