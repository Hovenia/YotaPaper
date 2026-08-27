# YotaPaper v0.4

## 新增：Xposed 翻页动画集成进桌面
- Launcher 桌面本身现在就是 Xposed 模块，无需再安装单独的 PageTurn 模块。
- 在「设置 → EPD 刷新参数」中把目标 Activity 设为「动画」后，点击屏幕左侧/右侧即可触发上一页/下一页的 Yota 墨水屏横翻动画。
- 动画帧坐标使用墨水屏物理分辨率（720×1280），兼容 1080p 兼容模式布局的阅读类应用。
- 仅对目标 Activity 的 DecorView 注入，排除 Dialog / PopupWindow / 系统 UI 等非白名单窗口。
- 过滤只按 Activity 全类名匹配，不再按包名过滤。

## EPD 参数管理改进
- 应用列表展示全部已安装应用，已配置的应用排在最前。
- 编辑弹窗在「动画」模式下显示动画相关配置：注入有效窗口（ms）、动画帧数。
- 动画条目保存时：先以占位符方式逻辑删除系统 EPD Provider 中的记录，再只写入本地配置供 Xposed 模块读取，不再更新到系统 EPD SDK。
- 高画质 / 流畅模式仍正常写入系统 EPD Provider。
- 启动同步、批量设置、导入数据均遵循「动画只写本地、不写系统」的规则。

## 修复
- 修复 ComponentInfo 包名解析错误导致白名单匹配失败、所有应用都被跳过注入的问题。
- 修复动画帧生成时 x1 > x2 导致 `EPD_HAL: drawAnimation: TCON image updating error` 的问题。
- 修复 Dialog / Popup 窗口在目标应用内被误注入翻页动画的问题。

## 其他
- 新增 Xposed API 编译期桩依赖与 ProGuard keep 规则，保证 release 混淆后 Xposed 入口可用。
- Launcher 自身进程跳过注入，不影响桌面自身动画系统。

## 使用说明
1. 安装本 APK。
2. 在 Xposed Installer 中勾选本应用为模块并重启。
3. 打开桌面 → 设置 → EPD 刷新参数 → 为阅读类 Activity 选择「动画」并保存。
4. 在目标应用阅读页点击屏幕左侧（上一页）或右侧（下一页）查看动画效果。
