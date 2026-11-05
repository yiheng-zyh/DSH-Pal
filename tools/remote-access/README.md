# 国内 IPv4 远程通道（Windows 开发预览）

这套工具为每位用户自己的 DSH 创建国内备用入口。手机通过 HTTPS 连接 Sakura Frp 国内 TCP 节点，官方 frpc 在电脑端处理 TLS，再经只监听 `127.0.0.1` 的转发器连接 DSH 的动态本地端口。无需公网 IPv4、IPv6 或自购域名；不修改原 Relay、Cloudflare、VPN 或其他启动任务。

2026-10-31 的实测覆盖 Windows x64、远程插件 `@linxin666/dsh-remote-web-ui` 0.4.11、Android 16 手机及联通移动数据。正式 App 完成首次资源下载、进入工作区、读取现有会话和文件差异。其他运营商、Android 17、不同插件版本及长期在线仍需分别验收。它绕开原 Relay 的 503，不能修复第三方 Relay 服务端的存储故障。

## 准备

> **[需要用户介入]** 注册 Sakura 账号并实名认证
> AI 应说：「这一步需要你本人注册并实名（我无法代做），而且要付 1 元钱、用支付宝刷脸。完整步骤见下方『注册到拿到密钥的完整路径』，做完把访问密钥存好（别发聊天里），告诉我一声。」

- Windows x64，Node.js 22 或更新版本，DSH 桌面端及远程插件已运行。
- 每位用户自行注册 [Sakura Frp](https://www.natfrp.com/)、按其要求实名认证，并取得自己的访问密钥。不要共享开发者账号。
- 免费账号的隧道数、流量、限速及节点可用性以供应商面板为准。流量耗尽或节点维护会中断连接；免费服务不提供本项目可承诺的可用性保证。

### 注册到拿到密钥的完整路径（照着做，约 10 分钟）

> 这段是**给用户本人看的**。AI 做不了，但必须把它原样交给用户，别只说「你去注册一下」。
> 官方依据：[实名认证 FAQ](https://doc.natfrp.com/faq/realname.html)

**第 1 步：注册账号**

1. 打开 <https://www.natfrp.com/>，点右上角「注册」
2. 用**邮箱**注册（QQ 邮箱、163 都行），设置密码
3. 去邮箱点验证链接完成激活
4. 登录后进入「管理面板」

**第 2 步：实名认证（必须做，注意要花钱）**

> ⚠️ **三个必须提前知道的事实**（不然会卡住）：
> - **要付 1 元**。这是第三方实人认证接口（支付宝/旷视）的调用费，Sakura 官方文档明确说明他们无收入。
> - **必须用支付宝 APP 刷脸**。没有支付宝就**无法完成认证**，只能退款。
> - **没实名就建不了隧道**，这条路走不通。

具体操作：

1. 打开 <https://www.natfrp.com/user/realname>
2. 点「购买实名认证资格」，付 **1 元**（支持支付宝）
3. **付款后刷新页面**，输入真实姓名 + 身份证号，点「确认提交」
4. 系统弹出二维码 → 用**支付宝 APP 扫码** → 按提示**刷脸 + 拍身份证**
5. 完成。此步骤**不需要额外付费**（1 元已在第 2 步付过）

> **认证失败怎么办**：
> - 提示「对比源不清晰」/ 没有支付宝 → 官方建议注册个支付宝再来；实在不行去 <https://www.natfrp.com/purchase/list> **申请退款**（认证完成前 7 天无理由）
> - 没有手机号 → 暂不支持，只能退款

**第 3 步：拿到访问密钥**

1. 登录后打开 <https://www.natfrp.com/user/>
2. 在用户信息区找到 **「访问密钥」**（一串字母数字，类似 `xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx`）
3. 点「复制」

> **密钥的性质**：它等同你的账号密码，**能操作你名下所有隧道**。
> 不要发到群里、不要写进 Issue、不要贴进聊天记录。

**第 4 步：把密钥存到本机**

打开**仓库根目录**的 PowerShell（就是能看到 `tools` 文件夹的那一层），依次执行：

```powershell
Set-Location tools/remote-access
npm ci --ignore-scripts --no-audit --no-fund    # 第一次要装依赖，约 1 秒
.\save-sakura-token.cmd
```

上一步会**弹出一个窗口**，把第 3 步复制的密钥粘贴进去、回车保存。

保存位置：`%LOCALAPPDATA%\DSHPal\remote-access\sakura-token.dpapi`，用 Windows DPAPI 加密，**只有当前 Windows 用户能解开**。

> **为什么用弹窗而不是命令行传参**：命令行参数会留在 PowerShell 历史记录里，弹窗不会。
> 如果你已经把密钥发给过 AI，它会留在对话记录里 —— 不方便撤回的话，用完建议去面板**重置密钥**。
>
> **报错怎么办**：
> - `无法加载文件…因为在此系统上禁止运行脚本` → 这是 PowerShell 执行策略限制。用 `powershell -ExecutionPolicy Bypass -File .\secure-store.ps1` 代替，或直接**双击** `save-sakura-token.cmd`（在文件资源管理器里进到该目录双击，不经过 PowerShell）。
> - `npm : 无法将"npm"项识别为…` → 没装 Node.js，去 <https://nodejs.org/> 装 LTS 版本（要 22 或更新），装完**重开一个** PowerShell 窗口。
> - 弹窗一闪而过 → 说明出错了。在该目录按 Shift+右键 → 「在此处打开 PowerShell 窗口」→ 输入 `.\save-sakura-token.cmd` 看完整报错。

**第 5 步：确认密钥真的存好了**

```powershell
node ../deploy-status.mjs
```

看输出里 `Sakura 凭据` 那一行是不是 ✅。如果是 ❌，说明第 4 步没成功，重做。

**随时检查配到哪一步了**（只读，不修改任何东西）：

```powershell
node ../deploy-status.mjs           # 人看的摘要
node ../deploy-status.mjs --json    # 供 AI / 脚本解析
node ../deploy-status.mjs --share   # 脱敏版，可贴进公开 Issue
```

它输出 `todo` 数组，直接说明还差什么、是 AI 能做还是要用户本人做。

列出当前可用的免费国内节点：

```powershell
node --input-type=module -e "import {loadSakuraToken} from './windows.mjs'; import {sakuraRequest,freeMainlandNodes} from './sakura.mjs'; console.table(freeMainlandNodes(await sakuraRequest(await loadSakuraToken(),'/nodes')).map(({id,name})=>({id,name})));"
```

选一个节点 ID。下面的 `123` 是占位值，请替换成上一步的真实 ID：

```powershell
node setup-sakura.mjs 123
node configure.mjs
node run.mjs
```

`setup-sakura` 只创建一个名为 `DSHPal_remote_v1` 的 TCP 隧道及免费的随机子域绑定，目标固定为 `127.0.0.1:18772`，启用官方自动 HTTPS。重复运行复用同一资源；发现同名隧道配置不同或域名记录属于别的隧道即停止，不删除、覆盖其他资源，也不自动购买套餐。供应商报错不会自动重放创建请求；请先到面板确认结果。

`configure` 自动下载官方 Windows x64 frpc，核对官方元数据中的大小和 MD5，并保存本地 SHA-256，启动前再次比对。MD5 只能检查下载损坏，不能当成数字签名。客户端独立下载，不随本项目发布，也不在运行时自动更新。已有配置会继续固定原版本。

默认 DSH 配置位置由当前用户目录推导；自定义 profile 请显式传绝对路径：

```powershell
node configure.mjs '<自己的 profile 目录>\cordis.patch.yml'
```

工具先验证恰好一个远程插件，仅添加带本工具注释的 `trustedHosts`。修改前用 DPAPI 加密备份整个配置，保留其他插件、账号和用户条目。配置热重载时会短暂断开；运行器每分钟核对自己的信任条目，被更新覆盖后补回。它不启动 DSH，不同步 profile，不修改依赖。

## 扫码及日常使用

日常使用请按下面“接入现有插件的正式入口”完成一次电脑配置，然后直接打开 DSH 插件原有的「远程访问 → 设备配对」面板并刷新二维码。手机首次启动点击“扫码连接”，自动识别实际通道，不需要先选 Relay 或填写节点。首次按系统提示允许相机；通知授权与联网认证分别处理。

手机使用者不需要注册 Sakura、安装 frpc 或填写访问密钥，这些只在电脑部署时配置。清除手机 App 数据后需要重新扫码配对；日常 Wi-Fi 与移动数据切换保持同一国内地址及 Cookie，不要求重新扫码。电脑 DSH 和国内通道须运行，二维码须在有效期内，网络须能访问节点；不能据短期测试保证每个运营商和节点都可达。

`show-pair.cmd` **仅用于独立通道调试**，不是日常操作必需项。它通过真正的本地控制接口签发配对，在本机临时窗口内存生成二维码，不复制旧域名 Cookie，也不关闭配对鉴权。生成新码会使前一个未使用的配对码失效，已配对设备继续保留；设备表满时遵循插件原有的最旧设备淘汰规则。

首次证书签发可能需要几分钟。必须用系统信任的有效 HTTPS 证书连接；本工具不绕过证书校验。官方自动 HTTPS 会自行管理证书，并可能在本机运行目录生成证书文件；整个本地运行目录都是私有资料，不能打包发布。

确认手机能够显示工作区和读取现有会话后，可在另一个 PowerShell 窗口安装当前用户登录启动任务：

```powershell
powershell -NoProfile -File .\install-autostart.ps1 -DryRun
powershell -NoProfile -File .\install-autostart.ps1
```

先关闭手动 `node run.mjs` 窗口，再启动任务：

```powershell
Start-ScheduledTask -TaskName DSH-Pal-Remote-Access
```

任务以当前用户普通权限运行，不要求管理员；路径变动后请先检查原任务，工具不会覆盖指向其他位置的同名任务。它不限制笔记本电池运行时长。转发器绑定端口保证重复启动先退出，不会重复启动隧道；frpc 异常退出按 4～60 秒退避重启，DSH 改变动态端口后自动重新发现，仅检查 DSH 进程拥有的监听端口。退出运行器时官方客户端通过父进程监视参数退出。

启动时把上一次成功端口作为提示，重新验证配对协议后才启动公网隧道，不会直接信任旧端口。后端暂不可用时，网关仍返回真实 503；对于无查询参数的 `/`、`/pair-app` 普通 GET 页面，显示等待页并检查恢复，收到有效配对状态后转到不含令牌的 `/pair-app`。每页最多检查12次，短时间最多自动导航3次；不会重放 `pair`、`grant` 链接或任何写请求。隔离 Edge 浏览器已验证“503 → 后端恢复 → 自动返回页面”；该测试不代表完整电脑重启验收。

停止本工具时使用任务的停止入口；停用登录自启可执行：

```powershell
Disable-ScheduledTask -TaskName DSH-Pal-Remote-Access
Stop-ScheduledTask -TaskName DSH-Pal-Remote-Access
```

这些命令不停止 DSH、VPN 或旧 Relay，也不删除供应商隧道、配对记录或本地凭据。

启动器使用 Windows Job Object 管理自己的进程树；停止任务会连同其 Node、凭据子进程与 frpc 一起退出，避免 Windows 只结束外层启动器、留下隐形后台进程。创建子进程时先挂起、加入该进程组再恢复，不按进程名批量结束其他服务。真实停止及重新启动已验收。

## 验证与边界

### 接入现有插件的正式入口

独立通道验收通过后，可以用现有插件已支持的自定义公网入口替换其默认海外中继：

```powershell
node integrate-plugin.mjs --dry-run
node integrate-plugin.mjs --apply
```

> **[需要用户确认]** `--apply` 会改插件的公网优先级（关闭 Relay 与 Cloudflare 自动隧道）。
> AI 应先把 `--dry-run` 的输出给用户看，说明会影响哪些字段，**得到同意后**再 `--apply`。
> 只在你确实要把国内通道作为主入口时才做；只想多一条备用入口而保留 Relay 为默认时，不要执行这一步。

这一步会先通过有效 HTTPS 校验真实配对接口，再加密备份所选 profile 的 `settings.yaml` 与 `cordis.patch.yml`（**存在哪个就备份哪个**）。它只修改远程插件的 `publicBaseUrl`、`autoTunnel`、`relay`、`pairingSource`、`tunnelToken`：发布自己的国内入口，关闭插件自带的 Cloudflare 自动隧道和 Relay，清空当前命名隧道令牌以避免优先级抢占，并保留自动选择 LAN 的能力。旧配置只保存在本机加密备份中，不打印凭据。设备表、其他插件和桌面服务保持不动。

> **兼容性说明**：早期版本硬编码读取 `<profile>/settings.yaml`，但 **DSH 0.2.1-rc.5 及之后**的 profile 里可能只有 `cordis.patch.yml`，没有 `settings.yaml`，会导致这一步 `ENOENT` 失败。
> 现已改为**文件存在才处理**：缺 `settings.yaml` 就跳过它，直接改 `cordis.patch.yml`，并在输出里注明跳过了什么。
> 如果你用的是修复前的版本且卡在这里，升级到当前版本重试；或手动把 `publicBaseUrl` / `autoTunnel` / `relay` 改到 `cordis.patch.yml` 的对应插件条目里。

生效后应核验本地 `/api/pair/status` 的 `publicUrl` 及插件原生 `/api/pair/issue` 生成链接的 origin 都等于国内入口，再用手机重新打开实际工作区。插件的 Relay 关闭时可以省略 `relay` 状态字段，发现器支持这种返回。切换前的配对链接需要重新生成；国内入口上已有的合法设备会话继续有效。

这是使用原插件现有配置的集成，国内隧道进程仍由本工具的登录任务管理；它没有修改已安装插件的供应商代码，也没有在原插件里新增一个 Sakura 专用控制面板。将来插件增加原生服务商支持时，应使用其正式接口迁移。

`state.json`（位于 `%LOCALAPPDATA%\DSHPal\remote-access\`，不在仓库里）只记录目标可用性、运行状态和汇总计数，不保存请求内容、Cookie、配对链接或原始 frpc 日志。`clientState` 取值 `running-unverified` 表示进程运行；`businessVerified` 恒为 false，工具无法仅凭端口或 HTTP 200 自动证明手机已经可用。判断网关存活看 `updatedAt` 是否在 30 秒内。

验收必须看手机：首次完整下载、真实工作区、实时服务连接、读取既有会话，随后验证退出重开与前后台返回。USB 只用于调试命令，不代表公网验收。不要通过发模型消息制造付费测试。

HTTP、压缩资源、SSE 和 WebSocket 原样流式转发，没有 32 MiB 资源截断或额外缓存层。公网请求保留原 Host、Origin、鉴权数据；明确标记为转发请求，不能冒充本地桌面控制端。管理 API 在转发器入口拒绝，业务访问仍由 DSH 插件自身的配对规则决定。服务原本的 401、403、503 会如实返回，不自动重试用户写操作。

`provider: "external"` 可以只运行同一个网关：配置中填写自己已有的有效 HTTPS origin、gatewayPort，并让自己的隧道转发到该 loopback 端口。外部服务、TLS 和启动恢复由用户自己的运维方案负责，不能视为已通过本项目的 Sakura 实测。

## 本地检查及公开发行

### 浏览器监看插件的流量保护

`dsh-ego-browser` 的 `/api/ego/stream` 同时包含工具通知、状态和 JPEG 画面。某些版本的后台工具监听器也会订阅完整画面流，即使没有打开监看面板。2026-10-31 现场统计曾在几十秒内记录约 30 MB，通道累计超过 1 GB；这不是一次性网页资源下载。

配套 App 对该接口延迟到监听器注册后再建立连接：没有 `frame` 监听器时添加 `dsh_events_only=1`，本地网关在公网传输前过滤 `frame` 事件，保留其他事件字节。实际画面消费者仍请求完整流；其他 SSE、任务 WebSocket、壁纸和用户插件配置不变。此保护需要新版 App 与本工具共同部署；旧 App、普通浏览器和其他通道不会自动获得这项过滤。主动开启实时画面依然持续消耗流量，不能把视频传输当成免费后台功能。

网关提供有上限的资源类别字节统计，不记录查询串和用户正文。`responseBytes` 是网关向外发送的 HTTP 响应体累计值，非手机上行或供应商计费账单，也不包含所有 TLS/WebSocket 开销。大壁纸静态帧也可能达到数 MB，首次加载与持续视频需要分开判断。

```powershell
npm test
```

可选的本地真实浏览器恢复检查（Windows 上需已安装 Edge，参数为已有的绝对产物目录）：

```powershell
node verify-recovery-browser.mjs '<自己的验证产物目录>'
```

检查使用独立浏览器 profile 和只监听回环的模拟服务，不访问真实 DSH，不使用已有浏览器账号；保留隔离 profile 与 JSON 结果供排查。

依赖由锁文件固定。发布 Git 跟踪的源码和文档；禁止附带 `node_modules`、供应商客户端、本地配置、DPAPI 文件、证书、设备截图及任何个人入口。每个复刻者使用自己的账号、节点、域名、运行目录和配对二维码。

官方依据：[开放 API](https://github.com/natfrp/api)、[子域与自动证书](https://doc.natfrp.com/bestpractice/domain-bind.html)、[frpc 手册](https://doc.natfrp.com/frpc/manual.html)。
