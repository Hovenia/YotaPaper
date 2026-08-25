# Yota3 系统自带「刷新管理」应用逆向分析

> 分析对象：`com.baoliyota.epdparams`（`/system/app/CY_EpdParams/CY_EpdParams.apk`）
> 关联框架：`/system/framework/oat/arm64/services.odex` 中的 `WindowManagerService` / `EpdUpdateParamsManager`
> 工具：apktool 2.9.3、baksmali（apktool 内置）、adb、uiautomator
> 设备：Yota3（YOTA/Y3/Y3:7.1.1）

---

## 1. 定位与形态

系统自带的刷新管理应用是 **`com.baoliyota.epdparams`**，APK 名为 `CY_EpdParams.apk`。

| 项 | 值 |
|---|---|
| APK 路径 | `/system/app/CY_EpdParams/CY_EpdParams.apk` |
| 实际代码 | `/system/app/CY_EpdParams/oat/arm64/CY_EpdParams.odex`（APK 内无 classes.dex，为资源壳） |
| sharedUserId | `android.uid.system`（uid 1000） |
| 关键权限 | `REAL_GET_TASKS`、`INSTALL_PACKAGES`、`SYSTEM_ALERT_WINDOW` 等 |
| 共享库 | `com.yotadevices.sdk`（`/system/priv-app/YotaDevicesSDK/YotaDevicesSDK.apk`） |
| 版本 | versionCode=1 / versionName=1.0 / targetSdk=25 |

组件构成：

| 组件 | 类 | 作用 |
|---|---|---|
| 界面 | `com.baoliyota.epdparams.ParamsActivity` | 底部弹出的「显示调节」面板 |
| 数据 | `com.baoliyota.epdparams.provider.ParamsProvider` | 以 ContentProvider 形式向 WindowManager 暴露每应用刷新参数 |

---

## 2. UI 与交互（ParamsActivity）

反编译布局 `res/layout/activity_main.xml`：整体是一个 `layout_gravity="bottom"` 的底部小面板。

```
LinearLayout (底部, 白底, wrap_content)
├── 顶部黑色细条 View
├── 标题行：「显示调节」 + 「重置」(默认 invisible)
├── RadioGroup：
│   ├── rb_fluent      「流畅」  → mode = 1
│   └── rb_high_quality「高画质」→ mode = 0
└── params_content（流畅时 VISIBLE，高画质时 GONE）
    ├── 对比度   SeekBar max=32   默认 10
    ├── 锐化     SeekBar max=16   默认 2
    ├── 黑色拉伸 SeekBar max=125  默认 70
    ├── 白色拉伸 SeekBar max=255  默认 255
    └── 亮度     SeekBar max=125  默认 0
```

交互逻辑（smali `ParamsActivity`）：

- `onCreate` 启动一个后台 `Thread` 查询「当前前台 Activity」的刷新参数；查不到则插入默认值。
- 选择「流畅」：`ParamsActivity$2` 将 `mParams`（目标 Activity）与 `mMyParams`（本应用自己）的 `mode` 都置为 1，写回 Provider，并通过 Handler 显示 SeekBar 面板。
- 选择「高画质」：`ParamsActivity$3` 将 mode 置为 0，写回 Provider，隐藏 SeekBar 面板。
- 拖动 SeekBar：`onProgressChanged` 将新值投入线程池（核心 2 / 最大 5 的 `ThreadPoolExecutor`）异步写回 Provider。
- 点击「重置」：`ParamsActivity$9` 恢复默认值 `mode=1, contrast=10, sharping=2, black_stretch=70, white_stretch=255, bright=0` 并写回。

---

## 3. 数据层：刷新参数数据库

`DBHelper` 建立 SQLite 数据库 `params.db`：

```sql
CREATE TABLE IF NOT EXISTS params (
  activity      VARCHAR,
  mode          INTEGER,
  contrast      INTEGER,
  sharping      INTEGER,
  black_stretch INTEGER,
  white_stretch INTEGER,
  bright        INTEGER
);
```

`ParamsProvider`：

- Authority：`com.baoliyota.epdparams.paramsprovider`
- URI：`content://com.baoliyota.epdparams.paramsprovider/params`
- 支持 `query`（全表 / 按 id）、`insert`、`update`；`delete` 直接返回 0。
- **insert / update 之后都会调用 `ContentResolver.notifyChange(CONTENT_URI, null)`** —— 这是系统能实时感知参数变化的关键。

设备实测数据（`adb shell content query --uri content://com.baoliyota.epdparams.paramsprovider/params`）：

```
com.yota.paperlauncher/...LauncherActivity  mode=0 contrast=10 sharping=2 black=70 white=255 bright=0
com.baoliyota.epdparams/...ParamsActivity  mode=1 ...
com.yota.sdktest/...MainActivity           mode=0 ...
com.qidian.QDReader/...MainGroupActivity    mode=1 ...
...
```

可见它是**按 Activity（`ComponentInfo{package/activity}` 字符串）粒度**存储刷新偏好的。

---

## 4. 前台 Activity 识别

`ParamsActivity` 使用系统权限调用：

```java
ActivityManager am = (ActivityManager) getSystemService("activity");
List<RunningTaskInfo> tasks = am.getRunningTasks(100, true);
String activity = tasks.get(0).topActivity.toString();
// 得到类似 "ComponentInfo{com.yota.paperlauncher/com.yota.launcher.LauncherActivity}"
```

然后 `ParamsProviderHelper.query(activity)` 以 `activity=?` 查 Provider；查不到则插入一条默认值。这也解释了为什么打开它之后，它自己也会生成一行 `com.baoliyota.epdparams/...ParamsActivity`。

---

## 5. Framework 消费链（核心原理）

以下内容反编译自 `/system/framework/oat/arm64/services.odex` 中的 `services.dex`。

### 5.1 WindowManagerService 中的 ContentObserver

`WindowManagerService$ParamsObserver`：

```java
// 构造时
resolver.registerContentObserver(
    Uri.parse("content://com.baoliyota.epdparams.paramsprovider/params"),
    false, this, -1);

// onChange 时
Cursor c = resolver.query(PARAMS_CONTENT_URI, null, null, null, null);
EpdUpdateParamsManager.updateUserParamsList(c);
```

即：**Provider 数据一变，WMS 立刻重查全表，刷新内存中的 `sUserParamsList`。**

`EpdUpdateParamsManager.updateUserParamsList()` 按列索引读取：

| 列索引 | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| 字段 | activity | mode | contrast | sharping | black_stretch | white_stretch | bright |

### 5.2 窗口级单查：querySingleEpdParams

`WindowManagerService.querySingleEpdParams(win, epdUpdateParams)` 在窗口创建 / 更新时被调用，用窗口标题拼出 Activity 键：

```java
String activity = "ComponentInfo{" + win.getAttrs().getTitle() + "}";
cursor = resolver.query(URI, null, "activity=?", new String[]{activity}, null);
```

然后按 mode 应用：

```
mode == 1（流畅）:
    setEpdViewUpdateType(0)      // TYPE_DEFAULT
    setContrast(列2)
    setSharpening(列3)
    setWhiteStretch(列4)         // 注意：列4实际是 black_stretch，此处疑似系统写反
    setBlackStretch(列5)         // 列5实际是 white_stretch
    setBrightness(列6)

mode != 1（高画质）:
    setEpdViewUpdateType(3)      // TYPE_GRAYSCALE
    setEpdViewDithering(2)
```

### 5.3 参数提交与优化：EpdUpdateParamsManager

`EpdUpdateParamsManager` 是整个机制的“大脑”，关键成员：

```java
SparseArray<HashMap<EpdUpdateParams, WindowState>> sEpdUpdateParamsTable;
ArrayList<UserParams> sUserParamsList;                  // Provider 全表缓存
ArrayList<String> sFullUpdateAppsList;                  // {"com.tencent.mm", "com.amazon.kindlefc", "com.sina.weibo"}
WindowState sFullUpdateWin;                              // 最近一次全刷窗口
EpdUpdateParams sAnimationParams; WindowState sAnimationWin; // 最近一次带动画的窗口
```

`generateEpdUpdateId(win, params)` 流程：

1. 调用 `optimizeApp(prevWin, win, params)` 做内置优化：
   - 窗口类型为 `0x7db / 0x7dc`（输入法 / 候选窗）时，保存当前 updateType/dithering，套用 `sEpdControllerUpdateParams`。
   - 对指定 App 硬编码修正：
     - 微信 `ImageGalleryUI` → sharpening=1
     - `WebViewActivity` → sharpening=2, contrast=4, black=70
     - 同花顺 `io.dcloud.H5B892C49/...Hexin` → bright=40, contrast=8, white=125
     - 追书神器 `ReaderActivity` → bright=80
     - 京东阅读 `ReadOverlayActivity` → updateType=3, dithering=1
   - 遍历 `sUserParamsList`，将 `activity` 与窗口标题匹配：
     - `mode == 1`：`updateType=0` + 六项用户参数（contrast/sharping/white/black/bright）
     - `mode != 1`：`updateType=3` + `dithering=2`，六项参数归零/置白（white=255，其余 0）
2. 调用 `EpdUpdateMaster.generateNewEpdUpdateId()` 生成一个 native updateId。
3. 将 `{params, win}` 存入 `sEpdUpdateParamsTable`。
4. 若 `updateType == 4 或 11`（全刷），记录 `sFullUpdateWin`。
5. 若 `params.getCustomAnimation() != null`，记录 `sAnimationParams / sAnimationWin` —— 这是 Launcher 翻页动画被系统记住的路径。
6. 打印 log（与 logcat 中观察到的一致）：
   ```
   Saved EpdUpdateParams for updateId 5541: |4|1|0|2|10|0|255|70|false|false|false|60|; win: Window{...}
   ```
   该数组即 `EpdUpdateParams.toArray()`：`updateType, dithering, updateMode?, sharping, contrast, bright, whiteStretch, blackStretch, flags..., customAnimation 长度`。

### 5.4 系统服务：epdupdateservice

`IEpdUpdateService` 在 `EpdDisplayParamsThread`（一个 `ServiceThread`，线程名 `com.yotadevices.EpdDisplayParams`）上注册，服务名 `epdupdateservice`。

`EpdUpdateService.getEpdUpdateParameters(updateId)` 流程：

1. 若存在 Keyguard 保存的 updateId，优先读取并清掉。
2. 从 `sEpdUpdateParamsTable` 取出并移除该 updateId 对应的 map。
3. 若该窗口正好是 `sAnimationWin`，把保存的 `customAnimation` 塞回；若 updateType != 4，则清掉 customAnimation。
4. 若该窗口正好是 `sFullUpdateWin`，强制 `updateType=4`。
5. 特殊窗口处理：
   - 标题含 `InputMethodEpd` 或 `AuthPortalUIActivity` → `updateType=0, dithering=0, customAnimation=null`。
   - 存在可见的 InputMethod 窗口 → 同样清零。
6. 非 mirroring 状态时，取 `getTopVisibleWindowOnEPD()` 的 `EpdUpdateParams`，调用 `EpdUpdateMaster.setParametersSuperposition(params, topParams, type)` 做参数叠加。
7. 若最终 `updateType != 0`，强制 `contrast=0, sharpening=0, white=255, black=0, bright=0`。
8. 打印 log：`Read EpdUpdateParams for updateId ... : ...`。

### 5.5 Native 层

`com.yotadevices.epdmanager.EpdUpdateMaster` 只有三个 native 方法：

```java
private static native int   native_generateNewEpdUpdateId();
private static native boolean native_isIgnoredWindow(int winAttrsType);
private static native int[] native_setParametersSuperposition(int[] epd, int[] nonSystem, int winType);
```

真正的波形叠加与窗口类型过滤算法在 `libyotadevices` 的 native 代码中（不透明）。

---

## 6. 模式语义总结

| UI 名称 | DB `mode` | 波形 updateType | dithering | 六项用户参数 |
|---|---|---|---|---|
| 流畅 | 1 | 0 `TYPE_DEFAULT` | 未动（默认） | **生效**（来自 DB） |
| 高画质 | 0 | 3 `TYPE_GRAYSCALE` | 2 | 忽略，强制 `contrast=0, sharpening=0, white=255, black=0, bright=0` |

updateType 常量（来自 SDK `Epd$UpdateType`）：

```
0 = TYPE_DEFAULT
1 = TYPE_MONOCHROME
2 = TYPE_MONOCHROME_FINE
3 = TYPE_GRAYSCALE
4 = TYPE_GRAYSCALE_FINE   ← 全刷
5/6/7 = TYPE_GRAYSCALE_READER 系列
```

结论：**系统刷新管理里，「流畅」才开放对比度/锐化/黑白拉伸/亮度；「高画质」走 16 级灰度波形，固定参数（白 255 黑 0 等）。** 这与 UI 上「流畅模式才显示 SeekBar」一致。

---

## 7. 与手势和系统的关联

- `PhoneWindowManager` 中定义：
  ```
  DOUBLE_TAP_EPD_RECENT_PACKAGE = "com.baoliyota.epdparams"
  DOUBLE_TAP_EPD_RECENT_ACTIVITY = "com.baoliyota.epdparams.ParamsActivity"
  ```
- **双击墨水屏 recent 键** → 除非前台 App 在“不可调参”名单（`isNonParamForegroundApp()`）中，否则直接启动 `ParamsActivity`。
- 系统属性 `persist.sys.epdrecentenabled=true` 控制该手势总开关。
- 相关系统服务（`service list`）：
  - `epdupdateservice` → `IEpdUpdateService`（本文主角）
  - `epd_keyguard` → `IEpdKeyguardManager`
  - `mirroring` → `IMirroringManager`
  - `input_method_epd` → 输入法墨水屏参数
  - `wakelock` → `IWakeLockManager`
- 其他相关系统属性：
  ```
  [persist.sys.epdrecentenabled]: [true]
  [persist.sys.yota.adj]: [-2.0]
  [hw.epd.temperature]: [normal]
  [sys.epd.interactive]: [1]
  [ro.hardware.epd.density]: [320]
  ```

---

## 8. 对 Paper Launcher 的启示

1. 我们的 `com.yota.paperlauncher/...LauncherActivity` 已在系统 `params` 表中存在一行 `mode=0`（高画质）。这意味着系统 WMS 给我们的窗口默认走 `TYPE_GRAYSCALE + dithering 2` 波形 —— 与 Launcher 中“动画仅 `refreshMode==0` 时生效”的设计相吻合。
2. 如果用户用系统刷新管理把我们的 Activity 调成「流畅」（mode=1），系统会改走 `TYPE_DEFAULT` 并应用那六项图像参数；我们自己再调 `Epd.setUpdateMode` 或 `Epd.setEpdUpdateParams` 时，是叠加在系统这一层之上的。
3. Launcher 的翻页动画之所以能被系统正确处理，是因为 `EpdUpdateParamsManager` 会专门保存带 `customAnimation` 的窗口参数，并在 `getEpdUpdateParameters` 时按窗口原样发回。
4. 系统对 `TYPE_GRAYSCALE` 之外（updateType != 0）的波形会强制清空对比度/锐化/拉伸/亮度。因此如果未来要开放这些图像参数给用户，必须在 `updateType=0`（TYPE_DEFAULT）路径下才有意义。
5. `querySingleEpdParams` 中疑似存在 black_stretch / white_stretch 列交换 bug（列4/列5 与表定义相反），在自行写入 Provider 数据时建议以系统实际行为为准。
