package com.yota.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import org.json.JSONObject
import java.util.Locale

class AppRepository(private val context: Context) {

    private val appPrefs = context.getSharedPreferences("yota_paper_apps", Context.MODE_PRIVATE)

    // 应用列表在冷启动会被 home/apps 两处刷新各查一次；缓存起来，隐藏/包变更时失效。
    private var cachedAllApps: List<ResolveInfo>? = null
    private var cachedVisibleApps: List<ResolveInfo>? = null

    // 应用 label 持久缓存：排序需要全量 label，loadLabel 是跨进程调用，冷启动时最贵。
    // 所有 label 合并为一个 JSON 字符串存储，启动时一次 parse。
    private val labelPrefs = context.getSharedPreferences("yota_paper_labels", Context.MODE_PRIVATE)
    private val labelLocaleTag: String = Locale.getDefault().toString()
    private val labelCache: MutableMap<String, String> = run {
        val map = HashMap<String, String>()
        if (labelPrefs.getString(KEY_LABEL_LOCALE, null) == labelLocaleTag) {
            val json = labelPrefs.getString(KEY_LABELS_JSON, null)
            if (!json.isNullOrEmpty()) {
                runCatching {
                    val obj = JSONObject(json)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = obj.optString(key)
                        if (value.isNotEmpty()) map[key] = value
                    }
                }
            }
        } else {
            // 系统语言变了，旧 label 缓存失效。
            labelPrefs.edit().clear().apply()
        }
        map
    }
    private val labelDirty = HashMap<String, String>()

    fun loadApps(): List<ResolveInfo> {
        cachedVisibleApps?.let { return it }
        // loadHidden() 只读一次，避免每个 app 都去读一遍 SharedPreferences。
        val hidden = loadHidden()
        val apps = loadAllApps().filterNot { hidden.contains(it.activityInfo.packageName) }
        cachedVisibleApps = apps
        return apps
    }

    fun loadAllApps(): List<ResolveInfo> {
        cachedAllApps?.let { return it }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(intent, 0)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, 0)
        }
        val self = context.packageName
        val sorted = apps
            .filter { it.activityInfo.packageName != self }
            .sortedBy { labelOf(it).lowercase() }
        flushLabelCache()
        cachedAllApps = sorted
        return sorted
    }

    private fun componentKey(info: ResolveInfo): String =
        "${info.activityInfo.packageName}/${info.activityInfo.name}"

    private fun labelOf(info: ResolveInfo): String {
        val key = componentKey(info)
        labelCache[key]?.let { return it }
        val label = info.loadLabel(context.packageManager).toString()
        labelCache[key] = label
        labelDirty[key] = label
        return label
    }

    /** 有新 label 时一次性合并写一个 JSON 字符串；apply() 异步落盘。 */
    private fun flushLabelCache() {
        if (labelDirty.isEmpty()) return
        writeLabelCache()
    }

    private fun writeLabelCache() {
        val obj = JSONObject()
        labelCache.forEach { (key, value) -> runCatching { obj.put(key, value) } }
        labelPrefs.edit()
            .putString(KEY_LABEL_LOCALE, labelLocaleTag)
            .putString(KEY_LABELS_JSON, obj.toString())
            .apply()
        labelDirty.clear()
    }

    private fun invalidateCache() {
        cachedAllApps = null
        cachedVisibleApps = null
    }

    /** 包变更（安装/卸载/更新）时调用：失效应用列表与对应 label，重新写盘。 */
    fun invalidatePackage(packageName: String) {
        invalidateCache()
        val prefix = "$packageName/"
        val removed = labelCache.keys.filter { it.startsWith(prefix) }
        if (removed.isNotEmpty()) {
            removed.forEach { labelCache.remove(it) }
            writeLabelCache()
        }
    }

    fun sortHomeApps(apps: List<ResolveInfo>, limit: Int): List<ResolveInfo> {
        val pinned = loadPinned()
        val usage = loadUsage()
        // loadApps() 已按 label 升序排好；Kotlin 的 sortedWith 是稳定排序，
        // 这里只按 pinned/usage 排，label 顺序自然保留，省掉逐个 loadLabel。
        return apps
            .sortedWith(
                compareByDescending<ResolveInfo> { pinned.contains(it.activityInfo.packageName) }
                    .thenByDescending { usage[it.activityInfo.packageName] ?: 0 }
            )
            .take(limit)
    }

    fun launch(info: ResolveInfo) {
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // Hidden apps

    fun isHidden(packageName: String): Boolean = loadHidden().contains(packageName)

    fun hiddenPackages(): Set<String> = loadHidden()

    fun setHidden(packageName: String, hidden: Boolean) {
        val set = loadHidden().toMutableSet()
        if (hidden) set.add(packageName) else set.remove(packageName)
        appPrefs.edit().putStringSet("hidden", set).apply()
        invalidateCache()
    }

    fun batchSetHidden(packageNames: Collection<String>, hidden: Boolean) {
        val set = loadHidden().toMutableSet()
        if (hidden) set.addAll(packageNames) else set.removeAll(packageNames)
        appPrefs.edit().putStringSet("hidden", set).apply()
        invalidateCache()
    }

    private fun loadHidden(): Set<String> = appPrefs.getStringSet("hidden", emptySet()) ?: emptySet()

    // Pinned apps

    fun isPinned(packageName: String): Boolean = loadPinned().contains(packageName)

    fun setPinned(packageName: String, pinned: Boolean) {
        val pinnedSet = loadPinned().toMutableSet()
        if (pinned) pinnedSet.add(packageName) else pinnedSet.remove(packageName)
        appPrefs.edit().putStringSet("pinned", pinnedSet).apply()
    }

    private fun loadPinned(): Set<String> = appPrefs.getStringSet("pinned", emptySet()) ?: emptySet()

    // Usage frequency

    fun recordUsage(packageName: String) {
        val usage = loadUsage().toMutableMap()
        usage[packageName] = (usage[packageName] ?: 0) + 1
        appPrefs.edit().putInt("use_$packageName", usage[packageName]!!).apply()
    }

    private fun loadUsage(): Map<String, Int> {
        val usage = mutableMapOf<String, Int>()
        appPrefs.all.forEach { (key, value) ->
            if (key.startsWith("use_") && value is Int) {
                usage[key.removePrefix("use_")] = value
            }
        }
        return usage
    }

    companion object {
        private const val KEY_LABEL_LOCALE = "locale"
        private const val KEY_LABELS_JSON = "labels"
    }
}
