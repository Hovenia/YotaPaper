# Yota Paper

专为 Yota3 双屏墨水屏设计的极简启动器：白纸黑字、1px 灰线分割、无卡片堆叠，面向 E-Ink 的 Paper UI。

本项目是 Yota Paper 启动器的干净源码副本（去除构建产物与本地配置），功能与当前设备上安装的版本一致。

---

## 功能

- **主页 4×3 / 应用 4×5**：应用按「固定优先 → 点击次数」自动排序
- **手势翻页**：左右或上下滑动翻页；翻页/开屏/息屏动画（仅 Yota 设备 + 高画质模式生效）
- **刷新主模式**：高画质 / 流畅 / 自适应（Yota 设备可见）
- **最近任务**：双击底部「主页」标签（或背屏 HOME）打开，支持时间范围过滤、清空、取消行为设置
- **应用管理**：长按图标固定/隐藏/查看信息；「管理」模式批量隐藏/显示/卸载
- **图标**：支持第三方图标包（appfilter 映射）；自动绘制线条图标并本地缓存
- **实时状态**：WiFi / 蓝牙文字单击开关、长按进系统设置；时钟点击手动全刷清除残影
- **WiFi 传书**：手机在 25025 端口开启网页服务，电脑浏览器上传文件到 `WIFI_transfer` 文件夹
- **锁屏**：Yota 设备调用背屏 SDK `lockEpd()`（无提示）；非 Yota 设备回退设备管理器锁屏
- **EPD 参数管理**：按应用配置背屏刷新参数并自动同步（Yota 设备）
- 应用安装 / 卸载 / 更新后自动刷新列表

## 文档

| 文档 | 说明 |
|---|---|
| [docs/USER_GUIDE.md](docs/USER_GUIDE.md) | 使用说明（交互逻辑） |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构与源码结构 |
| [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) | 第三方依赖与许可证 |
| [docs/Yota系统刷新管理分析.md](docs/Yota系统刷新管理分析.md) | Yota 墨水屏刷新机制调查记录 |

## 构建

### 环境要求

- JDK 17+
- Android SDK 36（`platforms/android-36`、`build-tools/36.0.0`）
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
adb shell appops set com.yota.paperlauncher GET_USAGE_STATS allow
```

## Yota SDK 说明

- `libs/yotadevice_sdk-full-stub.jar` 是 **编译期 Stub**（所有方法抛 `Stub!`），通过 `compileOnly` 引入，**不会打进 APK**。
- 真机上，`AndroidManifest.xml` 通过 `<uses-library android:name="com.yotadevices.sdk" android:required="false"/>` 声明使用系统共享库；运行时代码会通过反射/系统类加载获取 `EpdManager` 等实现。
- 非 Yota 设备可正常编译安装，SDK 相关功能自动降级（锁屏走设备管理器，刷新/动画相关 UI 自动隐藏）。

## 许可证

- 本项目源码采用 **GNU Affero General Public License v3.0 (AGPL-3.0)**，全文见 [LICENSE](LICENSE)。
- `libs/yotadevice_sdk-full-stub.jar` 为 YotaDevices 专有 SDK Stub，不属于 AGPL-3.0 覆盖范围，请按 YotaDevices SDK 条款处理。
- ZXing Core 为 Apache-2.0，与 AGPL-3.0 兼容；保留声明见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。

## 版本

- `app/build.gradle.kts`：`applicationId = "com.yota.paperlauncher"`，`versionName = "0.3"`
- 源码包名：`com.yota.launcher`
