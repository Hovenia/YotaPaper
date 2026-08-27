package com.yota.launcher

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewStub
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.yota.launcher.data.AppRepository
import com.yota.launcher.epd.EpdManagerActivity
import com.yota.launcher.epd.EpdParamsStore
import com.yota.launcher.data.LauncherConfig
import com.yota.launcher.data.LauncherConfigStore
import com.yota.launcher.ui.EInkLauncherView
import com.yota.launcher.ui.IconLoader
import com.yota.launcher.ui.SettingsController
import com.yota.launcher.ui.StatusBarController
import com.yota.launcher.yota.EInkSdk
import com.yota.launcher.yota.YotaSdkAdapter
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

object StartupTracer {
    private const val TAG = "StartupTrace"
    private var last = 0L

    fun start() {
        last = android.os.SystemClock.elapsedRealtime()
    }

    fun stage(name: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "[$name] +${now - last}ms")
        last = now
    }
}

class LauncherActivity : Activity() {

    private lateinit var store: LauncherConfigStore
    private lateinit var repository: AppRepository
    private lateinit var config: LauncherConfig

    private lateinit var statusController: StatusBarController
    private lateinit var settingsController: SettingsController

    // Pages
    private lateinit var pageHome: View
    private lateinit var pageApps: View
    private lateinit var pageSettings: View

    // Home
    private lateinit var textTime: TextView
    private lateinit var textDate: TextView
    private lateinit var gridHome: EInkLauncherView

    // Apps
    private lateinit var gridApps: EInkLauncherView
    private lateinit var textPage: TextView
    private lateinit var manageToggle: TextView
    private var appsAll: List<ResolveInfo> = emptyList()
    private var appsPageIndex = 0
    private var appsPageCount = 0
    private var currentPage = -1 // 记录当前所在主页页面

    // Non-Yota fallback: all EPD SDK features are skipped and the
    // refresh/animation settings group is hidden.
    private var yotaDevice = false
    private val epdObserverHandler = Handler(Looper.getMainLooper())
    private val epdObserverRunnable = Runnable { syncEpdParamsFromProvider() }
    private val epdProviderObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            epdObserverHandler.removeCallbacks(epdObserverRunnable)
            epdObserverHandler.postDelayed(epdObserverRunnable, 150)
        }
    }
    private var manageMode = false
    private val manageSelected = mutableSetOf<String>()
    private val uninstallQueue = mutableListOf<String>()

    // Bottom bar
    private lateinit var bottomBar: View
    private lateinit var infoTime: TextView
    private lateinit var tabHome: View
    private lateinit var tabApps: View
    private lateinit var tabSettings: View
    private lateinit var tabHomeLabel: TextView
    private lateinit var tabAppsLabel: TextView
    private lateinit var tabSettingsLabel: TextView
    private lateinit var dotHome: View
    private lateinit var dotApps: View
    private lateinit var dotSettings: View
    private lateinit var btnLock: View

    // Manage bar
    private lateinit var manageBar: View
    private lateinit var manageHide: TextView
    private lateinit var manageShow: TextView
    private lateinit var manageUninstall: TextView
    private lateinit var manageDone: TextView

    // Action sheet
    private lateinit var actionOverlay: View
    private lateinit var actionTitle: TextView
    private lateinit var actionPin: TextView
    private lateinit var actionHide: TextView
    private lateinit var actionAppInfo: TextView
    private var selectedApp: ResolveInfo? = null

    // Recent tasks overlay
    private lateinit var recentOverlay: View
    private lateinit var recentPanel: View
    private lateinit var recentMessage: TextView
    private lateinit var recentGrid: EInkLauncherView
    private lateinit var recentCancel: TextView
    private lateinit var recentClear: TextView
    private var previousApp: ResolveInfo? = null
    private var lastHomeIntentTime = 0L
    private var lastHomeClickTime = 0L

    // Info overlay (help / about)
    private lateinit var infoOverlay: View
    private lateinit var infoTitle: TextView
    private lateinit var infoText: TextView
    private lateinit var infoClose: View

    // Selection overlay (settings multi-option rows)
    private lateinit var selectOverlay: View
    private lateinit var selectTitle: TextView
    private lateinit var selectList: LinearLayout
    private var selectCallback: ((Int) -> Unit)? = null

    // WiFi book transfer overlay
    private lateinit var wifiOverlay: View
    private lateinit var wifiQr: ImageView
    private lateinit var wifiUrlText: TextView
    private lateinit var wifiHintText: TextView
    private var wifiServer: WifiTransferServer? = null

    // First-use guide overlay
    private lateinit var guideOverlay: View
    private lateinit var guideStart: View

    // Donation QR
    private lateinit var donationQr: ImageView
    private lateinit var guideDonationQr: ImageView
    private lateinit var donationPromptQr: ImageView
    private var donationQrReady = false

    // Settings page is inflated on first open (large XML, lazy)
    private var settingsInflated = false

    // Apps page layout is inflated on first open (small, but keeps main layout minimal)
    private var appsInflated = false

    // Apps grid lazy loading
    private var appsLoaded = false

    // Skip duplicate home refresh on the first onResume after cold start
    private var coldStartResume = true

    // Donation prompt (50 HOME presses)
    private lateinit var donationOverlay: View
    private lateinit var donationClose: TextView
    private var donationCloseEnabled = false

    // About-page switch for the 50-home donation prompt
    private lateinit var rowDonationPrompt: View
    private lateinit var settingsDonationPromptValue: TextView
    private val donationCloseRunnable = Runnable {
        donationCloseEnabled = true
        donationClose.text = getString(R.string.info_close)
        donationClose.setTextColor(getColor(R.color.ink))
        donationClose.setOnClickListener { donationOverlay.visibility = View.GONE }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart ?: return
            Log.i(TAG, "package changed: $pkg")
            repository.invalidatePackage(pkg)
            IconLoader.invalidatePackage(pkg)
            refreshHome()
            // Apps 页如果已经加载过才刷新；没加载的话下次懒加载自然会拿到新列表。
            if (appsLoaded) refreshApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTracer.start()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        StartupTracer.stage("setContentView")

        store = LauncherConfigStore(this)
        repository = AppRepository(this)
        config = store.load()
        yotaDevice = YotaSdkAdapter.isYotaDevice()
        StartupTracer.stage("store/repo/config")

        if (yotaDevice) {
            // 中和系统刷新管理写入的行必须做，但不要阻塞首帧：放到后台单线程，
            // 完成后立刻重放一次窗口刷新模式，保证翻页动画不受系统参数覆盖。
            YotaSdkAdapter.optOutOfSystemEpdParamsAsync(this) {
                if (!isFinishing) applyRefreshMode(config)
            }
            startupSyncEpdParams()
            contentResolver.registerContentObserver(
                Uri.parse(EpdParamsStore.URI), true, epdProviderObserver
            )
        }
        StartupTracer.stage("epd-observe/optout")

        IconLoader.initialize(applicationContext)
        IconLoader.configure(config)
        StartupTracer.stage("iconloader-init")

        bindViews()
        StartupTracer.stage("bindViews")

        // 二维码生成约 0.9s：首帧后再在后台生成，绝不阻塞冷启动。
        window.decorView.post { ensureDonationQr() }
        StartupTracer.stage("postDonationQr")

        applyRefreshMode(config)
        StartupTracer.stage("applyRefreshMode")

        setupControllers()
        StartupTracer.stage("setupControllers")

        setupTabs()
        StartupTracer.stage("setupTabs")

        setupLock()
        StartupTracer.stage("setupLock")

        setupActionSheet()
        StartupTracer.stage("setupActionSheet")

        setupRecentOverlay()
        StartupTracer.stage("setupRecentOverlay")

        setupInfoOverlay()
        StartupTracer.stage("setupInfoOverlay")

        setupSelectOverlay()
        StartupTracer.stage("setupSelectOverlay")

        setupWifiTransfer()
        StartupTracer.stage("setupWifiTransfer")

        setupManage()
        StartupTracer.stage("setupManage")

        setupGrids()
        StartupTracer.stage("setupGrids")

        refreshHome()
        StartupTracer.stage("refreshHome")

        // Apps 页是第二屏：不在冷启动首帧生成 20 个图标。首帧后再加载，
        // 若用户先点到 apps 页则 selectPage 里会立即加载。
        epdObserverHandler.postDelayed({
            if (!appsLoaded) refreshApps()
        }, 500)
        StartupTracer.stage("postRefreshApps")

        selectPage(PAGE_HOME, animate = false)
        StartupTracer.stage("selectPage")

        registerTimeReceiver()
        registerPackageReceiver()
        updateClock()
        statusController.update()
        StartupTracer.stage("clock/status")

        // The cold-start HOME intent counts as the first press.
        lastHomeIntentTime = System.currentTimeMillis()

        maybeShowGuide()
        StartupTracer.stage("guide")
    }

    /** 首次使用引导：只显示一次，点击「开始使用」后关闭。 */
    private fun maybeShowGuide() {
        if (store.isGuideShown()) return
        guideOverlay.visibility = View.VISIBLE
        guideStart.setOnClickListener {
            guideOverlay.visibility = View.GONE
            store.setGuideShown()
        }
    }

    override fun onDestroy() {
        if (yotaDevice) {
            contentResolver.unregisterContentObserver(epdProviderObserver)
            epdObserverHandler.removeCallbacks(epdObserverRunnable)
        }
        super.onDestroy()
        window.decorView.removeCallbacks(donationCloseRunnable)
        statusController.destroy()
        runCatching { unregisterReceiver(timeReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
        wifiServer?.stop()
        wifiServer = null
    }

    /** 系统 Provider 变化（例如用户在系统刷新管理里改了某个应用）→ 合并进本地持久化。 */
    private fun syncEpdParamsFromProvider() {
        if (!yotaDevice || !store.autoApplyEpdParamsEnabled()) return
        val launcherComponent = ComponentName(this, LauncherActivity::class.java).toString()
        Thread {
            EpdParamsStore.mergeFromProvider(this, launcherComponent)
        }.start()
    }

    /** 启动时：先合并系统 Provider 的手动改动，再应用本地保存的全部条目。 */
    private fun startupSyncEpdParams() {
        if (!yotaDevice || !store.autoApplyEpdParamsEnabled()) return
        val launcherComponent = ComponentName(this, LauncherActivity::class.java).toString()
        // 延迟到首帧之后再执行，避免冷启动早期与 UI 抢 Provider/IO。
        epdObserverHandler.postDelayed({
            Thread {
                EpdParamsStore.syncAndApply(this, launcherComponent)
            }.start()
        }, 300)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeIntent(intent)
    }

    /**
     * The only way a launcher can "see" the physical HOME key is through the
     * HOME intent the system sends to its home activity. Two HOME intents
     * arriving in a short window are treated as a double press.
     */
    private fun handleHomeIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_MAIN) return
        val isHome = intent.categories?.contains(Intent.CATEGORY_HOME) == true ||
                intent.categories?.contains(EPD_HOME_CATEGORY) == true
        if (!isHome) return

        // Donation prompt is modal-ish: ignore further HOME presses while it is up.
        if (donationOverlay.visibility == View.VISIBLE) return

        val now = System.currentTimeMillis()
        if (recentOverlay.visibility == View.VISIBLE) {
            hideRecentTasks()
            lastHomeIntentTime = 0L
            return
        }
        if (now - lastHomeIntentTime in 1..799) {
            lastHomeIntentTime = 0L
            showRecentTasks()
        } else {
            lastHomeIntentTime = now
        }

        maybeShowDonationPrompt()
    }

    /** Count HOME presses; every 50 show the donation prompt once (unless disabled in About). */
    private fun maybeShowDonationPrompt() {
        if (!store.isDonationPromptEnabled()) return
        val count = store.incrementHomePressCount()
        Log.d(TAG, "home press count=$count")
        if (count >= 50) {
            store.resetHomePressCount()
            showDonationPrompt()
        }
    }

    private fun showDonationPrompt() {
        ensureDonationQr()
        donationCloseEnabled = false
        donationClose.text = getString(R.string.donation_close_wait)
        donationClose.setTextColor(getColor(R.color.gray))
        donationClose.setOnClickListener(null)
        donationOverlay.visibility = View.VISIBLE
        window.decorView.postDelayed(donationCloseRunnable, 5000L)
    }

    override fun onResume() {
        StartupTracer.stage("onResume-enter")
        super.onResume()
        if (yotaDevice) {
            YotaSdkAdapter.optOutOfSystemEpdParamsAsync(this) {
                if (!isFinishing) applyRefreshMode(config)
            }
        }
        val newConfig = store.load()
        val configChanged = newConfig != config
        if (configChanged) {
            config = newConfig
            settingsController.updateValues(config)
            applyRefreshMode(config)
            refreshHome()
            refreshApps()
        } else {
            applyRefreshMode(config)
            // 冷启动首帧前的 onCreate 已刷新过 home；这里只处理“从别的应用回来”的 usage 重排。
            if (!coldStartResume) refreshHome()
        }
        updateClock()
        statusController.update()
        coldStartResume = false
        StartupTracer.stage("onResume-before-screenAnim")
        maybePlayScreenOnAnimation()
        StartupTracer.stage("onResume-after-screenAnim")
    }

    /** 开屏动画：按开关与样式，播放一次开屏动画（跟随主模式，仅高画质模式播放）。 */
    private fun maybePlayScreenOnAnimation() {
        if (!yotaDevice) {
            Log.d(TAG, "screenOnAnimation: skipped (not a Yota device)")
            return
        }
        if (!config.screenOnAnimation) return
        if (config.refreshMode != 0) {
            Log.d(TAG, "screenOnAnimation: skipped (refreshMode=${config.refreshMode})")
            return
        }
        val anim = animationForStyle(config.screenOnAnimationStyle)
        if (anim == EInkSdk.ANIM_OFF) return
        Log.d(TAG, "screenOnAnimation: style=${config.screenOnAnimationStyle} anim=$anim")
        // Arm before the next draw (same pre-rebind timing as page-turn).
        EInkSdk.applyScreenAnimation(window.decorView, anim)
    }

    /** 样式编号 1..6 -> EInkSdk 动画预设。 */
    private fun animationForStyle(style: Int): Int = when (style) {
        2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
        3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
        4 -> EInkSdk.ANIM_VERTICAL_TOP
        5 -> EInkSdk.ANIM_VERTICAL_OPEN
        6 -> EInkSdk.ANIM_VERTICAL_CLOSE
        else -> EInkSdk.ANIM_HORIZONTAL_LEFT
    }

    // ------------------------------------------------------------------ Setup

    private fun bindViews() {
        pageHome = findViewById(R.id.pageHome)
        // pageApps 由 ensureAppsPage()、pageSettings 由 ensureSettingsPage() 懒加载

        textTime = findViewById(R.id.textTime)
        textDate = findViewById(R.id.textDate)
        gridHome = findViewById(R.id.gridHome)

        // gridApps / textPage / manageToggle 在 ensureAppsPage() 中绑定

        bottomBar = findViewById(R.id.bottomBar)
        infoTime = findViewById(R.id.infoTime)
        tabHome = findViewById(R.id.tabHome)
        tabApps = findViewById(R.id.tabApps)
        tabSettings = findViewById(R.id.tabSettings)
        tabHomeLabel = findViewById(R.id.tabHomeLabel)
        tabAppsLabel = findViewById(R.id.tabAppsLabel)
        tabSettingsLabel = findViewById(R.id.tabSettingsLabel)
        dotHome = findViewById(R.id.dotHome)
        dotApps = findViewById(R.id.dotApps)
        dotSettings = findViewById(R.id.dotSettings)
        btnLock = findViewById(R.id.btnLock)

        manageBar = findViewById(R.id.manageBar)
        manageHide = findViewById(R.id.manageHide)
        manageShow = findViewById(R.id.manageShow)
        manageUninstall = findViewById(R.id.manageUninstall)
        manageDone = findViewById(R.id.manageDone)

        actionOverlay = findViewById(R.id.actionOverlay)
        actionTitle = findViewById(R.id.actionTitle)
        actionPin = findViewById(R.id.actionPin)
        actionHide = findViewById(R.id.actionHide)
        actionAppInfo = findViewById(R.id.actionAppInfo)

        recentOverlay = findViewById(R.id.recentOverlay)
        recentPanel = findViewById(R.id.recentPanel)
        recentMessage = findViewById(R.id.recentMessage)
        recentGrid = findViewById(R.id.recentGrid)
        recentCancel = findViewById(R.id.recentCancel)
        recentClear = findViewById(R.id.recentClear)

        infoOverlay = findViewById(R.id.infoOverlay)
        infoTitle = findViewById(R.id.infoTitle)
        infoText = findViewById(R.id.infoText)
        infoClose = findViewById(R.id.infoClose)

        selectOverlay = findViewById(R.id.selectOverlay)
        selectTitle = findViewById(R.id.selectTitle)
        selectList = findViewById(R.id.selectList)

        wifiOverlay = findViewById(R.id.wifiOverlay)
        wifiQr = findViewById(R.id.wifiQr)
        wifiUrlText = findViewById(R.id.wifiUrlText)
        wifiHintText = findViewById(R.id.wifiHintText)

        guideOverlay = findViewById(R.id.guideOverlay)
        guideStart = findViewById(R.id.guideStart)

        donationQr = findViewById(R.id.donationQr)
        guideDonationQr = findViewById(R.id.guideDonationQr)

        donationOverlay = findViewById(R.id.donationOverlay)
        donationPromptQr = findViewById(R.id.donationPromptQr)
        donationClose = findViewById(R.id.donationClose)

        // rowDonationPrompt / settingsDonationPromptValue 在 ensureSettingsPage() 中懒加载
    }

    /** 生成赞赏二维码并设置到关于/使用说明、首次引导与 50 次 HOME 弹窗。 */
    private fun ensureDonationQr() {
        if (donationQrReady) return
        Thread {
            val qr = QrCodes.generate(DONATION_WECHAT_URI, dp(240))
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                donationQr.setImageBitmap(qr)
                guideDonationQr.setImageBitmap(qr)
                donationPromptQr.setImageBitmap(qr)
                donationQrReady = true
            }
        }.start()
    }

    private fun setupControllers() {
        statusController = StatusBarController(this).also {
            it.bind()
            it.setup()
        }
    }

    /** Apps 页懒加载：inflate page_apps.xml 并绑定网格/管理入口。 */
    private fun ensureAppsPage() {
        if (appsInflated) return
        findViewById<ViewStub>(R.id.pageAppsStub).inflate()
        pageApps = findViewById(R.id.pageApps)
        gridApps = findViewById(R.id.gridApps)
        textPage = findViewById(R.id.textPage)
        manageToggle = findViewById(R.id.manageToggle)
        appsInflated = true

        manageToggle.setOnClickListener {
            if (manageMode) exitManageMode() else enterManageMode()
        }
        gridApps.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(info: ResolveInfo) {
                if (manageMode) toggleManageSelect(info) else launchApp(info)
            }

            override fun onItemLongClick(info: ResolveInfo) {
                if (!manageMode) showActionSheet(info)
            }
        })

        // 修改边界滑动的响应，让手势可以切换三大主页
        gridApps.setOnPageChangeListener(object : EInkLauncherView.OnPageChangeListener {
            override fun onNextPage() {
                if (appsPageIndex < appsPageCount - 1) showAppsPage(appsPageIndex + 1)
                else selectPage(PAGE_SETTINGS, animate = true) // 最后一页继续左滑去设置
            }
            override fun onPrevPage() {
                if (appsPageIndex > 0) showAppsPage(appsPageIndex - 1)
                else selectPage(PAGE_HOME, animate = true) // 第一页继续右滑去主页
            }
        })
    }

    /** 设置页懒加载：inflate 大块设置布局并建立 SettingsController。 */
    private fun ensureSettingsPage() {
        if (settingsInflated) return
        findViewById<ViewStub>(R.id.pageSettingsStub).inflate()
        pageSettings = findViewById(R.id.pageSettings)
        rowDonationPrompt = findViewById(R.id.rowDonationPrompt)
        settingsDonationPromptValue = findViewById(R.id.settingsDonationPromptValue)
        settingsInflated = true

        settingsController = SettingsController(
            this,
            { config },
            ::onConfigChanged,
            ::showSelectOverlay,
            showYotaSettings = yotaDevice
        ).also {
            it.bind()
            it.setup(config)
        }

        if (yotaDevice) {
            findViewById<View>(R.id.rowEpdManager).setOnClickListener {
                startActivity(Intent(this, EpdManagerActivity::class.java))
            }
        } else {
            findViewById<View>(R.id.rowEpdManager).visibility = View.GONE
        }

        findViewById<View>(R.id.rowHelp).setOnClickListener {
            rowDonationPrompt.visibility = View.GONE
            showInfo(getString(R.string.settings_help), getString(R.string.help_text))
        }
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            rowDonationPrompt.visibility = View.VISIBLE
            updateDonationPromptValue()
            showInfo(getString(R.string.settings_about), getString(R.string.about_text))
        }
        rowDonationPrompt.setOnClickListener {
            store.setDonationPromptEnabled(!store.isDonationPromptEnabled())
            updateDonationPromptValue()
        }

        // 给设置页（ScrollView）加上全局手势检测，允许右滑退回到应用网格
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 80
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) { // 向右滑 (手指从左往右移)，回到应用页
                        selectPage(PAGE_APPS, animate = true)
                        return true
                    }
                }
                return false
            }
        })
        pageSettings.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // 放行，保证原本 ScrollView 的上下滑动不受影响
        }
    }

    private fun onConfigChanged(newConfig: LauncherConfig, affected: Int) {
        config = newConfig
        store.save(config)
        settingsController.updateValues(config)

        if (affected and SettingsController.AFFECT_ICONS != 0) {
            IconLoader.configure(config)
            refreshHome()
            refreshApps()
        }
        if (affected and SettingsController.AFFECT_HOME != 0) refreshHome()
        if (affected and SettingsController.AFFECT_APPS != 0) refreshApps()
        if (affected and SettingsController.AFFECT_REFRESH != 0) applyRefreshMode(config)
    }

    private fun applyRefreshMode(cfg: LauncherConfig = config) {
        if (!yotaDevice) {
            Log.d(TAG, "applyRefreshMode: skipped (not a Yota device)")
            return
        }
        val mode = cfg.refreshMode.coerceIn(0, 2)
        Log.d(TAG, "applyRefreshMode: mode=$mode")
        EInkSdk.setUpdateMode(window.decorView, mode)
    }

    // ------------------------------------------------------------------ Home / Apps grids

    private fun setupGrids() {
        gridHome.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(info: ResolveInfo) = launchApp(info)
            override fun onItemLongClick(info: ResolveInfo) = showActionSheet(info)
        })
        // 主页向左滑进入应用页
        gridHome.setOnPageChangeListener(object : EInkLauncherView.OnPageChangeListener {
            override fun onNextPage() = selectPage(PAGE_APPS, animate = true)
            override fun onPrevPage() = Unit // 主页已经是第一页，向右滑不做处理
        })
    }

    private fun refreshHome() {
        val apps = repository.loadApps()
        StartupTracer.stage("refreshHome-loadApps")
        gridHome.configure(config.homeColumns, config.homeRows, config.iconSize) // 传入图标大小
        val limit = config.homeColumns * config.homeRows
        val sorted = repository.sortHomeApps(apps, limit)
        StartupTracer.stage("refreshHome-sort")
        gridHome.setApps(
            pageApps = sorted,
            selection = emptySet(),
            manageMode = false,
            showDividers = config.showHomeDividers,
            hidden = emptySet()
        )
        StartupTracer.stage("refreshHome-setApps")
    }

    private fun refreshApps() {
        ensureAppsPage()
        appsLoaded = true
        appsAll = if (manageMode) repository.loadAllApps() else repository.loadApps()
        StartupTracer.stage("refreshApps-load")
        gridApps.configure(config.columns, config.rows, config.iconSize) // 传入图标大小
        appsPageCount =
            if (appsAll.isEmpty()) 0
            else ceil(appsAll.size.toDouble() / (config.columns * config.rows)).toInt()
        showAppsPage(0)
        StartupTracer.stage("refreshApps-showPage")
    }

    private fun showAppsPage(index: Int) {
        val pageSize = config.columns * config.rows
        val clamped = index.coerceIn(0, (appsPageCount - 1).coerceAtLeast(0))
        val previous = appsPageIndex
        appsPageIndex = clamped
        val start = clamped * pageSize
        val end = minOf(start + pageSize, appsAll.size)
        val page: MutableList<ResolveInfo?> = appsAll.subList(start, end).toMutableList()
        while (page.size < pageSize) page.add(null)

        gridApps.setApps(
            pageApps = page,
            selection = manageSelected,
            manageMode = manageMode,
            showDividers = config.showAppDividers,
            hidden = repository.hiddenPackages()
        )
        val pageText = if (appsPageCount > 0) "${clamped + 1}/$appsPageCount" else "0/0"
        if (textPage.text != pageText) textPage.text = pageText

        if (previous != clamped) {
            val delta = clamped - previous
            val anim = when (config.pageAnimation) {
                2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
                3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
                4 -> if (delta > 0) EInkSdk.ANIM_VERTICAL_BOTTOM else EInkSdk.ANIM_VERTICAL_TOP
                5 -> EInkSdk.ANIM_VERTICAL_OPEN
                6 -> EInkSdk.ANIM_VERTICAL_CLOSE
                0 -> EInkSdk.ANIM_OFF
                else -> if (delta > 0) EInkSdk.ANIM_HORIZONTAL_RIGHT else EInkSdk.ANIM_HORIZONTAL_LEFT
            }
            if (yotaDevice && config.refreshMode == 0) {
                Log.d(TAG, "showAppsPage: pageChanged $previous -> $clamped delta=$delta type=${config.pageAnimation} anim=$anim")
                EInkSdk.applyPageTurn(gridApps, anim)
            } else {
                Log.d(TAG, "showAppsPage: pageChanged $previous -> $clamped, animation skipped (yotaDevice=$yotaDevice, refreshMode=${config.refreshMode})")
            }
        } else {
            Log.d(TAG, "showAppsPage: page unchanged ($clamped), no animation")
        }
    }

    // ------------------------------------------------------------------ Tabs

    private fun setupTabs() {
        tabHome.setOnClickListener { onHomeClick() }
        tabApps.setOnClickListener { selectPage(PAGE_APPS, animate = true) }
        tabSettings.setOnClickListener { selectPage(PAGE_SETTINGS, animate = true) }
    }

    private fun onHomeClick() {
        if (recentOverlay.visibility == View.VISIBLE) {
            hideRecentTasks()
            lastHomeClickTime = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHomeClickTime in 1..799) {
            lastHomeClickTime = 0L
            showRecentTasks()
        } else {
            lastHomeClickTime = now
            selectPage(PAGE_HOME, animate = true) // 改为带动画返回
        }
    }

    // 重构的跨主页切换及动画逻辑
    private fun selectPage(page: Int, animate: Boolean = false) {
        if (page == currentPage) return

        val previous = currentPage
        currentPage = page

        if (page == PAGE_APPS) {
            ensureAppsPage()
            if (!appsLoaded) refreshApps()
        }
        if (page == PAGE_SETTINGS) {
            ensureSettingsPage()
        }

        // 处理跨三大主页的动画，跟随 Yota 高画质模式
        if (animate && previous != -1 && yotaDevice && config.refreshMode == 0) {
            val delta = page - previous
            val anim = when (config.pageAnimation) {
                2 -> EInkSdk.ANIM_HORIZONTAL_OPEN
                3 -> EInkSdk.ANIM_HORIZONTAL_CLOSE
                4 -> if (delta > 0) EInkSdk.ANIM_VERTICAL_BOTTOM else EInkSdk.ANIM_VERTICAL_TOP
                5 -> EInkSdk.ANIM_VERTICAL_OPEN
                6 -> EInkSdk.ANIM_VERTICAL_CLOSE
                0 -> EInkSdk.ANIM_OFF
                else -> if (delta > 0) EInkSdk.ANIM_HORIZONTAL_RIGHT else EInkSdk.ANIM_HORIZONTAL_LEFT
            }
            EInkSdk.applyPageTurn(window.decorView, anim)
        }

        pageHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.GONE
        if (::pageApps.isInitialized) {
            pageApps.visibility = if (page == PAGE_APPS) View.VISIBLE else View.GONE
        }
        if (::pageSettings.isInitialized) {
            pageSettings.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.GONE
        }

        tabHomeLabel.setTextColor(color(if (page == PAGE_HOME) R.color.ink else R.color.gray))
        tabAppsLabel.setTextColor(color(if (page == PAGE_APPS) R.color.ink else R.color.gray))
        tabSettingsLabel.setTextColor(color(if (page == PAGE_SETTINGS) R.color.ink else R.color.gray))

        dotHome.visibility = if (page == PAGE_HOME) View.VISIBLE else View.INVISIBLE
        dotApps.visibility = if (page == PAGE_APPS) View.VISIBLE else View.INVISIBLE
        dotSettings.visibility = if (page == PAGE_SETTINGS) View.VISIBLE else View.INVISIBLE

        if (::manageToggle.isInitialized) {
            manageToggle.visibility = if (page == PAGE_APPS) View.VISIBLE else View.GONE
        }
    }

    // ------------------------------------------------------------------ Lock

    private fun setupLock() {
        btnLock.setOnClickListener { lockScreen() }
        // 左下角时间：点击触发一次手动全刷（清除残影）
        infoTime.setOnClickListener { EInkSdk.manualFullRefresh(infoTime) }
    }

    // ------------------------------------------------------------------ Manage mode

    private fun setupManage() {
        // manageToggle 的监听在 ensureAppsPage() 中懒加载后绑定
        manageDone.setOnClickListener { exitManageMode() }
        manageHide.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                repository.batchSetHidden(manageSelected, true)
                toast("已隐藏 ${manageSelected.size} 个应用")
                manageSelected.clear()
                refreshApps()
            }
        }
        manageShow.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                repository.batchSetHidden(manageSelected, false)
                toast("已显示 ${manageSelected.size} 个应用")
                manageSelected.clear()
                refreshApps()
            }
        }
        manageUninstall.setOnClickListener {
            if (manageSelected.isEmpty()) {
                toast("请先选择应用")
            } else {
                uninstallQueue.clear()
                uninstallQueue.addAll(manageSelected)
                manageSelected.clear()
                startNextUninstall()
            }
        }
    }

    private fun enterManageMode() {
        manageMode = true
        manageSelected.clear()
        manageToggle.text = getString(R.string.manage_done)
        bottomBar.visibility = View.GONE
        manageBar.visibility = View.VISIBLE
        refreshApps()
    }

    private fun exitManageMode() {
        manageMode = false
        manageSelected.clear()
        manageToggle.text = getString(R.string.manage)
        bottomBar.visibility = View.VISIBLE
        manageBar.visibility = View.GONE
        refreshApps()
    }

    private fun toggleManageSelect(info: ResolveInfo) {
        val pkg = info.activityInfo.packageName
        if (manageSelected.contains(pkg)) manageSelected.remove(pkg) else manageSelected.add(pkg)
        gridApps.updateSelection(manageSelected)
    }

    /**
     * 逐个打开系统卸载页：一次只 start 一个，等 onActivityResult 回来再处理下一个，
     * 避免之前 forEach 连开多个 ACTION_DELETE 只有最后一个生效、其余被丢弃的问题。
     */
    private fun startNextUninstall() {
        if (uninstallQueue.isEmpty()) {
            exitManageMode()
            refreshApps()
            toast("卸载流程结束")
            return
        }
        val pkg = uninstallQueue.removeAt(0)
        runCatching {
            startActivityForResult(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")),
                REQ_UNINSTALL
            )
        }.onFailure { e ->
            Log.w(TAG, "start uninstall failed for $pkg: ${e.message}")
            toast("无法卸载 $pkg：${e.message}")
            startNextUninstall()
        }
    }

    // ------------------------------------------------------------------ Action sheet

    private fun setupActionSheet() {
        findViewById<View>(R.id.actionDim).setOnClickListener { hideActionSheet() }
        findViewById<View>(R.id.actionClose).setOnClickListener { hideActionSheet() }
        actionPin.setOnClickListener {
            selectedApp?.let { togglePin(it) }
            hideActionSheet()
        }
        actionHide.setOnClickListener {
            selectedApp?.let { hideApp(it) }
            hideActionSheet()
        }
        actionAppInfo.setOnClickListener {
            selectedApp?.let { showAppInfo(it) }
            hideActionSheet()
        }
    }

    private fun showActionSheet(info: ResolveInfo) {
        selectedApp = info
        actionTitle.text = info.loadLabel(packageManager)
        actionPin.text = getString(
            if (repository.isPinned(info.activityInfo.packageName)) R.string.action_unpin
            else R.string.action_pin
        )
        actionHide.text = getString(R.string.action_hide)
        actionAppInfo.text = getString(R.string.action_app_info)
        actionOverlay.visibility = View.VISIBLE
    }

    private fun hideActionSheet() {
        actionOverlay.visibility = View.GONE
        selectedApp = null
    }

    // ------------------------------------------------------------------ Recent tasks

    /**
     * Lock entry point: on Yota devices lock the back EPD screen via the SDK;
     * on other devices fall back to DevicePolicyManager.lockNow() through our
     * minimal device admin (force-lock only).
     */
    private fun lockScreen() {
        if (yotaDevice) {
            if (config.screenOffAnimation && config.refreshMode == 0) {
                val anim = animationForStyle(config.screenOffAnimationStyle)
                if (anim != EInkSdk.ANIM_OFF) {
                    Log.d(TAG, "screenOffAnimation: style=${config.screenOffAnimationStyle} anim=$anim")
                    EInkSdk.applyScreenAnimation(window.decorView, anim)
                    // Let the close animation play, then turn the back screen off.
                    window.decorView.postDelayed({
                        YotaSdkAdapter.lockEpd()
                    }, 700L)
                    return
                }
            }
            YotaSdkAdapter.lockEpd()
        } else {
            deviceManagerLock()
        }
    }

    private fun deviceManagerLock() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, LockAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
        } else {
            runCatching {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.lock_admin_description)
                    )
                startActivity(intent)
            }
        }
    }

    private fun setupRecentOverlay() {
        findViewById<View>(R.id.recentDim).setOnClickListener { hideRecentTasks() }
        recentClear.setOnClickListener {
            // 先取当前最近任务列表，再清空记录，最后同步清理这些应用的后台进程。
            val appsToKill = currentRecentApps()
            store.markRecentCleared()
            previousApp = null
            populateRecentGrid()
            killBackgroundApps(appsToKill)
        }
        recentCancel.setOnClickListener {
            hideRecentTasks()
            if (config.recentCancelBackToApp) {
                previousApp?.let { launchApp(it) }
            }
        }
        recentGrid.setOnItemInteractionListener(object : EInkLauncherView.OnItemInteractionListener {
            override fun onItemClick(info: ResolveInfo) {
                hideRecentTasks()
                launchApp(info)
            }

            override fun onItemLongClick(info: ResolveInfo) = Unit
        })
        recentGrid.setOnPageChangeListener(null)
    }

    // ------------------------------------------------------------------ Info overlay

    private fun setupSelectOverlay() {
        findViewById<View>(R.id.selectDim).setOnClickListener { hideSelectOverlay() }
        findViewById<View>(R.id.selectCancel).setOnClickListener { hideSelectOverlay() }
    }

    private fun showSelectOverlay(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPicked: (Int) -> Unit
    ) {
        selectTitle.text = title
        selectCallback = onPicked
        selectList.removeAllViews()

        options.forEachIndexed { index, label ->
            val row = TextView(this).apply {
                text = if (index == selectedIndex) "● $label" else "○ $label"
                textSize = 16f
                setTextColor(color(if (index == selectedIndex) R.color.ink else R.color.gray))
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), 0, dp(24), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52)
                )
                setOnClickListener {
                    hideSelectOverlay()
                    onPicked(index)
                }
            }
            selectList.addView(row)

            if (index < options.size - 1) {
                selectList.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    )
                    setBackgroundColor(color(R.color.hairline))
                })
            }
        }

        selectOverlay.visibility = View.VISIBLE
    }

    private fun hideSelectOverlay() {
        selectOverlay.visibility = View.GONE
        selectCallback = null
    }

    // ------------------------------------------------------------------ WiFi transfer

    private fun setupWifiTransfer() {
        textTime.setOnClickListener { startWifiTransfer() }
        findViewById<View>(R.id.wifiClose).setOnClickListener { closeWifiTransfer() }
    }

    private fun startWifiTransfer() {
        if (wifiOverlay.visibility == View.VISIBLE) return

        if (Build.VERSION.SDK_INT >= 23 &&
            Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
            return
        }
        // 4.2/4.4/5.x：WRITE_EXTERNAL_STORAGE 安装时授予，无需运行时申请。
        openWifiTransferOverlay()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNINSTALL) {
            startNextUninstall()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WRITE_STORAGE) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openWifiTransferOverlay()
        } else {
            toast("需要存储权限才能保存上传的文件")
        }
    }

    private fun openWifiTransferOverlay() {
        val ip = getWifiIpAddress()
        if (ip == null) {
            toast(getString(R.string.wifi_transfer_no_wifi))
            return
        }

        val dir = File(Environment.getExternalStorageDirectory(), "WIFI_transfer")
        val server = WifiTransferServer(WIFI_TRANSFER_PORT, dir)
        if (!server.start()) {
            toast(getString(R.string.wifi_transfer_start_failed))
            return
        }

        wifiServer?.stop()
        wifiServer = server

        val url = "http://$ip:$WIFI_TRANSFER_PORT"
        wifiUrlText.text = url
        wifiHintText.text = getString(R.string.wifi_transfer_url_hint, url) + "\n" +
                getString(R.string.wifi_transfer_folder_hint)
        wifiQr.setImageBitmap(QrCodes.generate(url, dp(300)))
        wifiOverlay.visibility = View.VISIBLE
    }

    private fun closeWifiTransfer() {
        wifiServer?.stop()
        wifiServer = null
        wifiOverlay.visibility = View.GONE
    }

    private fun getWifiIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    val addresses = interfaces.nextElement().inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun setupInfoOverlay() {
        infoClose.setOnClickListener { hideInfo() }
        // rowHelp / rowAbout / rowDonationPrompt 在 ensureSettingsPage() 中懒加载后绑定
    }

    private fun updateDonationPromptValue() {
        settingsDonationPromptValue.text =
            getString(if (store.isDonationPromptEnabled()) R.string.divider_on else R.string.divider_off)
    }

    private fun showInfo(title: String, text: String) {
        infoTitle.text = title
        infoText.text = text
        infoOverlay.visibility = View.VISIBLE
    }

    private fun hideInfo() {
        infoOverlay.visibility = View.GONE
    }

    private fun showRecentTasks() {
        if (actionOverlay.visibility == View.VISIBLE) hideActionSheet()
        if (manageMode) exitManageMode()
        recentOverlay.visibility = View.VISIBLE
        previousApp = loadRecentApps().firstOrNull()
        populateRecentGrid()
    }

    private fun populateRecentGrid() {
        // UsageStatsManager / PACKAGE_USAGE_STATS 需要 API 21。
        if (Build.VERSION.SDK_INT < 21) {
            recentMessage.text = "最近任务需要 Android 5.0 及以上"
            recentMessage.setOnClickListener(null)
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        if (!usageAccessGranted()) {
            recentMessage.text = "需要开启「使用情况访问」权限才能显示后台应用，点此去开启"
            recentMessage.setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        val apps = currentRecentApps()

        if (apps.isEmpty()) {
            recentMessage.text =
                if (config.recentWindowMs > 0L) "暂无近期使用" else "暂无最近任务"
            recentMessage.setOnClickListener(null)
            recentMessage.visibility = View.VISIBLE
            recentGrid.visibility = View.GONE
            return
        }

        recentMessage.visibility = View.GONE
        recentGrid.visibility = View.VISIBLE
        val columns = 4
        val rows = (apps.size + columns - 1) / columns
        recentGrid.configure(columns, rows, config.iconSize)

        // ==========================================
        // 核心修改区：动态计算行高，代替写死的 76dp
        // ==========================================
        val rowHeight = config.iconSize + 34 // 使用 +34dp 给予大图标下方的文字更多一点呼吸空间
        recentGrid.layoutParams = recentGrid.layoutParams.apply {
            height = dp(rows * rowHeight)
        }
        // ==========================================

        recentGrid.setApps(
            pageApps = apps,
            selection = emptySet(),
            manageMode = false,
            showDividers = true,
            hidden = emptySet()
        )
    }

    /** 当前最近任务列表（与 recents 界面显示的一致）。 */
    private fun currentRecentApps(): List<ResolveInfo> {
        val windowMs = config.recentWindowMs
        return if (windowMs > 0L) {
            loadRecentApps(System.currentTimeMillis() - windowMs)
        } else {
            loadRecentApps()
        }
    }

    /**
     * 尽力清理最近任务中每个应用的后台进程。
     * 普通应用没有 force-stop 权限，只能调用 killBackgroundProcesses；
     * 前台有服务的应用不会被杀，但缓存/后台进程会被回收。
     */
    private fun killBackgroundApps(apps: List<ResolveInfo>) {
        if (apps.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "KILL_BACKGROUND_PROCESSES not granted")
            toast("缺少后台清理权限")
            return
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        var killed = 0
        for (info in apps) {
            val pkg = info.activityInfo.packageName
            runCatching {
                am.killBackgroundProcesses(pkg)
                killed++
            }.onFailure { e ->
                Log.w(TAG, "killBackgroundProcesses failed for $pkg: ${e.message}")
            }
        }
        if (killed > 0) {
            Log.i(TAG, "killBackgroundProcesses requested for $killed packages")
            toast("已清理 $killed 个后台应用")
        }
    }

    /**
     * Real background apps come from UsageStatsManager, sorted by the last
     * time the user used them. Only launchable packages are shown.
     */
    private fun loadRecentApps(since: Long = 0L): List<ResolveInfo> {
        if (Build.VERSION.SDK_INT < 21) return emptyList()
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val begin = now - 7L * 24 * 60 * 60 * 1000
        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, now)
        }.getOrDefault(emptyList())

        val apps = ArrayList<ResolveInfo>()
        val clearedAt = store.recentClearedAt()
        for (stat in stats.sortedByDescending { it.lastTimeUsed }) {
            if (stat.lastTimeUsed <= 0) continue
            if (stat.lastTimeUsed <= clearedAt) continue
            if (since > 0L && stat.lastTimeUsed < since) continue
            val pkg = stat.packageName
            if (pkg == packageName) continue
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            val info = runCatching { packageManager.resolveActivity(launchIntent, 0) }.getOrNull()
                ?: continue
            if (apps.any { it.activityInfo.packageName == pkg }) continue
            apps.add(info)
            if (apps.size >= 12) break
        }
        return apps
    }

    private fun usageAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 21) return false
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun hideRecentTasks() {
        recentOverlay.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun togglePin(info: ResolveInfo) {
        val pkg = info.activityInfo.packageName
        val pinned = repository.isPinned(pkg)
        repository.setPinned(pkg, !pinned)
        toast(if (!pinned) "已固定到主页" else "已取消固定")
        refreshHome()
    }

    private fun hideApp(info: ResolveInfo) {
        repository.setHidden(info.activityInfo.packageName, true)
        toast("已隐藏")
        refreshHome()
        refreshApps()
    }

    private fun showAppInfo(info: ResolveInfo) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${info.activityInfo.packageName}")
                }
            )
        }
    }

    // ------------------------------------------------------------------ Common

    private fun launchApp(info: ResolveInfo) {
        repository.recordUsage(info.activityInfo.packageName)
        repository.launch(info)
    }

    private fun registerTimeReceiver() {
        val filter = IntentFilter(Intent.ACTION_TIME_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(timeReceiver, filter)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageReceiver, filter)
        }
    }

    private fun updateClock() {
        if (!::textTime.isInitialized) return
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日 · E", Locale.CHINA).format(now)
        textTime.text = time
        textDate.text = date
        infoTime.text = time
    }

    @Suppress("DEPRECATION")
    private fun color(res: Int): Int = resources.getColor(res)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when {
                    infoOverlay.visibility == View.VISIBLE -> hideInfo()
                    selectOverlay.visibility == View.VISIBLE -> hideSelectOverlay()
                    wifiOverlay.visibility == View.VISIBLE -> closeWifiTransfer()
                    recentOverlay.visibility == View.VISIBLE -> hideRecentTasks()
                    actionOverlay.visibility == View.VISIBLE -> hideActionSheet()
                    manageMode -> exitManageMode()
                    ::pageSettings.isInitialized && pageSettings.visibility == View.VISIBLE -> selectPage(PAGE_HOME, animate = true)
                    ::pageApps.isInitialized && pageApps.visibility == View.VISIBLE -> selectPage(PAGE_HOME, animate = true)
                    else -> Unit
                }
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    companion object {
        private const val TAG = "LauncherActivity"
        private const val PAGE_HOME = 0
        private const val PAGE_APPS = 1
        private const val PAGE_SETTINGS = 2
        private const val EPD_HOME_CATEGORY = "com.yotadevices.intent.category.EPD_HOME"
        private const val WIFI_TRANSFER_PORT = 25025
        private const val DONATION_WECHAT_URI =
            "wxp://f2f0KzDXCZc0SriqXOoY8fWKxxyFbxz9xhImNUL1VgMLLaQ"
        private const val REQ_WRITE_STORAGE = 7
        private const val REQ_UNINSTALL = 8
    }
}