package com.yota.launcher.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口。配置源为 Launcher 的 EPD 参数管理
 * （SharedPreferences: yota_paper_epd_params）：
 * mode == 2（动画）的条目 = 需要注入翻页动画的 Activity。
 *
 * 过滤只按 Activity 全类名匹配，不再按包名过滤：
 * 目标包进程照常安装 hook，具体窗口是否注入由窗口所属 Activity 决定。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LAUNCHER_PACKAGE = "com.yota.launcher";
    private static final String LAUNCHER_PREFS = "yota_paper_epd_params";

    private static Set<String> targetActivities = new HashSet<>();
    private static boolean enable = true;
    private static int animWindowMs = 1200;
    private static int animFrameCount = 15;
    private static boolean debugLog = true;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (LAUNCHER_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            return;
        }

        reloadPrefs();

        if (!enable) {
            XposedBridge.log("YotaPageTurn: disabled, skip " + lpparam.packageName);
            return;
        }

        if (targetActivities.isEmpty()) {
            XposedBridge.log("YotaPageTurn: no animation targets, skip " + lpparam.packageName);
            return;
        }

        XposedBridge.log("YotaPageTurn: loadPackage " + lpparam.packageName
                + " process=" + lpparam.processName
                + " activities=" + targetActivities.size());

        try {
            EpdPageTurnHook.install(lpparam.classLoader, lpparam.packageName,
                    targetActivities, enable, animWindowMs, animFrameCount, debugLog);
        } catch (Throwable t) {
            XposedBridge.log("YotaPageTurn: install failed for "
                    + lpparam.packageName + " : " + t);
        }
    }

    private static void reloadPrefs() {
        Set<String> acts = new HashSet<>();

        try {
            XSharedPreferences lp = new XSharedPreferences(LAUNCHER_PACKAGE, LAUNCHER_PREFS);
            lp.reload();
            String items = lp.getString("items", "");
            if (items != null && !items.trim().isEmpty()) {
                JSONArray arr = new JSONArray(items);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    if (o.optInt("mode", 0) != 2) continue;
                    String activity = o.optString("activity", "");
                    String cls = classOf(activity);
                    if (!cls.isEmpty()) acts.add(cls);
                }
            }

            enable = lp.getBoolean("anim_enable", true);
            animWindowMs = lp.getInt("anim_window_ms", 1200);
            animFrameCount = lp.getInt("anim_frame_count", 15);
            debugLog = lp.getBoolean("debug_log", true);
            XposedBridge.log("YotaPageTurn: prefs loaded from launcher"
                    + " activities=" + acts.size()
                    + " windowMs=" + animWindowMs
                    + " frameCount=" + animFrameCount);
        } catch (Throwable t) {
            XposedBridge.log("YotaPageTurn: launcher prefs read failed: " + t);
        }

        targetActivities = acts;
    }

    private static String classOf(String activity) {
        int idx = activity.indexOf('/');
        if (idx > 0) {
            int end = activity.indexOf('}');
            if (end > idx) return activity.substring(idx + 1, end);
            return activity.substring(idx + 1);
        }
        return activity;
    }
}
