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
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
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
import com.yota.launcher.data.LauncherConfigStore
import com.yota.launcher.utils.RootUtil
import com.yota.launcher.yota.EInkSdk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("NewApi", "MissingPermission", "SetTextI18n")
class ControlCenterActivity : Activity() {

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

    private var btAdapter: BluetoothAdapter? = null
    private var isClosing = false
    private var isOpenAnimationPlayed = false
    private var isRedirecting = false

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.yota.ACTION_CLOSE_PANEL" -> closePanel()

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
                    refreshNotifUI()
                }
                "com.yota.NOTIF_REMOVED" -> {
                    val key = intent.getStringExtra("key") ?: return
                    activeNotifications.removeAll { it.key == key }
                    refreshNotifUI()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(R.layout.layout_panel)
        EInkSdk.setUpdateMode(window.decorView, 0)
        findViewById<View>(android.R.id.content).setBackgroundColor(Color.WHITE)

        btAdapter = BluetoothAdapter.getDefaultAdapter()
        bindControlButtons()

        val filter = IntentFilter().apply {
            addAction("com.yota.ACTION_CLOSE_PANEL")
            addAction("com.yota.MEDIA_UPDATE")
            addAction("com.yota.MEDIA_CLEAR")
            addAction("com.yota.NEW_NOTIF")
            addAction("com.yota.NOTIF_REMOVED")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dataReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        sendBroadcast(Intent("com.yota.PANEL_OPENED"))
        pullCurrentState()

        if (!isOpenAnimationPlayed) {
            isOpenAnimationPlayed = true
            val config = LauncherConfigStore(this).load()
            if (config.controlCenterAnimation) {
                window.decorView.postDelayed({
                    EInkSdk.applyPageTurn(window.decorView, EInkSdk.ANIM_VERTICAL_BOTTOM)
                }, 100)
            }
        }
        window.decorView.postDelayed({ refreshNotifUI() }, 500)
    }

    override fun onPause() {
        super.onPause()
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(dataReceiver) }
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))
    }

    @Suppress("DEPRECATION")
    private fun closePanel() {
        if (isClosing) return
        isClosing = true
        sendBroadcast(Intent("com.yota.PANEL_CLOSED"))

        val config = LauncherConfigStore(this).load()
        if (config.controlCenterAnimation) {
            EInkSdk.applyPageTurn(window.decorView, EInkSdk.ANIM_VERTICAL_TOP)
        }

        findViewById<View>(android.R.id.content).visibility = View.INVISIBLE
        window.decorView.postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 350L)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = closePanel()

    private fun bindControlButtons() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        val btnWifi = findViewById<View>(R.id.btn_wifi)
        val btnBluetooth = findViewById<View>(R.id.btn_bluetooth)
        val btnSaver = findViewById<TextView>(R.id.btn_saver)

        btnWifi?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = wifiManager.isWifiEnabled
            wifiManager.isWifiEnabled = !currentState
            updateTogglesUI(wifiManager, btAdapter, powerManager)
            window.decorView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnWifi?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closePanel()
            true
        }

        btnBluetooth?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            val currentState = btAdapter?.isEnabled == true
            if (currentState) btAdapter?.disable() else btAdapter?.enable()
            updateTogglesUI(wifiManager, btAdapter, powerManager)
            window.decorView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnBluetooth?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closePanel()
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

            window.decorView.postDelayed({ updateTogglesUI(wifiManager, btAdapter, powerManager) }, 2000)
        }
        btnSaver?.setOnLongClickListener {
            simulateClickFeedback(it, changeColor = false)
            runCatching { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closePanel() }
            true
        }

        findViewById<View>(R.id.btn_settings)?.setOnClickListener {
            simulateClickFeedback(it, changeColor = false)
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            closePanel()
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeBar = findViewById<SeekBar>(R.id.volume_slider)
        val tvVolumePercent = findViewById<TextView>(R.id.tv_volume_percent)

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

        findViewById<View>(R.id.btn_media_prev)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            simulateClickFeedback(it, changeColor = true)
        }
        findViewById<TextView>(R.id.btn_media_play)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            simulateClickFeedback(it, changeColor = true)
        }
        findViewById<View>(R.id.btn_media_next)?.setOnClickListener {
            sendMediaCommand(KeyEvent.KEYCODE_MEDIA_NEXT)
            simulateClickFeedback(it, changeColor = true)
        }

        // 核心修复：扩大点击范围到整个卡片，加入双重保险强制拉起逻辑
        findViewById<View>(R.id.card_media)?.setOnClickListener {
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
                        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            startActivity(launchIntent)
                        }
                    }
                }
                closePanel()
            }
        }

        findViewById<View>(R.id.btn_clear_notif)?.setOnClickListener {
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
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun pullCurrentState() {
        val now = Date()
        findViewById<TextView>(R.id.tv_time)?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        findViewById<TextView>(R.id.tv_date)?.text = SimpleDateFormat("MM月dd日 E", Locale.CHINA).format(now)

        @Suppress("DEPRECATION")
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        findViewById<TextView>(R.id.tv_battery)?.text = "电量: $batLevel%"

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        updateTogglesUI(wifiManager, btAdapter, powerManager)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        findViewById<SeekBar>(R.id.volume_slider)?.progress = curVol
        findViewById<TextView>(R.id.tv_volume_percent)?.text = "${(curVol * 100) / maxVol}%"

        if (NotificationReaderService.isMediaAppActive) {
            updateMediaUI(NotificationReaderService.cachedAppName, NotificationReaderService.cachedTitle, NotificationReaderService.cachedArtist, NotificationReaderService.cachedIsPlaying)
        } else {
            clearMediaUI()
        }
        refreshNotifUI()
    }

    @Suppress("DEPRECATION")
    private fun updateTogglesUI(wifiManager: WifiManager, btAdapter: BluetoothAdapter?, powerManager: PowerManager) {
        val btnWifi = findViewById<View>(R.id.btn_wifi)
        val tvWifiTitle = findViewById<TextView>(R.id.tv_wifi_title)
        val tvWifiSub = findViewById<TextView>(R.id.tv_wifi_sub)

        val btnBt = findViewById<View>(R.id.btn_bluetooth)
        val tvBtTitle = findViewById<TextView>(R.id.tv_bt_title)
        val tvBtSub = findViewById<TextView>(R.id.tv_bt_sub)

        val btnSaver = findViewById<TextView>(R.id.btn_saver)

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
        findViewById<View>(R.id.card_media)?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tv_media_app_name)?.text = appName
        findViewById<TextView>(R.id.tv_media_title)?.text = title
        findViewById<TextView>(R.id.tv_media_artist)?.text = artist
        findViewById<TextView>(R.id.btn_media_play)?.text = if (isPlaying) "||" else "▷"
    }

    private fun clearMediaUI() {
        findViewById<View>(R.id.card_media)?.visibility = View.GONE
    }

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return listeners?.contains(packageName) == true
    }

    private fun grantNotificationPermissionViaRoot() {
        if (!RootUtil.isRootAvailable()) {
            openNotificationSettings()
            return
        }
        val component = "${packageName}/${NotificationReaderService::class.java.name}"
        val current = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (current.contains(component)) {
            refreshNotifUI()
            return
        }
        val newValue = if (current.isEmpty()) component else "$current:$component"
        Thread {
            try {
                val result = Shell.cmd("settings put secure enabled_notification_listeners \"$newValue\"").exec()
                runOnUiThread {
                    if (result.isSuccess) refreshNotifUI() else openNotificationSettings()
                }
            } catch (_: Exception) {
                runOnUiThread { openNotificationSettings() }
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
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivityForResult(intent, 1001)
        } catch (_: Exception) {
            isRedirecting = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            isRedirecting = false
            window.decorView.postDelayed({ refreshNotifUI() }, 600)
        }
    }

    private fun refreshNotifUI() {
        val container = findViewById<LinearLayout>(R.id.notification_container) ?: return
        val clearBtn = findViewById<View>(R.id.btn_clear_notif)

        container.removeAllViews()

        if (!isNotificationAccessGranted()) {
            clearBtn?.visibility = View.GONE
            val authBtn = TextView(this).apply {
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
            val emptyTv = TextView(this).apply {
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
                val notifView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))

                    val header = LinearLayout(this@ControlCenterActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL

                        val iconView = ImageView(this@ControlCenterActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
                            try {
                                setImageDrawable(packageManager.getApplicationIcon(item.pkg))
                            } catch(_: Exception) {
                                setImageResource(android.R.drawable.sym_def_app_icon)
                            }
                            val matrix = ColorMatrix().apply { setSaturation(0f) }
                            colorFilter = ColorMatrixColorFilter(matrix)
                        }
                        addView(iconView)

                        val titleView = TextView(this@ControlCenterActivity).apply {
                            this.text = item.title
                            textSize = 15f
                            setTextColor(Color.BLACK)
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        addView(titleView)

                        val timeView = TextView(this@ControlCenterActivity).apply {
                            this.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.time))
                            textSize = 12f
                            setTextColor(Color.GRAY)
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                rightMargin = dp(12)
                            }
                        }
                        addView(timeView)

                        val closeXBtn = TextView(this@ControlCenterActivity).apply {
                            text = "✕"
                            textSize = 14f
                            setTextColor(Color.GRAY)
                            setPadding(dp(8), dp(2), dp(8), dp(2))
                            setOnClickListener {
                                simulateClickFeedback(this, changeColor = true)
                                sendBroadcast(Intent("com.yota.CANCEL_NOTIF").putExtra("key", item.key))
                                activeNotifications.remove(item)
                                refreshNotifUI()
                            }
                        }
                        addView(closeXBtn)
                    }
                    addView(header)

                    val textView = TextView(this@ControlCenterActivity).apply {
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

                    val divider = View(this@ControlCenterActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) }
                        setBackgroundColor(Color.parseColor("#E0E0E0"))
                    }
                    addView(divider)

                    setOnClickListener {
                        simulateClickFeedback(this)
                        item.contentIntent?.let {
                            runCatching { it.send() }
                            closePanel()
                        }
                    }
                }
                container.addView(notifView)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}