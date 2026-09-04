# 更新日志

## [0.5] - 2026-09-04

### 控制中心：两轮合并重构

**第一轮：控制中心并入主页窗口（分场景混合方案）**

- 桌面场景（主页前台）下拉改为在**主页同一窗口内**打开浮层：与主页翻页动画同一机制（先 `EInkSdk.applyPageTurn` 武装、再切换可见性），开合连贯、无“新窗口首帧吞动画/动画延后”问题。
- 其它应用前台下拉仍走独立 `ControlCenterActivity` 窗口兜底，保证“任何界面都能唤出”。
- 手势服务按 `LauncherActivity` 是否前台自动分发：主页 → 广播打开浮层；其它应用 → 启动兜底窗口；上拉 / BACK / 广播统一收起。

**第二轮：两份实现合并为单一共享核心（去重）**

- 抽出共享 `ControlCenterUi`：按钮绑定、状态刷新、通知列表、媒体卡、授权流程、音量等逻辑**只保留一份实现**（此前 `ControlCenterActivity` 与浮层互为复制，约 600 行重复代码有分叉风险）。
- `ControlCenterActivity`（兜底窗口）与 `ControlCenterPanel`（主页浮层）都变成薄壳，只负责窗口形态、生命周期与动画决策。

### 动画规则

- 动画开关开启时**仅在主页浮层播放** EPD 动画；其它应用前台的兜底窗口**一律不播动画**（新窗口无法承载连贯动画，改为瞬时开合，避免“先亮一帧”）。
- 修复收起偶发“播两遍刷新才收干净”：收起前取消待执行的延迟补刷、隐藏时不再重建视图，使每次开 / 合只产生一次干净的 EPD 更新。
- 下拉呼出手势的起手区由屏幕顶部 **25%** 收紧为 **10%**（EPD 1280px 高 → 顶部 128px 内起手），更贴近状态栏下拉习惯。

### 最近任务清理（Root）

- 清理提速：运行中的应用**最多 3 路并发强停**（libsu 单壳内为串行队列，改用独立 su 进程并发），串行等待 ≈ 单批时间。
- 只对**仍在运行的进程**执行 root force-stop；已无进程的空闲项不再浪费强停。
- 修复“清理失效/杀不掉”回归：Android 5.0+ 的 `getRunningAppProcesses` 对第三方应用只返回自身进程，会把其它应用全部误判为“空闲”；改为 root `ps` 枚举进程名（完整包名，含 `pkg:子进程`），枚举失败时自动退回“全杀”，保证旧行为不失效。

### 修复

- 修复 Android 4.x 低版本上 Launcher 顶部出现系统标题栏：主题父类由 `Theme.Material.Light.NoActionBar` 调整为 `Theme.DeviceDefault.Light.NoActionBar`（Material 主题在低版本没有无 ActionBar 变体的可靠兜底）。

## [0.4] - 2026-08-27

- Xposed 翻页动画集成进桌面；EPD 参数管理改进（动画只写本地、不写系统）；修复白名单解析与动画帧坐标问题。详见 [docs/release-notes-v0.4.md](docs/release-notes-v0.4.md)。

## [0.3] - 2026-08-24

### 首个开源版本

Yota Paper 首个以 AGPL-3.0 发布的版本。面向 Yota3 双屏墨水屏的 Paper UI 极简启动器。

### 新增功能

- **Paper UI**：白纸黑字、衬线字体、1px 灰色发丝线，无卡片、无阴影、无多余装饰。
- **主页网格**：默认 4×3；应用按「固定优先 → 点击次数」自动排序；长按图标可固定 / 取消固定 / 隐藏 / 查看应用信息。
- **应用抽屉**：默认 4×5；左右或上下滑动翻页；「管理」模式批量隐藏 / 显示 / 卸载应用。
- **最近任务**：双击底部「主页」标签（或双击背屏 HOME）打开；以网格展示；标题右侧一键清空；底部取消栏；默认仅显示最近 30 分钟使用过的应用，时间范围支持 关 / 5分钟 / 30分钟 / 1小时 / 6小时 / 1天；取消行为可配置为回到上一应用或回到桌面。
- **刷新与动画**（仅 Yota 设备）：刷新主模式 高画质 / 流畅 / 自适应；翻页动画、开屏动画、息屏动画；动画仅在高画质模式生效；非 Yota 设备自动隐藏相关设置。
- **EPD 参数管理**（仅 Yota 设备）：按应用配置背屏刷新参数，并自动同步到系统。
- **图标**：支持第三方图标包（appfilter 映射）；自动绘制线条图标并本地缓存。
- **实时状态**：WiFi / 蓝牙文字单击开关，长按进入对应系统设置。
- **WiFi 传书**：单击顶部时钟开启，25025 端口网页上传，文件保存到内置存储 `WIFI_transfer`。
- **静默锁屏**：Yota 设备调用背屏 SDK `lockEpd()`（无提示）；非 Yota 设备回退设备管理器锁屏。
- **应用自动刷新**：安装 / 卸载 / 更新应用后自动刷新桌面列表。
- **设置分组折叠**：网格与主页、图标、最近任务、刷新与动画、EPD、系统；多选项使用底部弹窗列表选择；一键恢复默认。

### 系统要求

- Android 4.2（API 17）及以上
- 目标 / 编译 SDK：Android 16（API 36）
- 墨水屏相关能力仅在 Yota3 上生效，其他设备自动降级

### 构建

- JDK 17+
- Android SDK 36
- `scripts\build.bat`（Windows）/ `scripts\build.sh`（macOS / Linux）一键构建
- `scripts\deploy.bat` 一键部署（构建 + 安装 + 授权 + 启动）

### 权限说明

- `PACKAGE_USAGE_STATS`：最近任务列表（需在系统设置或通过 adb 授予）
- `QUERY_ALL_PACKAGES`：枚举已安装应用
- `REQUEST_DELETE_PACKAGES`：管理模式下卸载应用
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE`：WiFi 状态开关
- `BLUETOOTH` / `BLUETOOTH_ADMIN`：蓝牙状态开关
- `ACCESS_NETWORK_STATE` / `INTERNET`：WiFi 传书
- `WRITE_EXTERNAL_STORAGE`（仅 Android 9 及以下）：WiFi 传书写入文件
- `com.yotadevices.permission.ACCESS_BACK_SCREEN`：Yota 背屏

### 已知限制

- YotaDevices SDK 为编译期 Stub（`compileOnly`），不打包进 APK；EPD 相关功能依赖设备系统共享库。
- 最近任务依赖系统使用情况统计，首次使用需授予「使用情况访问」权限。
- 目前仅 Yota3 真机完整测试；其他 Android 设备可安装，但 EPD 功能自动禁用。

### 许可证

- 本项目源码：AGPL-3.0
- ZXing Core 3.5.1：Apache-2.0
- YotaDevices SDK Stub：专有，不属 AGPL-3.0 覆盖范围
