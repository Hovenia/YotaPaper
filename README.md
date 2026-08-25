# Yota Paper

> 白纸黑字 · 1px 灰线 · 无卡片 · 无打扰 —— 为 Yota3 双屏墨水屏而生的 Paper UI 启动器。

Yota Paper 是一款专为 Yota3（YOTA 3+，Android 7.1.1）墨水屏副屏设计的极简启动器。它回归“纸”的本质：白色纸面、黑色衬线字体、1px 灰色发丝线，没有任何卡片、阴影和多余装饰。所有交互都为 E-Ink 屏幕优化，刷新克制，操作即点即达。

## 功能一览

### 主页
- **4×3 网格**，应用按「固定优先 → 点击次数」自动排序，越常用越靠前
- 单击图标打开应用；长按图标弹出操作菜单（**不淡化背景、无震动**）：固定 / 取消固定、隐藏、应用信息
- 双击底部「主页」标签打开最近任务；再次按 HOME 或点空白处关闭
- 左下角时间点击触发一次**手动全刷**，快速清除残影

### 应用抽屉
- **4×5 网格**，左右或上下滑动翻页
- 左上角「管理」进入批量模式：勾选应用后批量**隐藏 / 显示 / 卸载**
- 应用安装 / 卸载 / 更新后自动刷新列表

### 墨水屏刷新与动画（仅 Yota 设备）
- **刷新主模式**：高画质 / 流畅 / 自适应
- 翻页动画：关闭 / 左右翻页 / 水平展开 / 水平闭合 / 上下翻页 / 垂直展开 / 垂直闭合
- 开屏动画与息屏动画各有开关和样式，动画仅在高画质模式生效
- **EPD 参数管理**：按应用配置背屏刷新参数，并自动同步到系统

### 最近任务
- 双击「主页」标签（或双击背屏 HOME）打开，以**网格**展示，底部为取消栏
- 默认只显示**最近 30 分钟**内使用过的应用；时间范围可设为 关 / 5分钟 / 30分钟 / 1小时 / 6小时 / 1天
- 标题右侧一键**清空**；取消行为可选：回到上一应用 / 回到桌面

### 图标
- 支持第三方图标包（appfilter 映射），未覆盖的应用回退系统图标
- **自动绘制线条图标**：根据应用原图标生成黑色线条图标并本地缓存

### 状态与快捷操作
- **WiFi / 蓝牙**：单击文字直接开关，长按进入对应系统设置，电量等信息实时刷新
- **WiFi 传书**：单击顶部时钟开启，手机在 25025 端口提供网页上传服务，文件保存到 `WIFI_transfer`
- **锁屏**：Yota 设备调用背屏 SDK `lockEpd()` 静默锁屏（无 Toast）；非 Yota 设备自动回退设备管理器锁屏

## 安卓版本需求

| 项目 | 值 |
|---|---|
| 最低支持 Android | **4.2（API 17，minSdk 17）** |
| 目标/编译 SDK | **Android 16（API 36，targetSdk / compileSdk 36）** |
| 目标设备 | Yota3（Android 7.1.1，EPD 背屏） |
| 其他 Android 设备 | 可安装运行，SDK 相关功能自动降级 |

> 说明：`minSdk 17` 保证老设备也能安装；但墨水屏相关能力只在 Yota3 上生效，普通设备会自动隐藏刷新/动画设置并回退锁屏方式。

## 构建

### 环境要求

- **JDK 17+**
- **Android SDK 36**（`platforms/android-36`、`build-tools/36.0.0`）
- 无需单独安装 Gradle：仓库自带 Gradle Wrapper

### 一键构建 / 部署

```bash
# Windows 一键构建
scripts\build.bat

# Windows 一键部署（构建 + adb install + 授予使用情况权限 + 启动）
scripts\deploy.bat

# macOS / Linux 一键构建
scripts/build.sh
```

脚本会自动探测 Android SDK（`ANDROID_HOME` / `ANDROID_SDK_ROOT` / 常见安装路径）。

### 手动构建

```bash
# Windows
set ANDROID_HOME=C:\path\to\android-sdk
gradlew.bat assembleDebug

# macOS / Linux
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

> 没有 `local.properties` 时，Gradle Android 插件会读取 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量定位 SDK。
> 也可以在项目根目录创建 `local.properties`：
>
> ```properties
> sdk.dir=C\:\\path\\to\\android-sdk
> ```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到 Yota3

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 授予“使用情况访问权限”，最近任务列表依赖它
adb shell appops set com.yota.launcher GET_USAGE_STATS allow
adb shell am start -n com.yota.launcher/.LauncherActivity
```

## 文档

| 文档 | 说明 |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | 完整使用说明（交互逻辑） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构与源码结构 |
| [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) | 第三方依赖与许可证 |
| [docs/Yota系统刷新管理分析.md](docs/Yota系统刷新管理分析.md) | Yota 墨水屏刷新机制调查记录 |

## Yota SDK 说明

- `libs/yotadevice_sdk-full-stub.jar` 是 **编译期 Stub**（所有方法抛 `Stub!`），通过 `compileOnly` 引入，**不会打进 APK**。
- 真机上，`AndroidManifest.xml` 通过 `<uses-library android:name="com.yotadevices.sdk" android:required="false"/>` 声明使用系统共享库；运行时代码会通过反射/系统类加载获取 `EpdManager` 等实现。
- 非 Yota 设备可正常编译安装，SDK 相关功能自动降级。

## 许可证

- 本项目源码采用 **GNU Affero General Public License v3.0 (AGPL-3.0)**，全文见 [LICENSE](LICENSE)。
- `libs/yotadevice_sdk-full-stub.jar` 为 YotaDevices 专有 SDK Stub，不属于 AGPL-3.0 覆盖范围，请按 YotaDevices SDK 条款处理。
- ZXing Core 为 Apache-2.0，与 AGPL-3.0 兼容；保留声明见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。

## 版本

- 源码包名：`com.yota.launcher`
- `applicationId`：`com.yota.launcher`
- `versionName`：`0.3`（`versionCode 3`）
