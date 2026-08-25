package com.yota.launcher.ui

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity
import com.yota.launcher.R

/**
 * Owns the status line under the clock: battery, WiFi, cellular and
 * Bluetooth. Click toggles WiFi/BT, long-press opens the matching system
 * settings page.
 */
class StatusBarController(private val activity: Activity) {

    private lateinit var battery: TextView
    private lateinit var wifiItem: View
    private lateinit var wifiIcon: ImageView
    private lateinit var wifiText: TextView
    private lateinit var network: TextView
    private lateinit var btItem: View
    private lateinit var btIcon: ImageView
    private lateinit var btText: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = update()
    }

    fun bind() {
        battery = activity.findViewById(R.id.statusBattery)
        wifiItem = activity.findViewById(R.id.statusWifiItem)
        wifiIcon = activity.findViewById(R.id.statusWifiIcon)
        wifiText = activity.findViewById(R.id.statusWifiText)
        network = activity.findViewById(R.id.statusNetwork)
        btItem = activity.findViewById(R.id.statusBtItem)
        btIcon = activity.findViewById(R.id.statusBtIcon)
        btText = activity.findViewById(R.id.statusBtText)
    }

    fun setup() {
        wifiItem.setOnClickListener { toggleWifi() }
        wifiItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            true
        }
        btItem.setOnClickListener { toggleBluetooth() }
        btItem.setOnLongClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            true
        }
        registerReceivers()
    }

    fun destroy() {
        runCatching { activity.unregisterReceiver(statusReceiver) }
    }

    @Suppress("DEPRECATION")
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            activity.registerReceiver(statusReceiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    fun update() {
        val ink = activity.resources.getColor(R.color.ink)
        val gray = activity.resources.getColor(R.color.gray)

        val level = batteryLevel()
        battery.text = if (level >= 0) "电量 $level%" else ""
        battery.setTextColor(gray)

        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiConnected = runCatching {
            cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
        }.getOrDefault(false)
        val wifiEnabled = runCatching {
            activity.getSystemService(Context.WIFI_SERVICE).let { (it as WifiManager).isWifiEnabled }
        }.getOrDefault(false)

        wifiText.text = when {
            wifiConnected -> {
                val wm = activity.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ssid = wm.connectionInfo?.ssid?.trim()?.removeSurrounding("\"")
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "WiFi" else ssid
            }
            wifiEnabled -> activity.getString(R.string.wifi_on)
            else -> activity.getString(R.string.wifi_off)
        }
        wifiText.setTextColor(if (wifiEnabled) ink else gray)
        wifiIcon.setColorFilter(if (wifiEnabled) ink else gray)

        val mobileConnected = runCatching {
            cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE)?.isConnected == true
        }.getOrDefault(false)
        if (!wifiConnected && mobileConnected) {
            network.text = "蜂窝"
            network.visibility = View.VISIBLE
        } else {
            network.text = ""
            network.visibility = View.GONE
        }

        val btOn = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
        }.getOrDefault(false)
        btText.text = activity.getString(if (btOn) R.string.bt_on else R.string.bt_off)
        btText.setTextColor(if (btOn) ink else gray)
        btIcon.setColorFilter(if (btOn) ink else gray)
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        runCatching {
            val wifi = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.setWifiEnabled(!wifi.isWifiEnabled)
        }
        wifiItem.postDelayed({ update() }, 600)
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth() {
        runCatching {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                if (adapter.isEnabled) adapter.disable() else adapter.enable()
            }
        }
        btItem.postDelayed({ update() }, 600)
    }

    private fun batteryLevel(): Int {
        return runCatching {
            val intent = activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else -1
        }.getOrDefault(-1)
    }
}
