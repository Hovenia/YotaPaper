# 架构与源码结构

## 总览

Yota Paper 是单 Activity 应用，不依赖 AndroidX / RecyclerView / ViewPager / ConstraintLayout / Coroutines。

- 语言：Kotlin
- UI：纯 XML 布局 + 自定义 `EInkLauncherView` 网格视图
- 持久化：`SharedPreferences`
- 图标：系统图标 + appfilter 图标包 + 自绘线条图标（`Canvas`）
- Yota SDK：`compileOnly` 编译期 Stub，运行时使用设备系统共享库（反射/降级）

## 包结构

```
com.yota.launcher
├── LauncherActivity.kt        主界面：页面切换、网格装配、最近任务、锁屏、WiFi 传书、EPD 同步
├── LockAdminReceiver.kt       设备管理器锁屏兜底（非 Yota 设备）
├── QrCodes.kt                 二维码生成（ZXing 封装）
├── WifiTransferServer.kt      WiFi 传书 HTTP 服务
├── data/
│   ├── Models.kt              LauncherConfig 配置模型与默认值
│   ├── LauncherConfigStore.kt 设置持久化（yota_paper_config）
│   └── AppRepository.kt       应用列表、隐藏/固定、点击次数、label 缓存
├── epd/
│   ├── EpdManagerActivity.kt  按应用配置背屏刷新参数的管理界面
│   └── EpdParamsStore.kt      EPD 参数持久化与 Provider 同步
├── ui/
│   ├── EInkLauncherView.kt    墨水屏网格视图（ViewHolder 池，无动画、无手动刷新）
│   ├── IconLoader.kt          图标加载：系统图标 / 图标包 / 线条图标
│   ├── LineIconRenderer.kt    根据原图标生成黑色线条图标
│   ├── SettingsController.kt  设置页控制器（折叠分组、弹窗列表选择）
│   └── StatusBarController.kt WiFi / 蓝牙 / 电量实时状态（广播）
└── yota/
    ├── EInkSdk.kt             EPD SDK 动画与刷新参数的低层封装
    └── YotaSdkAdapter.kt      背屏锁屏/设备能力探测（反射调用 EpdManager）
```

## 主界面结构

`LauncherActivity` 是唯一入口 Activity（`singleInstance`，MAIN/LAUNCHER/HOME/DEFAULT + Yota `EPD_HOME` category）。

主要页面：

| 页面 | 实现 |
|---|---|
| 主页 | `page_home.xml` + `gridHome`（EInkLauncherView） |
| 应用抽屉 | `page_apps.xml`（懒加载）+ `gridApps` |
| 设置 | `page_settings.xml`（ViewStub 懒加载）+ `SettingsController` |

浮层（全部为 `FrameLayout` 底部面板，非系统 Dialog）：

| 浮层 | 用途 |
|---|---|
| `actionOverlay` | 长按应用操作菜单（不淡化背景、无震动） |
| `recentOverlay` | 最近任务（标题右侧可清空，底部取消） |
| `selectOverlay` | 设置项多选弹窗列表（●/○ 标记） |
| `infoOverlay` | 使用说明 / 关于 |

## 网格视图（EInkLauncherView）

- 固定大小的 ViewHolder 池（`columns × rows`），复用无重建。
- 空槽位：alpha=0、图标置空、角标 GONE。
- 滑动阈值：`min(width, height) / 8`；`dx < 0` 为下一页，否则上一页。
- 无任何动画；不调用 SDK 刷新接口。

## 最近任务

- 数据来源：`UsageStatsManager` 最近 7 天，按 `lastTimeUsed` 倒序，最多 12 个，跳过自身。
- 时间过滤：`recentWindowMs`（0=关，否则只显示 `now - window` 之后使用过的应用）。
- 清空：记录 `recent_cleared_at` 时间戳，过滤掉该时间之前的使用记录。
- 打开：双击「主页」标签（1..799ms）或双击背屏 HOME；第三次 HOME 关闭。
- 取消行为：`recentCancelBackToApp` 控制回到上一应用或回到桌面。

## EPD / Yota SDK 集成

- `YotaSdkAdapter`：通过反射调用 `com.yotadevices.sdk.EpdManager.lockEpd()`，`SecurityException` 视为成功（系统已锁定）。
- `EInkSdk`：封装 `Epd.setUpdateMode` / `Epd.setEpdUpdateParams` / 动画帧等；仅 Yota 设备且高画质模式时播放动画。
- 非 Yota 设备：`isEpdContext()` 为 false，刷新/动画设置项自动隐藏，锁屏回退设备管理器。
- `EpdManagerActivity` / `EpdParamsStore`：按应用维护 EPD 参数，并同步到系统 Provider（`content://com.yotadevices.sdk.epdparams` 之类）。

## 配置模型（LauncherConfig）

见 `data/Models.kt`。默认值：

```kotlin
columns = 4, rows = 5,
homeColumns = 4, homeRows = 3,
showHomeDividers = true, showAppDividers = true,
recentWindowMs = 30 * 60_000L, recentCancelBackToApp = true,
iconPack = "", autoLineIcons = true,
refreshMode = 0,        // 0=高画质 1=流畅 2=自适应
pageAnimation = 1,      // 左右翻页
screenOnAnimation = true, screenOnAnimationStyle = 2,
screenOffAnimation = true, screenOffAnimationStyle = 3,
autoApplyEpdParams = true
```

## 关键设计决策

1. **无 AndroidX**：仅使用 `android.app.Activity`，APK 体积更小。
2. **稳定排序**：应用列表先按 label 升序，再用 Kotlin 稳定排序按「固定优先 → 点击次数」排序。
3. **label 缓存**：所有应用 label 合并为单个 JSON 字符串持久化，避免冷启动逐进程 IPC。
4. **设置懒加载**：`pageSettings` 通过 `ViewStub` 首次进入设置页时才 inflate。
5. **墨水屏友好**：界面无动画、无卡片阴影；所有弹窗为静态底部面板。
