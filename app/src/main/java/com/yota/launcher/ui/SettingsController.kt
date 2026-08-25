package com.yota.launcher.ui

import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import com.yota.launcher.R
import com.yota.launcher.data.LauncherConfig

/**
 * Owns the settings rows. It only computes the next config and reports it
 * through [onConfigChanged]; the activity persists the value and refreshes
 * whichever part of the UI is affected.
 */
class SettingsController(
    private val activity: Activity,
    private val currentConfig: () -> LauncherConfig,
    private val onConfigChanged: (LauncherConfig, Int) -> Unit,
    private val showSelector: (title: String, options: List<String>, selectedIndex: Int, onPicked: (Int) -> Unit) -> Unit,
    private val showYotaSettings: Boolean
) {

    companion object {
        const val AFFECT_APPS = 1
        const val AFFECT_HOME = 2
        const val AFFECT_ICONS = 4
        const val AFFECT_REFRESH = 8

        private val COLUMN_OPTIONS = intArrayOf(3, 4, 5)
        private val ROW_OPTIONS = intArrayOf(4, 5, 6)
        private val HOME_COLUMN_OPTIONS = intArrayOf(3, 4, 5)
        private val HOME_ROW_OPTIONS = intArrayOf(1, 2, 3)
        private val RECENT_WINDOW_OPTIONS_MS = longArrayOf(
            0L, 5 * 60_000L, 30 * 60_000L, 60 * 60_000L, 6 * 60 * 60_000L, 24 * 60 * 60_000L
        )
    }

    private lateinit var colsValue: TextView
    private lateinit var rowsValue: TextView
    private lateinit var homeColumnsValue: TextView
    private lateinit var homeRowsValue: TextView
    private lateinit var homeDividersValue: TextView
    private lateinit var appDividersValue: TextView
    private lateinit var recentWindowValue: TextView
    private lateinit var recentCancelValue: TextView
    private lateinit var iconPackValue: TextView
    private lateinit var lineIconsValue: TextView
    private lateinit var refreshModeValue: TextView
    private lateinit var pageAnimationValue: TextView
    private lateinit var screenOnAnimationValue: TextView
    private lateinit var screenOnAnimationStyleValue: TextView
    private lateinit var screenOffAnimationValue: TextView
    private lateinit var screenOffAnimationStyleValue: TextView
    private lateinit var rowPageAnimation: View
    private lateinit var rowScreenOnAnimation: View
    private lateinit var rowScreenOnAnimationStyle: View
    private lateinit var rowScreenOffAnimation: View
    private lateinit var rowScreenOffAnimationStyle: View
    private lateinit var settingsAnimationHint: View
    private lateinit var rowGroupGrid: View
    private lateinit var groupGridChevron: TextView
    private lateinit var groupGridContent: View
    private lateinit var rowGroupIcons: View
    private lateinit var groupIconsChevron: TextView
    private lateinit var groupIconsContent: View
    private lateinit var rowGroupRecent: View
    private lateinit var groupRecentChevron: TextView
    private lateinit var groupRecentContent: View
    private lateinit var rowGroupRefresh: View
    private lateinit var groupRefreshChevron: TextView
    private lateinit var groupRefreshContent: View

    fun bind() {
        colsValue = activity.findViewById(R.id.settingsColsValue)
        rowsValue = activity.findViewById(R.id.settingsRowsValue)
        homeColumnsValue = activity.findViewById(R.id.settingsHomeColumnsValue)
        homeRowsValue = activity.findViewById(R.id.settingsHomeRowsValue)
        homeDividersValue = activity.findViewById(R.id.settingsHomeDividersValue)
        appDividersValue = activity.findViewById(R.id.settingsAppDividersValue)
        recentWindowValue = activity.findViewById(R.id.settingsRecentWindowValue)
        recentCancelValue = activity.findViewById(R.id.settingsRecentCancelValue)
        iconPackValue = activity.findViewById(R.id.settingsIconPackValue)
        lineIconsValue = activity.findViewById(R.id.settingsLineIconsValue)
        refreshModeValue = activity.findViewById(R.id.settingsRefreshModeValue)
        pageAnimationValue = activity.findViewById(R.id.settingsPageAnimationValue)
        screenOnAnimationValue = activity.findViewById(R.id.settingsScreenOnAnimationValue)
        screenOnAnimationStyleValue = activity.findViewById(R.id.settingsScreenOnAnimationStyleValue)
        screenOffAnimationValue = activity.findViewById(R.id.settingsScreenOffAnimationValue)
        screenOffAnimationStyleValue = activity.findViewById(R.id.settingsScreenOffAnimationStyleValue)
        rowPageAnimation = activity.findViewById(R.id.rowPageAnimation)
        rowScreenOnAnimation = activity.findViewById(R.id.rowScreenOnAnimation)
        rowScreenOnAnimationStyle = activity.findViewById(R.id.rowScreenOnAnimationStyle)
        rowScreenOffAnimation = activity.findViewById(R.id.rowScreenOffAnimation)
        rowScreenOffAnimationStyle = activity.findViewById(R.id.rowScreenOffAnimationStyle)
        settingsAnimationHint = activity.findViewById(R.id.settingsAnimationHint)
        rowGroupGrid = activity.findViewById(R.id.rowGroupGrid)
        groupGridChevron = activity.findViewById(R.id.groupGridChevron)
        groupGridContent = activity.findViewById(R.id.groupGridContent)
        rowGroupIcons = activity.findViewById(R.id.rowGroupIcons)
        groupIconsChevron = activity.findViewById(R.id.groupIconsChevron)
        groupIconsContent = activity.findViewById(R.id.groupIconsContent)
        rowGroupRecent = activity.findViewById(R.id.rowGroupRecent)
        groupRecentChevron = activity.findViewById(R.id.groupRecentChevron)
        groupRecentContent = activity.findViewById(R.id.groupRecentContent)
        rowGroupRefresh = activity.findViewById(R.id.rowGroupRefresh)
        groupRefreshChevron = activity.findViewById(R.id.groupRefreshChevron)
        groupRefreshContent = activity.findViewById(R.id.groupRefreshContent)
    }

    fun setup(initial: LauncherConfig) {
        activity.findViewById<View>(R.id.rowCols).setOnClickListener {
            val cfg = currentConfig()
            val options = COLUMN_OPTIONS.map { it.toString() }
            showSelector(
                activity.getString(R.string.settings_columns), options,
                COLUMN_OPTIONS.indexOf(cfg.columns)
            ) { index ->
                onConfigChanged(currentConfig().copy(columns = COLUMN_OPTIONS[index]), AFFECT_APPS)
            }
        }
        activity.findViewById<View>(R.id.rowRows).setOnClickListener {
            val cfg = currentConfig()
            val options = ROW_OPTIONS.map { it.toString() }
            showSelector(
                activity.getString(R.string.settings_rows), options,
                ROW_OPTIONS.indexOf(cfg.rows)
            ) { index ->
                onConfigChanged(currentConfig().copy(rows = ROW_OPTIONS[index]), AFFECT_APPS)
            }
        }
        activity.findViewById<View>(R.id.rowHomeColumns).setOnClickListener {
            val cfg = currentConfig()
            val options = HOME_COLUMN_OPTIONS.map { it.toString() }
            showSelector(
                activity.getString(R.string.settings_home_columns), options,
                HOME_COLUMN_OPTIONS.indexOf(cfg.homeColumns)
            ) { index ->
                onConfigChanged(currentConfig().copy(homeColumns = HOME_COLUMN_OPTIONS[index]), AFFECT_HOME)
            }
        }
        activity.findViewById<View>(R.id.rowHomeRows).setOnClickListener {
            val cfg = currentConfig()
            val options = HOME_ROW_OPTIONS.map { it.toString() }
            showSelector(
                activity.getString(R.string.settings_home_rows), options,
                HOME_ROW_OPTIONS.indexOf(cfg.homeRows)
            ) { index ->
                onConfigChanged(currentConfig().copy(homeRows = HOME_ROW_OPTIONS[index]), AFFECT_HOME)
            }
        }
        activity.findViewById<View>(R.id.rowHomeDividers).setOnClickListener {
            val cfg = currentConfig()
            onConfigChanged(cfg.copy(showHomeDividers = !cfg.showHomeDividers), AFFECT_HOME)
        }
        activity.findViewById<View>(R.id.rowAppDividers).setOnClickListener {
            val cfg = currentConfig()
            onConfigChanged(cfg.copy(showAppDividers = !cfg.showAppDividers), AFFECT_APPS)
        }
        activity.findViewById<View>(R.id.rowIconPack).setOnClickListener {
            val cfg = currentConfig()
            val packs = IconLoader.findIconPacks(activity.packageManager)
            val options = listOf(activity.getString(R.string.icon_pack_default)) +
                packs.map { it.label }
            val selected = packs.indexOfFirst { it.packageName == cfg.iconPack }.let { if (it >= 0) it + 1 else 0 }
            showSelector(
                activity.getString(R.string.settings_icon_pack), options, selected
            ) { index ->
                val chosen = if (index <= 0) "" else packs.getOrNull(index - 1)?.packageName ?: ""
                onConfigChanged(currentConfig().copy(iconPack = chosen), AFFECT_ICONS)
            }
        }
        activity.findViewById<View>(R.id.rowLineIcons).setOnClickListener {
            val cfg = currentConfig()
            onConfigChanged(cfg.copy(autoLineIcons = !cfg.autoLineIcons), AFFECT_ICONS)
        }
        activity.findViewById<View>(R.id.rowRecentWindow).setOnClickListener {
            val cfg = currentConfig()
            val options = RECENT_WINDOW_OPTIONS_MS.map { activity.getString(windowLabel(it)) }
            showSelector(
                activity.getString(R.string.settings_recent_recent_only), options,
                RECENT_WINDOW_OPTIONS_MS.indexOf(cfg.recentWindowMs)
            ) { index ->
                onConfigChanged(currentConfig().copy(recentWindowMs = RECENT_WINDOW_OPTIONS_MS[index]), 0)
            }
        }
        activity.findViewById<View>(R.id.rowRecentCancel).setOnClickListener {
            val cfg = currentConfig()
            val options = listOf(
                activity.getString(R.string.recent_cancel_back_to_app),
                activity.getString(R.string.recent_cancel_back_to_home)
            )
            showSelector(
                activity.getString(R.string.settings_recent_cancel), options,
                if (cfg.recentCancelBackToApp) 0 else 1
            ) { index ->
                onConfigChanged(currentConfig().copy(recentCancelBackToApp = index == 0), 0)
            }
        }
        activity.findViewById<View>(R.id.rowRefreshMode).setOnClickListener {
            val cfg = currentConfig()
            val options = listOf(
                activity.getString(R.string.refresh_mode_high_quality),
                activity.getString(R.string.refresh_mode_high_speed),
                activity.getString(R.string.refresh_mode_adaptive)
            )
            showSelector(
                activity.getString(R.string.settings_refresh_mode), options,
                cfg.refreshMode.coerceIn(0, 2)
            ) { index ->
                onConfigChanged(currentConfig().copy(refreshMode = index), AFFECT_REFRESH)
            }
        }
        activity.findViewById<View>(R.id.rowPageAnimation).setOnClickListener {
            val cfg = currentConfig()
            val options = animationTypeOptions(includeOff = true)
            showSelector(
                activity.getString(R.string.settings_page_animation), options,
                cfg.pageAnimation.coerceIn(0, 6)
            ) { index ->
                onConfigChanged(currentConfig().copy(pageAnimation = index), AFFECT_REFRESH)
            }
        }
        activity.findViewById<View>(R.id.rowScreenOnAnimation).setOnClickListener {
            val cfg = currentConfig()
            onConfigChanged(cfg.copy(screenOnAnimation = !cfg.screenOnAnimation), 0)
        }
        activity.findViewById<View>(R.id.rowScreenOnAnimationStyle).setOnClickListener {
            val cfg = currentConfig()
            val options = animationTypeOptions(includeOff = false)
            showSelector(
                activity.getString(R.string.settings_screen_on_animation_style), options,
                (cfg.screenOnAnimationStyle.coerceIn(1, 6)) - 1
            ) { index ->
                onConfigChanged(currentConfig().copy(screenOnAnimationStyle = index + 1), 0)
            }
        }
        activity.findViewById<View>(R.id.rowScreenOffAnimation).setOnClickListener {
            val cfg = currentConfig()
            onConfigChanged(cfg.copy(screenOffAnimation = !cfg.screenOffAnimation), 0)
        }
        activity.findViewById<View>(R.id.rowScreenOffAnimationStyle).setOnClickListener {
            val cfg = currentConfig()
            val options = animationTypeOptions(includeOff = false)
            showSelector(
                activity.getString(R.string.settings_screen_off_animation_style), options,
                (cfg.screenOffAnimationStyle.coerceIn(1, 6)) - 1
            ) { index ->
                onConfigChanged(currentConfig().copy(screenOffAnimationStyle = index + 1), 0)
            }
        }
        rowGroupGrid.setOnClickListener {
            toggleGroup(groupGridContent, groupGridChevron)
        }
        rowGroupIcons.setOnClickListener {
            toggleGroup(groupIconsContent, groupIconsChevron)
        }
        rowGroupRecent.setOnClickListener {
            toggleGroup(groupRecentContent, groupRecentChevron)
        }
        rowGroupRefresh.setOnClickListener {
            toggleGroup(groupRefreshContent, groupRefreshChevron)
        }
        activity.findViewById<View>(R.id.settingsReset).setOnClickListener {
            Toast.makeText(activity, "已恢复默认", Toast.LENGTH_SHORT).show()
            onConfigChanged(LauncherConfig(), AFFECT_APPS or AFFECT_HOME or AFFECT_ICONS or AFFECT_REFRESH)
        }

        // Non-Yota devices: hide the whole refresh/animation group; the SDK
        // features it controls are Yota-only. Lock still falls back to the
        // device-admin path in LauncherActivity.
        if (!showYotaSettings) {
            rowGroupRefresh.visibility = View.GONE
        }

        collapseAllGroups()
        updateValues(initial)
    }

    fun updateValues(config: LauncherConfig) {
        colsValue.text = config.columns.toString()
        rowsValue.text = config.rows.toString()
        homeColumnsValue.text = config.homeColumns.toString()
        homeRowsValue.text = config.homeRows.toString()
        homeDividersValue.text = activity.getString(if (config.showHomeDividers) R.string.divider_on else R.string.divider_off)
        appDividersValue.text = activity.getString(if (config.showAppDividers) R.string.divider_on else R.string.divider_off)
        recentWindowValue.text = activity.getString(windowLabel(config.recentWindowMs))
        recentCancelValue.text = activity.getString(
            if (config.recentCancelBackToApp) R.string.recent_cancel_back_to_app
            else R.string.recent_cancel_back_to_home
        )
        iconPackValue.text = iconPackLabel(config.iconPack)
        lineIconsValue.text = activity.getString(if (config.autoLineIcons) R.string.divider_on else R.string.divider_off)
        refreshModeValue.text = activity.getString(
            when (config.refreshMode) {
                1 -> R.string.refresh_mode_high_speed
                2 -> R.string.refresh_mode_adaptive
                else -> R.string.refresh_mode_high_quality
            }
        )
        pageAnimationValue.text = activity.getString(animationStyleLabel(config.pageAnimation))
        screenOnAnimationValue.text = activity.getString(if (config.screenOnAnimation) R.string.divider_on else R.string.divider_off)
        screenOnAnimationStyleValue.text = activity.getString(animationStyleLabel(config.screenOnAnimationStyle))
        screenOffAnimationValue.text = activity.getString(if (config.screenOffAnimation) R.string.divider_on else R.string.divider_off)
        screenOffAnimationStyleValue.text = activity.getString(animationStyleLabel(config.screenOffAnimationStyle))
        updateAnimationVisibility(config)
    }

    /** 动画设置跟随主模式：仅高画质模式显示动画相关行；其他模式显示提示。 */
    private fun updateAnimationVisibility(config: LauncherConfig) {
        val show = config.refreshMode == 0
        val v = if (show) View.VISIBLE else View.GONE
        rowPageAnimation.visibility = v
        rowScreenOnAnimation.visibility = v
        rowScreenOnAnimationStyle.visibility = v
        rowScreenOffAnimation.visibility = v
        rowScreenOffAnimationStyle.visibility = v
        settingsAnimationHint.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun toggleGroup(content: View, chevron: TextView) {
        val expanded = content.visibility == View.VISIBLE
        collapseAllGroups()
        if (!expanded) setGroupExpanded(content, chevron, true)
    }

    private fun collapseAllGroups() {
        setGroupExpanded(groupGridContent, groupGridChevron, false)
        setGroupExpanded(groupIconsContent, groupIconsChevron, false)
        setGroupExpanded(groupRecentContent, groupRecentChevron, false)
        setGroupExpanded(groupRefreshContent, groupRefreshChevron, false)
    }

    private fun setGroupExpanded(content: View, chevron: TextView, expanded: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.text = activity.getString(if (expanded) R.string.settings_collapse else R.string.settings_expand)
    }

    private fun animationTypeOptions(includeOff: Boolean): List<String> {
        val styles = listOf(
            R.string.page_animation_left_right,
            R.string.page_animation_open,
            R.string.page_animation_close,
            R.string.page_animation_top_bottom,
            R.string.page_animation_open_v,
            R.string.page_animation_close_v
        ).map { activity.getString(it) }
        return if (includeOff) listOf(activity.getString(R.string.page_animation_off)) + styles else styles
    }

    private fun animationStyleLabel(style: Int): Int = when (style) {
        2 -> R.string.page_animation_open
        3 -> R.string.page_animation_close
        4 -> R.string.page_animation_top_bottom
        5 -> R.string.page_animation_open_v
        6 -> R.string.page_animation_close_v
        0 -> R.string.page_animation_off
        else -> R.string.page_animation_left_right
    }

    private fun iconPackLabel(packageName: String): String {
        if (packageName.isBlank()) return activity.getString(R.string.icon_pack_default)
        val label = runCatching {
            val app = activity.packageManager.getApplicationInfo(packageName, 0)
            app.loadLabel(activity.packageManager).toString()
        }.getOrNull()
        return label ?: packageName
    }

    private fun windowLabel(ms: Long): Int = when (ms) {
        0L -> R.string.recent_window_off
        5 * 60_000L -> R.string.recent_window_5min
        30 * 60_000L -> R.string.recent_window_30min
        60 * 60_000L -> R.string.recent_window_1h
        6 * 60 * 60_000L -> R.string.recent_window_6h
        24 * 60 * 60_000L -> R.string.recent_window_1d
        else -> R.string.recent_window_off
    }
}
