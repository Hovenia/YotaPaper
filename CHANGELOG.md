# 更新日志

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
