# 第三方依赖与许可证

## 当前依赖清单

| 依赖 | 版本/来源 | 许可证 | 用途 | 是否随 APK 分发 |
|---|---|---|---|---|
| `libs/zxing-core-3.5.1.jar` | ZXing 3.5.1 | Apache-2.0 | WiFi 传书二维码生成 | 是 |
| `libs/yotadevice_sdk-full-stub.jar` | YotaDevices SDK（编译期 Stub） | YotaDevices 专有 | 编译期引用 `com.yotadevices.sdk` API | 否（`compileOnly`） |
| Gradle Wrapper | `gradle/wrapper/*` | Apache-2.0 | 构建工具 | 否（仓库含 wrapper，运行时下载发行版） |
| Android Gradle Plugin | Google Maven | Android SDK 许可 | 构建工具 | 否 |
| Kotlin（AGP 内置 Kotlin 支持） | JetBrains | Apache-2.0 | 语言/编译 | 否（stdlib 运行时由系统/APK 按需携带） |

## 许可证兼容性分析（针对本项目源码选型）

### 结论

- **最严格（也最容易踩坑）的是 `libs/yotadevice_sdk-full-stub.jar`**：它是 YotaDevices 专有 SDK 的 Stub。它不属于本项目许可证覆盖范围，也不能被本项目重新许可。
- 实际打进 APK 的第三方代码只有 **ZXing Core（Apache-2.0）**，这是宽松许可证。
- 因此，**你自己的源码可以选择任何与 Apache-2.0 兼容的许可证**，从最宽松到最严格都可行。

### 可选许可证（由宽到严）

| 许可证 | 能否使用 | 说明 |
|---|---|---|
| MIT | ✅ | 最宽松，仅要求保留版权声明 |
| Apache-2.0 | ✅ | 与 ZXing 一致，Android 生态最常用 |
| GPL-3.0 | ✅ | 强 copyleft：分发需开源衍生代码；与 Apache-2.0 兼容 |
| AGPL-3.0 | ✅（最严格） | 比 GPL-3.0 更严：网络服务也需提供源码；与 Apache-2.0 兼容 |

### 建议

- 想省事、生态一致：选 **Apache-2.0**。
- 想要最严格的常见开源许可证：选 **AGPL-3.0**（其次 GPL-3.0）。

## 需要保留的声明

### ZXing Core 3.5.1

```
Copyright 2007-2020 ZXing authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

项目主页：https://github.com/zxing/zxing

### YotaDevices SDK Stub

`libs/yotadevice_sdk-full-stub.jar` 仅用于编译期引用。发布本项目源码时，请确认你有权分发该文件；如不希望携带，可将其从 `libs/` 移除，并自行从 YotaDevices SDK 获取后放回 `libs/` 再编译。

## 项目自身许可证

本项目源码采用 **GNU Affero General Public License v3.0 (AGPL-3.0)**，全文见仓库根目录 `LICENSE`。
