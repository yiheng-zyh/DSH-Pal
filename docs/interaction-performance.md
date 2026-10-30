# 移动端交互与性能维护

更新：2026-10-30。对应 0.1.0-alpha.3 源码；不代表已发布新 Release。

## 本轮处理

- 修复移动样式中四行损坏的选择器/字符串。真机上原整份样式没有解析出规则，不能把此前的显示表现当成所有移动样式均已生效。
- 手机左栏使用位移与遮罩动画，退出整页 View Transition 快照流程。空闲时准备已识别的侧栏，之后本地开合保留工作区、会话列表和滚动内容。`data-dsh-left-closed` 只表示本机隐藏，不修改桌面布局配置。原页面仍拥有功能和数据。
- 手机右栏使用独立的 `phone-right-motion.js`。收起保留插件面板和文件预览，提供本地重开入口；隐藏后释放聊天触摸，重开恢复面板内容。文件预览等明确打开右栏的操作同时解除本地隐藏。未知结构和宽屏保持原有分支。
- 本地隐藏的侧栏通过 `inert` 和既有显示规则隔离焦点/触摸。窗口切换到宽屏时恢复原界面。返回处理同时识别本地展示状态，避免隐藏侧栏持续吞掉返回操作。
- 避免聊天内容变化反复扫描侧栏。标签切换只更新指示器，取消扫描整个面板、等待空闲后再次播放内容动画的流程。
- 触摸按钮、标签、菜单项与会话树节点使用局部按压反馈。正文与输入区域保留选择、复制能力，不全局拦截长按。
- 设置中的缓存、日志、关于页进入统一转场；各设置分区保留滚动位置，移除与页面转场叠加的卡片延迟入场。原生按压反馈改为短时动画。
- 日志采用 LazyColumn，保留原有最近 120 条显示上限；查看期间最多每 250ms 更新一次展示，日志采集策略不变。
- 加入从真实设备操作提取的 UI Baseline Profile。现有 ProfileInstaller 已由依赖引入，无新增/升级运行库。预编译清单的收益受安装方式、系统编译状态和设备影响，不是网页绘制加速器。

## 真机验证范围

本轮使用 Android 16 手机，先检查既有连接，再通过 USB 开发通道复用电脑端服务。手机原无 USB 配对，经维护者明确同意后新增一次；没有反复签发配对或批量删除旧设备。

已进行左右栏真实触摸开合、会话/插件导航、文件预览及正文滚动、按钮长按，以及原生设置、外观、缓存和日志页面操作。文件预览真实滚动从 0 到约 263 CSS 像素，内容高度约 7303、视口约 616 CSS 像素。布局尺寸是本轮窗口值，不是固定设备能力。

左栏一组开合采样中，保留内容后的展开起步约 63–110ms。此值来自网页侧采样，包含采样开销；没有建立同负载的完整 A/B 帧率实验，不据此宣传固定倍数、恒定 120fps 或所有插件均已流畅。

平板、其他 ROM、所有第三方插件、极重会话与公网业务连通性需分别验收。USB 操作通过不代表公网链路恢复，既有平板前台服务问题不在本轮修复范围。

## 检查与构建

```text
node tools/test-web-layout-syntax.mjs
node tools/test-web-drawer.mjs
node tools/test-web-right-layout.mjs
node tools/test-web-viewport.mjs
gradle --no-daemon :app:assembleRelease :app:testDebugUnitTest
```

样式检查同时检查引号、注释、括号；编译 Kotlin 成功不能代替浏览器解析检查。新增插件、切换显示模式时需验证关闭后的命中区域、滚动、返回与重复开合。

`-PdshUsbDevelopment=true` 是隔离 USB 连接配置的开发包。`-PdshWebInspection=true` 可临时检查当前正式入口而不切换配置空间；两个参数在正常构建中均默认关闭。交付正常 APK 时检查 `USB_DEVELOPMENT=false`、`WEB_INSPECTION=false`、非 debuggable 和签名一致。

需要测量预编译时，可用 `-PdshUsbDevelopment=true -PdshBenchmark=true` 构建非 debuggable 的 USB 测量包；普通可调试 USB 包不包含应用 Baseline Profile，不能据它判断预编译是否生效。测量包仍有开发通道，不能作为正常发布包交付。

## 更新预编译清单

先在可检查的构建上实际操作启动、设置导航、侧栏、滚动等路径，再保存 ART 文本清单。所有 ADB 命令明确指定当前设备；原始清单与诊断文件只保存在本机。

```text
adb -s <serial> shell cmd package dump-profiles --dump-classes-and-methods com.dsh.mobile
adb -s <serial> pull /data/misc/profman/com.dsh.mobile-primary.prof.txt <private-art-profile.txt>
python tools/extract-ui-baseline.py <private-art-profile.txt> app/src/main/baseline-prof.txt
```

过滤脚本仅输出应用界面和实际运行过的界面库规则，不收集会话、URL、凭据或设备标识。更新 Compose、Miuix 或大量原生界面实现后重新采集；构建后确认 APK 内 `assets/dexopt/baseline.prof` 和 `baseline.profm` 有效。ProfileInstaller 安装配置与 ART 编译完成是不同阶段，测量时应记录实际编译状态。
