# DSH Pal

**离开电脑，也能接着用 DSH。**

DSH Pal 是一个个人开发的 Android 远程伴侣：在手机和平板里打开 DeepSeek Harness 的 Web 工作台，补上任务通知、连接管理、文件选择和移动布局。

它需要电脑端运行 DSH 与远程访问插件，不在手机本地运行模型。项目仍在迭代，欢迎附带复现步骤和脱敏日志报告问题。

[English](README.en.md) · [**配置核对清单（用户版）**](docs/用户核对清单.md) · [连接与两端配置](docs/connections.md) · [**给 AI 的部署契约**](docs/AI-部署契约.md) · [App 功能启用总览](docs/features-setup.md) · [第三方许可](THIRD_PARTY_LICENSES.md)

> ### 📋 第一次配？先看 [**配置核对清单（用户版）**](docs/用户核对清单.md)
>
> 那份是**给你看的**，不是给 AI 看的。它写清了三件你最需要知道的事：
>
> 1. **哪些事只能你本人做**（注册、实名、复制密钥、扫码、点系统授权）——提前知道，别配到一半卡住
> 2. **配置的正确顺序**（先公网后局域网，反过来会让你以为配完了、出门才发现用不了）
> 3. **配完怎么逐条核对**——一张打勾表，哪条 AI 说不清就是没做完
>
> 里面还有**功能使用教程**（设置里每个入口分别干嘛）和**常见「以为是坏了其实是没配」**。
>
> **如果你让 AI 帮你配**：要求它在开始前和完成后都给你看这份清单，并把关键内容贴在对话里。
> 它要是直接说「配好了」却没对照这份表，你可以直接问它：「对照用户核对清单了吗？」

> **让 AI 帮你配电脑端？** 把仓库链接丢给 AI 时，让它先读 [**AI 部署契约**](docs/AI-部署契约.md)。
>
> 那份文档写清了三件事：**要配哪些项**、**配到什么程度算配齐**、**哪些步骤必须停下来找你本人**。
> 只说「帮我部署」而不给这份清单，AI 很容易只配 Relay 就以为完事，**漏掉国内通道**。
>
> **AI 能做到的边界**：它能配完电脑端所有它有权执行的步骤，但**注册实名、手机装 App、授权、扫码这些必须你本人来**。
> 好的 AI 会明确告诉你还剩哪些没做、该你做什么；不好的 AI 会含糊地说「配好了」。

## 能做什么

- **继续使用 DSH 网页工作台**：复用会话、工作区和已安装插件，按检测到的结构适配手机、平板和分屏，不把某套插件当必装项。
- **任务进度进通知栏**：收起时看最新公开执行步骤；展开后看简短说明和最近步骤。等待确认时提供相应入口；不展示思考链、命令正文或工具返回正文。
- **兼容机型的媒体岛**：通过 Android MediaStyle / MediaSession 通道显示任务阶段，默认关闭，用户可主动开启。具体显示受 ROM、机型和系统设置影响。
- **统一管理连接**：Relay、Cloudflare Tunnel、自有 HTTPS、Tailscale、WireGuard，以及开发用 USB 入口。网络通道与 DSH 配对分别检查，保留已保存入口供切换。
- **补齐手机操作**：系统文件选择与下载适配、刷新、手势返回、左右栏滚动、本地色调与跟随 DSH 页面背景。
- **深浅色与调色盘**：保留原有深色配色，新增五种浅色预设；在设置 → 外观滑动调节色相、饱和度和明暗，预览后应用，仅保存在本机。详见[配色教程](docs/appearance-and-diagnostics.md#浅色预设与自定义调色盘)。
- **减少重复等待**：允许的静态资源与带版本插件资源在本地缓存；动态工作流仍实时联网。可分别清资源缓存、页面存储、Cookie 或站点数据。
- **出问题留得下证据**：启动阶段日志、资源字节计数、连接/业务状态提示，以及分类、有限容量、可导出的诊断记录。

## 从哪里开始

本地 `0.1.0` 新增设置 → 更新与公告，支持版本检查、显式下载与系统安装确认；开发者主页已补充。在线公告和后续更新需要发布者同步源码及 Release 附件，详见[更新与公告说明](docs/app-updates.md)。此处不代表本地版已发布到 GitHub。

1. 电脑安装并启用 `@linxin666/dsh-remote-web-ui`，确认自己运行的 DSH profile。具体命令见[连接文档](docs/connections.md)。

> **⚠️ 这个插件上游已弃用，随时可能从 npm 消失 —— 请务必固化到本地。**
>
> 作者的弃用声明原文：*"Browser-based remote control has been replaced by the native
> phone app, and this package no longer provides remote access capabilities."* ——
> 也就是**本 App 依赖的那部分功能，作者明确说不保留**。
>
> **两条必做：**
>
> **① 固化到本地**（一条命令，之后上游怎么变都影响不到你）：
>
> ```bash
> node tools/vendor-plugin.mjs
> ```
>
> 本仓库已内置 0.4.14 完整副本（`third_party/dsh-remote-web-ui`），**这一步无需联网**。
> 干完后再按提示 `pnpm install` 即可。详见[插件本地固化指南](docs/PLUGIN-VENDOR-GUIDE.md)。
>
> **② 不要用 `@latest`**：新版只提供原生 App 的协议 v1 通道（`/ios/v1`），
> **不再提供本 App 依赖的浏览器远程界面**（`/api/pair`、`/remote` 与移动触屏适配层已确认不迁移）。
> 装到新版手机会直接连不上。
2. 按已有网络条件选入口，先确认电脑端服务和转发可用。
3. 手机首次启动点击“扫码连接”，扫描电脑远程访问面板的配对二维码，自动识别实际入口；需要手工配置时再选择对应方案。
4. 后续从设置中的连接管理切换；新主机地址可能需要单独认证，VPN已登录不代表DSH已配对。

**选入口时别漏掉国内通道**：Relay / Cloudflare Tunnel / **国内 IPv4（Sakura Frp，注册在 <https://www.natfrp.com/>，需实名）** 是三条独立的公网路径。国内环境下 Relay 目前有服务端 503 故障，Sakura 通道已实测可用。详见[连接文档](docs/connections.md#国内-ipv4-通道sakura-frp)。

**电脑端配完自检**：

```powershell
node tools/deploy-status.mjs
```

会列出还差什么、缺的是谁的责任。加 `--share` 输出脱敏版，可安全贴进 Issue。

**装完 App 后**：每个功能默认是开还是关、要什么权限，见 [App 功能启用总览](docs/features-setup.md)。通知、悬浮窗等权限不给，功能会静默失效。

**想在网页里一键切换公网入口**（国内 frp ⇄ Cloudflare）：装 [公网通道切换插件](tools/pal-channel-plugin/README.md)。它只改远控插件的配置、不改它的代码，上游更新不会冲掉。装完后 DSH 设置里会出现「公网通道」区块。

**登录 Tailscale 之后不必再扫二维码**：电脑端装 `pc-plugin/autopair.mjs` 即可，说明见 [pc-plugin/README](pc-plugin/README.md)。它确认来源是你自己 tailnet 的设备后，从回环代签一次配对令牌；`/pair-accept` 仍是同一个端点、同一套校验。

**国内 HTTPS、Relay 与 Cloudflare Tunnel 是分开的路径。** 默认二维码实际指向哪条路径，以地址和插件配置为准；首页扫码自动识别，App 不会替你修改电脑的域名优先级。

Tailscale 需要电脑端登录（安装：<https://tailscale.com/docs/install/windows>）和正确配置 Serve，手机端完成内置登录与系统 VPN 授权。WireGuard 需要真实可达的服务端和签发配置；只安装 App 并不能建立可用对端。

## 当前验证边界

2026-10-31 更新：新增 [国内 IPv4 通道与复刻工具](tools/remote-access/README.md)。Sakura 国内 TCP 节点及有效 HTTPS 证书已在 Android 16 手机的联通移动数据上完成工作区、既有会话及文件差异读取，并验证重新打开和前后台返回。这是绕开原 Relay 503 的独立备用入口；原 Relay 服务端故障、其他运营商及 Android 17 不能据此称已修复或已验证。以下段落保留 2026-10-29 的历史验收边界。

截至 2026-10-29 的侧栏修复基线：175 个 Kotlin 单测及布局脚本检查通过；手机与平板分别验证了左栏整体滚动、工作区独立滚动和右栏内容滚动，手机验证右栏反复开合及关闭后对话恢复滚动。

这些操作在 USB 开发通道下完成了页面级验证。**最近一次正常正式包公网加载复查仍有超时和探测异常，没有宣称通过。** 历史有 Tailscale 端到端成功记录，但当前网络仍可能经过波动的中继。

收尾另发现平板熄屏启动时前台服务超时退出，尚待定位。已知待办还包括：健康探测地址可能与业务地址不一致；不同插件组合仍需回归；WireGuard 暂无可用服务端进行真实网络验收；平板经典通知的完整展开、短横屏大键盘和重负载帧率仍有验证空缺。完整待办以本页与 `docs/` 下的技术文档为准。

## 实现方式

```text
电脑 DSH + 远程访问插件 → 选定的入口 → Android WebView
                                       ├─ 布局 / 文件 / 主题 / 缓存适配
                                       └─ 旁听原页面 WebSocket
                                           → EventReducer / TaskState
                                           → 原生通知 / MediaStyle 媒体岛
```

事件桥接旁听页面既有的任务事件，不额外建立一条任务状态 WebSocket。用户主动回复、文件上传和连接探测属于另外的正常交互，不能把整个 App 描述为“从不发送请求”。

左右栏优先保留插件的原生事件，只有识别到对应结构才补布局。没有对应插件时应回退正常网页，而不是展示固定空面板。

## 构建

需要 JDK 21、Android SDK 36，以及 ASCII 工程路径。

```powershell
$env:JAVA_HOME='D:\JDK21'
$env:ANDROID_HOME='D:\AndroidSdk'
$env:GRADLE_USER_HOME='D:\gradle-home'
& 'D:\AndroidSdk\gradle\gradle-8.14.4\bin\gradle.bat' -p '<仓库根目录>' `
  --no-daemon :app:assembleRelease :app:testDebugUnitTest --console=plain
```

内嵌 Tailscale 库不在 Git 中。首次构建先阅读 [原生库准备说明](third_party/tailscale-bindings/README.md) 与 `tools/prepare-tailscale-sdk.ps1`；不要将“本机能构建”当作已验证干净克隆和当前 CI。

- Kotlin **2.3.20**、AGP **8.13.2**、Gradle **8.14.4**、Miuix **0.8.8**。
- **不添加 Compose BOM，不升级 Miuix 到0.9.x**；保持已验证的依赖组合。
- 普通 debug 包名是 `com.dsh.mobile.debug`；正式与 USB 开发 release 包名是 `com.dsh.mobile`。
- release 签名从本地 `keystore.properties` 读取；缺少时会用 debug 密钥，发布前须配置自己的稳定签名。密钥和密码不入库。
- **覆盖安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 就是签名不符**：设备上已装正式签名包，而本次构建用了 debug 回落签名。此时**不要卸载重装**（会丢通知授权、连接、草稿与 VPN 账号），应补齐 `keystore.properties` 后重新构建再 `install -r`。文件格式（值不要入库）：
  ```properties
  storeFile=<绝对路径>/your-release.jks
  storePassword=<口令>
  keyAlias=<别名>
  keyPassword=<口令>
  ```
- 设备安装只用 `adb -s <serial> install -r <apk>` 保留数据。

## 隐私、权限与许可

连接地址、站点认证和 VPN 配置保存在应用私有空间。日志提供分类开关和脱敏导出，公开前仍须检查是否包含业务内容或敏感信息。任务公开说明可能出现在通知和锁屏上，可在设置中管理通知。

App 没有内置广告或分析 SDK；所用网络方案仍可能连接 Tailscale、身份认证、Relay、Cloudflare 等基础设施，不能理解为“只访问一个服务器”。内置 VPN 也可能与设备上其他 VPN 互斥。

- 应用代码：[Apache-2.0](LICENSE)。
- 鲸鱼娘形象：仓库现有素材按 **CC BY-NC-SA 4.0** 及作者署名分发，**不包含任意商业使用授权**。
- MiSans、DSHgo 参考、Tailscale/Go、WireGuard 等详见 [第三方说明](THIRD_PARTY_LICENSES.md)。

感谢 DSH、远程访问插件、[DSHgo](https://github.com/just-a-FA-for-name/DSHgo)、Miuix 及相关开源项目。本项目不是 DeepSeek、DSH 或小米的官方客户端，无相关机构背书。

## 报告问题

请提供 App版本、Android/ROM/WebView版本、手机或平板/屏幕方向、连接类型、相关插件、复现步骤、期望与实际、脱敏日志或录屏。**不要上传完整配对链接、Cookie、Tailscale授权链接、WireGuard私钥或私人会话。**

需要 AI 视角的部署清单，看 [AI 部署契约](docs/AI-部署契约.md)。

- 项目主页：<https://github.com/yiheng-zyh/DSH-Pal>
- 问题反馈：<https://github.com/yiheng-zyh/DSH-Pal/issues>
- 下载：**[Releases](https://github.com/yiheng-zyh/DSH-Pal/releases)** —— 最新为 `v0.1.0`，正式版；升级前请先读该版本的已知问题
- 哔哩哔哩：<https://b23.tv/8o1bX3M>（「电脑还在跑，我先溜了！给 DSH 做个手机搭子｜DSH Pal 开源」）

> 从 `0.1.0-alpha.1` 起使用维护者专用发布签名。与更早的 debug 签名测试包**签名不同**，旧测试包不能直接覆盖升级；本版之后的版本可以正常覆盖更新。
