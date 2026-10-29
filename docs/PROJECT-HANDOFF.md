# DSH Pal：项目总交接与 Issue 修复手册

> 更新日期：2026-10-29，Asia/Shanghai。面向接手本仓库的新对话或维护者。
> 本文可以单独提供给新对话，再附 Issue；无需复制整段历史聊天。前提是接手者能访问项目源码。没有源码时，本文只能支持分析，不能代替工程文件。
> 历史名：DSH / DSH Mobile；现展示名：**DSH Pal**。包名仍为 `com.dsh.mobile`，项目目录仍为 `D:\dsh-mobile`。不要为改名迁移数据或重建配对。

## 1. 给接手对话的完整启动指令

复制下面一段，附上本文和待修 Issue 即可：

```text
请接手 DSH Pal Android 项目，本地源码 D:\dsh-mobile。
先完整阅读我附的 PROJECT-HANDOFF.md，再检查仓库 AGENTS.md（如有）、git status、git log -8、adb devices -l。
以当前源码、Issue 复现和现场证据为准；交接是带日期的快照，不是永久成立的结论。
请先告诉我：你理解的故障、准备检查的文件、如何在真实设备复现与验收，然后直接推进已授权工作。
不要先清数据、重新配对、升级依赖、改服务器绑定或把网络失败当成布局故障。
手机和平板必须明确区分，所有 ADB 命令带 -s；一端通过不能代表另一端通过。
做最小修复，保留已有连接、通知授权、VPN账号、草稿与主题设置。
交付前验证 Issue 的用户操作流程，补适当回归；构建成功不等于真机通过。
更新本文的现状、.ai/HANDOFF.md、DECISIONS.md、WORKLOG.md，用中文提交并保持工作区可交接。
以下是本次 Issue：
【在这里粘贴问题、复现步骤、设备信息、日志或视频】
```

## 2. 项目定位与边界

这是一个个人开发的 Android 远程伴侣：电脑运行 DeepSeek Harness（DSH）和远程访问插件；手机通过 WebView 加载 DSH 的网页工作台，原生层提供连接管理、通知、兼容机型的媒体岛、文件选择、外观、缓存和诊断。

- **不是手机本地运行完整 DSH 或模型**，电脑端仍需运行且入口可达。
- **没有另写一套聊天协议或固定插件界面**：复用 DSH Web UI，按检测到的结构补布局与桥接。
- 不是 DSHgo 的改名版。参考过 DSHgo 的连接体验与实现，保留相应致谢和许可。
- 用户称“Cloudflare”的历史扫码入口，现场常实际为 Relay。两者必须按地址和实际路径区分。
- 目标是保留插件功能并适配手机/平板；不能把当前作者装的插件集合当作所有用户的固定配置。

## 3. 接手时先确认什么

```powershell
git -C D:\dsh-mobile status --short
git -C D:\dsh-mobile log -8 --oneline
& 'D:\AndroidSdk\platform-tools\adb.exe' devices -l
```

最新功能基线之一为 `cf635ea`（手机左右栏修复）。本次后续提交增加 DSH Pal 展示名和本文；请以实际 `git log` 为准，不要 reset 到这个历史节点。

若接手的是公开源码快照：旧`.ai`运维记录和原Git历史不会随包公开，文中旧提交号是本地开发历史索引，在新公开仓库未必可查。本文提供接手所需摘要；后续可新建维护记录，不要为补齐历史重新上传私有交接。

截至该次侧栏验证：175 个 Kotlin 单测通过；四组 Node 布局检查通过。手机约374×703 CSS 像素，平板横屏约1152×609 CSS像素；这是当时窗口尺寸，不能写死成设备能力。两台安装均为覆盖安装。后续DSH Pal改名包构建和175单测再次通过，但新增发现平板熄屏启动前台服务超时，详见P0待办；不能用旧USB场景覆盖本次新场景。

**当前不能宣称所有问题已经解决。** USB 开发通道已验证页面与左右栏操作；换回正常正式包后的公网加载未通过。手机入口 ICMP 五次丢包40%、平均约338ms，同时健康日志出现401；平板出现503、超时、HTTP2_PING_FAILED。健康探测来源还可能与业务 origin 不一致，故不能凭401断言 Cookie 已失效，也不能凭 ping 直接断言程序无 bug。

本机私有截图、日志和诊断记录位于 `.local/`，不随公开源码分发。只有公开仓库时，这些证据可能不存在，应重新采集。

## 4. 必须遵守的工程与环境约束

1. **不改 DSH webserver 的 host/port**：desktop 配置中必须保持回环 `127.0.0.1` 与动态端口 `0`；实际运行端口每次启动可能变化。修改为 LAN 地址曾导致桌面端无法启动。
2. 本机测试走既有公网入口或 USB；不擅自改 LAN、Windows 防火墙、插件配置、域名优先级或既有 Serve。管理员另有明确授权时也要先备份并核对副作用。
3. 安装只用 `adb install -r`。禁止卸载、`pm clear`、删除 SharedPreferences 或直接篡改凭据来“修复”。用户主动在产品内清理数据与开发者代为清数据是两回事。
4. Kotlin **2.3.20**、AGP **8.13.2**、Miuix **0.8.8**、Gradle **8.14.4**、JDK **21**。禁止加 `androidx.compose:compose-bom`，禁止升 Miuix 到0.9.x；曾导致 Compose 双版本或 Kotlin 不兼容。
5. 项目和 Gradle 用户目录保持 ASCII 路径。不得启动 Android 模拟器；AEHD 曾导致主机故障。老 WebView 的模拟器也不能证明当前前端兼容。
6. 灵动岛只使用 `MediaIsland.kt` 的 **MediaStyle / MediaSession**。不要碰 `miui.focus.param`；侧载包私有焦点身份校验已证实失败。`IslandNotificationBuilder.kt` 为历史实现，不要据它重新接通旧协议。
7. 不发送用户对话、不停真实任务、不调用付费语音服务来制造测试。展示预览必须明确标注，不能充当真实端到端任务证据。
8. 纯逻辑 `core` 类不要直接调用依赖 Android Looper 的 `RunLog`；通过回调提供日志，否则本地单测可能在类初始化时崩溃。
9. 中文沟通、中文提交；修改前说明范围，长操作给有内容的进度。ADB操作由同一主代理串行执行，防止手机与平板互相干扰。

## 5. 架构与定位入口

```text
电脑 DSH + 远程访问插件
   ├─ Relay / Cloudflare Tunnel / 自有 HTTPS
   ├─ Tailscale Serve / WireGuard 对端及受保护入口
   └─ USB 开发代理 → 本机回环服务
                 ↓
MainActivity / DshMobileApp → ShellScreen → DshWebView
                 ├─ WebView 私有 Cookie / 站点存储
                 ├─ JS 布局、插件能力、键盘、主题与导航适配
                 ├─ 原页面 WebSocket → BridgeScript → DshBridge
                 │                    → EventReducer → TaskState
                 │                    → DshLiveService → 通知 / MediaIsland
                 └─ 静态资源缓存、加载量、健康状态、日志导出
```

主要目录相对仓库根，均在 `app/src/main/java/com/dsh/mobile/` 下：

| 问题类型 | 优先文件 | 责任与边界 |
|---|---|---|
| 启动、深链、前后台 | `MainActivity.kt`、`ui/DshMobileApp.kt`、`ui/screen/ShellScreen.kt` | 恢复已有WebView和入口；不能把回到前台一律当冷启动 |
| 配对与连接切换 | `core/ConnectionProfile.kt`、`ConnectionSwitch.kt`、`ConnectionProbe.kt`、`PairingNavigation.kt`、`LaunchRouting.kt`、`MeshPairing.kt`、`SettingsStore.kt` | 地址分类、准备/探测/提交/失败恢复、每origin认证与一次性配对 |
| VPN启动/退出/授权 | `vpn/VpnController.kt`、`TailscaleEngine.kt`、`TailscaleVpnService.java`、`WireGuardKeepaliveService.kt`、`MeshDiscovery.kt`、`core/VpnSessionGate.kt` | 内置Tailscale/WG、发现目标、按需停止与拒绝迟到回调 |
| WebView与文件 | `ui/web/DshWebView.kt`、`WebCapabilities.kt`、`core/WebFilePolicy.kt` | 文件选择/下载/录音等系统能力、权限与取消回调 |
| 左右栏、插件布局 | `bridge/WebLayoutScript.kt`、`WebDrawerMotion.kt`、`WebSidebarNavigation.kt`、`assets/mobile-layout.css`、`assets/plugin-compat.js` | 手机/平板分支、能力标记、原生点击与外层/局部滚动 |
| 键盘和横竖屏 | `ui/web/WebViewport.kt`、`KeyboardInsetsBridge.kt`、`assets/keyboard-motion.js`、`core/WebLayoutMode.kt` | 实际窗口与IME inset，不能只按物理设备判平板 |
| 外观与背景 | `core/LocalAppearance.kt`、`ui/theme/DshTheme.kt`、`ui/web/WebAppearance.kt` | 跟随DSH背景/本地色调，原生设置与网页统一；不依赖某个壁纸插件存在 |
| 状态与通知 | `bridge/BridgeScript.kt`、`DshBridge.kt`、`core/EventReducer.kt`、`TaskState.kt`、`TaskNotice.kt`、`service/DshLiveService.kt`、`island/TaskNotificationBuilder.kt`、`MediaIsland.kt` | 旁听已有事件流，投影公开步骤与任务状态；不另外伪造任务连接 |
| 缓存和数据清理 | `core/StaticResourceCache.kt`、`ResourceLoadTracker.kt`、`ui/web/WebResourceCache.kt`、`BrowserDataManager.kt`、`ui/screen/CacheManagementScreen.kt` | 静态资源与认证/动态API分开管理 |
| 网络状态和自动恢复 | `core/NetworkConnectivity.kt`、`ConnectionHealth.kt`、`TaskSocketTracker.kt`、`HarnessSocketPolicy.kt`、`ui/web/DshWebView.kt` | 链路、入口、Socket、业务响应是不同证据 |
| 返回、二次退出 | `ui/AppBackHandler.kt`、`AppPageHeader.kt`、`BackNavigationHost.kt`、`core/ExitBackGate.kt` | 各级页面返回/预测性返回；根页再次操作回桌面，轻提示而非确认弹窗 |
| 日志和支持 | `core/RunLog.kt`、`LogPolicy.kt`、`LogSanitizer.kt`、`ui/ExportDiagnostics.kt`、`ui/screen/LogSettingsScreen.kt` | 分类、限量持久化、脱敏导出 |
| 桌面Serve辅助 | 根目录`pc-plugin/index.mjs`与`pc-plugin/README.md` | 仅跟踪DSH回环端口并维护自己管理的Serve，不能重置其他Serve/Funnel |

## 6. 连接：不要混淆网络、入口与配对

| 入口 | 当前状态/依赖 | 排障重点 |
|---|---|---|
| Relay | 远程访问插件提供固定中继域名；本轮产品推荐仍在此项 | 与Cloudflare直隧道分开；历史移动网络波动明显 |
| Cloudflare Tunnel | 同一远控插件体系可提供直隧道入口 | 不是另一个聊天协议；域名变化要核对origin和认证 |
| 自有 HTTPS / frp | 管理员需已有正确转发的服务 | 同时支持HTTP与WebSocket，不能把任意200网页当DSH |
| Tailscale | 内置登录、Android VPN授权、发现目标；电脑官方客户端和Serve | 已有端到端成功历史，但DERP与当前网络仍影响质量 |
| WireGuard | 内置官方tunnel库、配置导入与启动 | **尚无可用服务端实网验收**；内置客户端不能自动创造服务器 |
| USB | 仅开发版，ADB reverse与本地代理 | 适合布局验收；不能代替公网验收 |
| Wi-Fi直连 | 有入口类型 | 本环境不为测试改LAN绑定；不能宣称本轮实测通过 |

连接类型是 `TUNNEL / RELAY / CUSTOM / LAN / MESH / USB`，MESH下还有Tailscale/WireGuard/外部提供者。不能因UI分成更多卡片，就把底层都当完全独立实现。

- **VPN在线不等于DSH已配对**，发现电脑也不等于业务能用。
- Cookie按主机保存；公网地址已配对不会自动让 `.ts.net` 认证成功。`MeshPairing` 可从有效配对链接提取尚有效token，在同一被控DSH的目标origin完成接受；必须核实服务归属，不能向任意新域名发送token。
- 这条跨origin复用是已核对当前插件行为的适配，不是永久的协议保证；上游更改后需重验。
- 切换由 `ConnectionSwitch` 先准备通道、探测目标、成功后持久提交；网络错误最多探测4次，有400/900/1500ms退避；配置/HTTP错误不盲目重复。**入口探测通过不是业务验收通过。**
- 失败/取消要恢复旧通道；成功从VPN切到公网要按需停止App自有VPN，不退出账号或删除配置。
- 返回桌面与最近任务划掉不同：普通后台保留必要连接；明确划掉不应隔一段时间偷偷拉起VPN。`VpnSessionGate` 防止旧回调、独立服务重启重新开会话。
- Android同一用户通常只有一个活跃VPN槽位；内置VPN可能与另一VPN应用互斥，不宣传“对其他VPN绝无影响”。App范围路由不等于全系统出口代理。
- 本机DSH配对设备表已满16。反复签发并接受新配对曾挤掉旧设备；测试优先复用已有记录，不能无限重扫掩盖失效原因。

具体安装命令和两端步骤见 `docs/connections.md` 与App的连接帮助。DSH CLI/profile及插件版本以现场为准，不擅自为修手机升级电脑全部插件。

## 7. 已实现、需要保护的体验

- 首次启动未绑定时展示连接方案；已绑定后连接管理展示当前入口、可切换记录和添加入口。VPN配置在对应二级流程内完成，避免让用户在两个不关联设置页来回找。
- 扫码和粘贴完整链接；重启/刷新沿用有效配对，不把一次性URL当永久主页。
- 启动页保留滚动日志、阶段和资源字节计数；“导航86%”不等于资源字节86%，未知总量不编造百分比；失败保留已收到字节不倒退。
- 顶部刷新/设置图标，设置统一页头和左上返回；关于、通知与后台、缓存、日志等分组。
- 本地常用色调同时覆盖原生壳与网页；默认跟随**DSH页面背景**，不是电脑桌面壁纸，不强制依赖wallpaper插件。
- 缓存只处理允许的同origin静态资源和受约束的带版本插件bundle，不做动态工作流离线回放。当前自管缓存上限96MiB、单文件12MiB；具体规则以`StaticResourceCache`为准。
- 数据清理区分资源、当前页面本地存储、全部Cookie、当前站点、完整站点数据；清资源不顺手退出登录。完整清理依赖现代WebView能力，不能静默退化成未清干净。
- 文件选择处理包括ClipData-only结果；不同机型/提供器的选择、确认、实际上传仍需独立回归。语音依赖服务端插件、权限与网络，不能把有按钮等同于已转录成功。
- 手机、平板、横竖屏/分屏按真实窗口适配；桌面布局有用户开关，窄分屏可紧凑回退，放大后恢复选择。
- 左栏导航到功能页应自动收起；用户菜单是弹层，不能被误当导航导致锚点漂移。
- 通知开关、岛开关与后台行为应真实控制输出，不能只是保存视觉状态。**岛默认关闭**，用户按机型主动启用。
- 通知收起显示最新公开运行步骤；展开上方简短公开说明或结果提示、下方最近3条灰色步骤。只读取公开text，不采思考链、命令正文或tool/result输出。短说明可展示；长文/代码提示去对话查看。已完成结果原策略约4秒收起，尚非持久结果收件箱。
- 运行日志默认启用，可按连接、网络、资源、网页、通知、应用六类控制。内存最多400项，单条2048字符，单份持久段128KiB，导出最多512KiB；不是无限累积。导出前仍需人工复核脱敏。

## 8. 左右栏最新修复细节：不要重新踩坑

### 手机右栏（cf635ea）

1. 现场真实原生点击后 `data-sidebar-right-open=true` 可以驱动open。旧交接“open恒false”只是当时假设，**不要照着假设重写正确判断**。
2. 原来只靠约3秒轮询补0×0几何，插件空宿主还会停在向右平移一整屏的位置，收起按钮移出屏外。现在在状态/节点变化时立即修复。
3. 手机≤900px触摸分支跳过快照点击拦截/合成重放，让插件自己处理开合；关闭整棵右栏display:none，恢复临时样式，避免透明后代吃掉聊天触摸。
4. 内部tabBody原先hidden且自身有固定高度，只改外层overflow并不能滚。需让真正裁剪内容的容器滚动。

### 左栏整体与工作区嵌套滚动

宽屏补救不能自动覆盖手机。现在同样按触摸设备与已识别工作区能力处理：外层菜单整体滚，工作区树自身滚；约45dvh、最小240/最大420px保持工作区可用。内层overscroll允许传递，外层contain，不用JS拦截手势。折叠、结构移除或切布局时恢复原始内联值。

真实触摸证据：手机内层0→83.69、外层0；外层0→99.69、内层不变；内层到底后外层0→99.38。平板内层0→92、外层0；外层0→100、内层不变。手机右栏0→116.31（范围117），关闭后对话约180px；平板右栏0→180。

这些是**正确滚动证据，不是帧率测试**。未做全插件/全负载60fps保证。新增插件结构不匹配时应回退原界面，不能强行抓取所有div套固定几何。

## 9. 待办与已知限制（不要在新对话中丢失）

| 优先级 | 事项 | 已知证据/下一步 |
|---|---|---|
| P0 | 平板熄屏启动前台服务超时退出 | 改名包覆盖安装后，平板Asleep，am start后约30秒出现ForegroundServiceDidNotStartInTimeException，指向DshLiveService；尚未隔离根因，不能断言由改名造成 |
| P0 | 正式包公网进入不稳定，最新两端未完成加载 | 与USB正常分开；核对活动profile、最终URL、资源失败、Socket与业务接口；不靠清数据解决 |
| P1 | 健康探测可能使用旧/公网origin，显示与业务不一致 | 已有业务通但探测75%失败的记录；核对切换后probe来源/代次，401只是探测HTTP结果 |
| P1 | 不同插件组合的侧栏/主题/上传回归 | 已识别结构才修；无右栏/无上下文插件不能留下遮罩和空洞 |
| P1 | 反复切换的用户实际流程 | 已有探测重试/回退/按需VPN清理；不能宣称任意路径任意网络全通过 |
| P2 | WireGuard真实网络测试 | 没有可用服务端；曾提议电脑IPv6但校园网不可达，不继续凭空部署 |
| P2 | 平板经典通知的完整详情展开 | 手机已实测标题与步骤层次；平板仅折叠/分组确认，未完成完整展开验收 |
| P2 | 个别插件接口受限/资源失败 | 曾出现3项；可能是服务端权限/端点兼容，不能自动当成VPN坏了 |
| P2 | 短横屏大键盘空间与重负载侧栏帧率 | 布局已有修复，仍需当前WebView真实操作与性能采样 |
| P2 | MESH地址分类过宽 | 当前普通字母域名可能被保存成MESH；收紧不能误伤WireGuard自定义域名 |
| 发布前 | 公共仓库、B站链接尚未设置 | 关于页仍预留，未获真实地址前不编造链接 |
| 发布前 | 许可、公开历史、CI与干净构建 | 见下一节；不能直接推送含本机运维记录的全部历史 |

## 10. 构建、真机验收与诊断

### Windows本机正常包

```powershell
$env:JAVA_HOME='D:\JDK21'
$env:ANDROID_HOME='D:\AndroidSdk'
$env:GRADLE_USER_HOME='D:\gradle-home'
& 'D:\AndroidSdk\gradle\gradle-8.14.4\bin\gradle.bat' -p 'D:\dsh-mobile' `
  --no-daemon :app:assembleRelease :app:testDebugUnitTest --console=plain
node D:\dsh-mobile\tools\test-web-layout-syntax.mjs
node D:\dsh-mobile\tools\test-web-right-layout.mjs
node D:\dsh-mobile\tools\test-web-drawer.mjs
node D:\dsh-mobile\tools\test-web-viewport.mjs
```

先核对本机SDK/AAR/签名依赖；首次克隆参考`third_party/tailscale-bindings/README.md`与`tools/prepare-tailscale-sdk.ps1`。库生成物不在Git，原生包准备尚未在全新机器完整验收；CI本轮补上Windows准备步骤，仍需线上运行。没有正式签名时release会回退debug密钥；不能直接当最终发布签名。

```powershell
$adb='D:\AndroidSdk\platform-tools\adb.exe'
$serial='<从adb devices -l确认的目标序列号>'
& $adb -s $serial install -r 'D:\dsh-mobile\app\build\outputs\apk\release\app-release.apk'
```

普通debug构建包名为`com.dsh.mobile.debug`；USB开发使用release任务加`-PdshUsbDevelopment=true`，包名仍`com.dsh.mobile`且可调试。别把普通debug当成覆盖正式包。

### USB布局调试

先读完整`tools/start-usb-development.ps1`与代理，确认副作用。它可能构建、覆盖安装、启动本地Node代理、设置ADB reverse，并在缺少已有USB入口时签发配对。设备表满时**不要直接运行默认路径**。现有USB记录应复用；`-AttachOnly`也需要先确认其配对判定，不代表绝对只读。

```powershell
# 仅在已审计并确认可复用已有配对时执行；实际端口从DSH运行实例确认
& D:\dsh-mobile\tools\start-usb-development.ps1 -Serial $serial -LocalPort <实际回环端口>
```

USB开发私有origin为`http://dsh-usb.localhost:18765`，代理监听回环18766。USB配置与正式入口隔离。交付正常包后清理本轮创建的转发/代理，但不误停其他进程、不删认证。

### 最低验收矩阵

- 冷启动、回到前台、明确划掉后再开；两种行为分别检查VPN与页面恢复。
- 当前入口→目标入口→原入口；包括故意失败/取消，确认旧连接仍可用、草稿保留、VPN按需。
- 看到真实工作台、桥接就绪、Socket OPEN，服务端已连接且有在线设备，至少一项真实无副作用业务响应；仅HTTP200、发现设备或加载100%均不足。
- 手机左右栏开→滚→关→再开；关闭后对话可滚；左栏内外分别滑动和边界传递；平板另测宽屏分支。
- 涉及文件/语音的Issue验收到实际结果，不只系统选择器/权限弹窗。
- 涉及通知验证开关、实际任务状态、折叠与展开；小米岛仅支持设备测，不在不支持的平板伪造岛验收。

### JS/CDP常见坑

- Kotlin三引号内含JS模板字符串：**模板字符串的注释里也不能随意写反引号**，否则整段注入SyntaxError，表现像USB/连接/左右栏同时坏。
- 先跑`test-web-layout-syntax.mjs`；真机检查`adb -s <serial> logcat -d -s DshRun/Console:E`。不要只看Kotlin编译通过。
- USB开发包可用`/proc/net/unix`找`webview_devtools_remote_<pid>`，用ADB forward转发至本机，再访问`/json`和CDP。量computed overflow、几何、scrollHeight/clientHeight、开合属性与elementFromPoint；需要时用CSS.getMatchedStylesForNode查规则是否生效。
- 触摸成功证据要用可信触摸手势，不能用`element.click()`或直接赋scrollTop冒充用户操作。设置起点可以，之后必须测真实手势结果。
- `adb shell input text`可能被中文输入法改写域名，使用产品的粘贴链接流程。
- 截图先设备内`screencap -p /sdcard/<file>.png`再pull；PowerShell直接重定向exec-out可能损坏PNG。
- 内嵌Tailscale是App范围路由：系统shell ping电脑Tailscale IP失败不能证明App内隧道坏。ICMP丢包也不是HTTP/WebSocket失败率的同义词。
- 正常release不可run-as/CDP；不要为读取状态去清数据。导出产品日志或使用已获准的开发版。

## 11. Issue信息与修复交付格式

最少需要：App版本/提交、Android/ROM/WebView版本、手机或平板与窗口方向、连接类型、复现步骤、期待/实际、是否切换过入口、发生时任务是否运行、脱敏日志与录屏。有无相关插件也要写，不能要求用户提供token/Cookie/私钥。

新维护者的交付应包含：根因与证据、改动范围、真实用户操作验收、测试命令与结果、未覆盖场景、提交号与回退位置。Issue未复现或无真机不能写“已全部修好”。

## 12. 发布、素材和历史保护

- 代码Apache-2.0；鲸鱼娘素材沿用仓库`THIRD_PARTY_LICENSES.md`所记的**CC BY-NC-SA 4.0**与署名；MiSans、DSHgo参考、Tailscale/Go、WireGuard各自许可保留。不要把整个包笼统称为“全部Apache”或“素材任意商用”。
- 当前本地`.ai`记录和历史提交含真实运维信息，`.gitignore`不清理历史。公开前审计当前文件、Git历史、截图、日志、APK配置与下载附件；**本手册更新不等于整个仓库已完成脱敏**。
- `tools/scan_release.py`已改为接收目录/ZIP的只读规则扫描；不打印命中值，不修改/解压输入；规则无命中仍不等于完整隐私审计。本轮CI已改为Windows并增加原生库准备、JS检查和单测；尚未在GitHub线上运行验收。
- 本轮不发布GitHub、不重写旧历史、不替换生产签名；新公开仓库的导入策略须另做可审查方案，保留本地完整开发历史备份。
- `.ai/HANDOFF.md`用于最新工作状态，DECISIONS防重踩，WORKLOG保留历史；本文是压缩后的单文件入口。历史文档中的旧结论必须结合日期阅读。

## 13. 关键历史节点（不是要求回滚）

| 提交 | 意义 |
|---|---|
| `842b06c` | ClipData-only附件与多级预测性返回 |
| `7dab3f7` | 平板窄分屏紧凑回退与恢复桌面偏好 |
| `0726f0a` | 通知灰色步骤/展开说明 |
| `1c04fca` | VPN目标配对token跨origin适配 |
| `9576e1c` | 静态资源加载字节显示 |
| `e3e4b8f` | 连接管理、Relay推荐、教程和平板滚动 |
| `b3144b8` | 灵动岛默认关闭 |
| `81df34c` | DSH专项交接及当时左右栏改动 |
| `cf635ea` | 手机右栏原生开合、手机/平板两级左栏滚动 |

修新Issue时沿当前分支继续，不重新实现这些已验证功能；需要变更既有行为时说明理由和回归范围。
