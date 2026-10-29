# 连接方式与两端配置

所有方式都连接同一套 DSH。App 保存各入口及本地选择，内置 Tailscale / WireGuard 仅为本 App 建立 VPN，自动发现仅检查已授权设备；不修改 DSH 服务、普通 LAN 绑定或防火墙。DSH 桌面 webserver 保持 `127.0.0.1` 与自动端口 `0`；**所有方案均不需要修改 `~/.dsh/profiles/desktop/cordis.patch.yml` 的 webserver host/port**。

## 先选入口

| 配对页入口 | 适合谁 | 优点 | 代价与限制 |
|---|---|---|---|
| Relay 固定域名中继（当前引导推荐） | 使用 DSH 面板原固定域名二维码的用户 | 地址通常固定，复用原扫码配对 | 多一跳中继，质量需单独判断 |
| Cloudflare Tunnel | 手机流量、不同 Wi-Fi | 不经过 DSH Relay 中转；手机无需 VPN | Quick 域名可能变化，Named 需自有域名部署 |
| 其他 HTTPS / frp | 已有自有反向代理入口 | 按既有部署连接 | 需管理员配置 HTTPS、WebSocket 和认证 |
| Tailscale / WireGuard | 两端已经部署这些工具 | 可限定在自己的网络内 | 需两端客户端、路由和访问规则；可能中继 |
| Wi-Fi 直连 | 手机与电脑在同一 Wi-Fi，已有 LAN 转发入口 | 路径较短，不依赖公网 | 需要现成入口；隔离、防火墙、IP 变化会影响 |
| USB 数据线 | 开发与 UI 适配 | 资源走数据线，便于调试 | 需要 ADB 与 USB 开发构建，拔线不可用 |

这里不保证固定延迟或价格。它们取决于线路、设备、服务商和网络政策。

## 电脑先装什么，再用手机连接

这几条路径**共用 DSH 插件 `@linxin666/dsh-remote-web-ui`**，不需要为 Tailscale、WireGuard、Wi-Fi 另找同名 DSH 插件。插件负责远程界面、授权和二维码；配套客户端或转发工具负责把地址连通。安装在**被控端电脑**，手机不执行 DSH 插件安装命令。

| 选择的路径 | 被控端 DSH 插件 | 额外准备 | 配好后手机选什么 |
|---|---|---|---|
| Cloudflare Quick | `@linxin666/dsh-remote-web-ui` | 插件已有的自动公网隧道，cloudflared 由插件准备 | Cloudflare Tunnel |
| Relay | 同上 | 插件已有的 Relay 固定域名中继 | Relay 固定域名中继 |
| Cloudflare Named | 同上 | 已配置的 Cloudflare 账号、域名与 Named Tunnel | Cloudflare Tunnel |
| Tailscale | 同上 | 电脑官方 Tailscale + 手机内置通道；电脑用 Serve 转发 DSH | Tailscale / WireGuard |
| WireGuard | 同上 | 电脑 WireGuard + 手机内置通道、可用配置，以及到 DSH 回环服务的转发入口 | Tailscale / WireGuard |
| Wi-Fi 直连 | 同上 | 同一 Wi-Fi 和已配置的独立 LAN 转发入口 | Wi-Fi 直连 |
| USB 数据线 | 同上 | Android SDK Platform Tools（ADB）、项目 USB 助手与 USB 开发版 | USB 数据线 |
| frp / 其他 HTTPS 反代 | 同上 | frpc / frps 或其他代理、HTTPS 域名与 WebSocket 转发 | 其他 HTTPS / frp |

### 第一步：确认你正在控制哪个 DSH

- 平时打开 **DSH 桌面客户端**：选 `desktop`。
- 平时运行 **`dsh web`**：选 `web`。
- 使用自定义 profile：将命令里的 `desktop` / `web` 改成自己的名称。

**两条命令按实际情况只选一条**。不要把桌面端插件装到 web profile 后，期待桌面端出现入口。

桌面客户端用户，在电脑终端执行：

```powershell
dsh plugin --profile desktop add @linxin666/dsh-remote-web-ui@latest
```

`dsh web` 用户，在电脑终端执行：

```powershell
dsh plugin --profile web add @linxin666/dsh-remote-web-ui@latest
```

这是尚未安装插件时的命令。**已有远程访问入口就跳过安装**，直接配置；不要为了切换连接方式重复安装或升级。安装期间若 DSH 提示需要重新加载/重启，先保存任务，按提示操作，不必结束所有 DSH 实例。

如果提示“找不到 dsh”：先确认电脑已安装 DSH CLI、终端能识别 `dsh`，安装 CLI 后重新打开终端。只有桌面客户端而没有 CLI 时，可在该客户端的插件管理中按完整包名查找并安装；不要随便下载名称近似的包，也不要用修改 webserver 绑定来解决安装问题。

### 第二步：确认插件已在正确的 DSH 中启用

回到刚才选择的 DSH，确认插件已启用，并出现 **远程访问**（手机图标）入口。打开面板后再做下方所选方案的配置。找不到入口时，先检查 profile、插件启用状态和安装报错，手机暂时不用反复扫码。

### 第三步：配置通道，然后获取配对码

按下面相应方案配置 → 用手机浏览器检查入口 → 在电脑 DSH 的“远程访问”面板获取配对二维码 → 手机 App 选择对应入口完成配对。不同主机的 Cookie 不自动共享；不要手工把链接改成不明主机。当前 App 的 Tailscale 流程支持在确认属于同一被控 DSH 的目标上复用尚有效的配对 token 完成该目标的认证，具体由 App 处理，不等于任意地址都能直接改写。

多通道共存时，优先复用已经运行和配对的入口；确需首次认证时再使用有效配对链接，避免在设备数量达到上限时反复签发/接受新配对挤掉旧设备。不为切换关闭 `autoTunnel`、Relay 或修改 `publicBaseUrl`。如果部署全新自有入口确实需要调整插件的主机信任或公布地址，由管理员先备份并确认对其他设备的影响；不能只看 App 有记录就认定入口有效。DSH webserver 绑定不应改动。

插件安装语法依据：[插件作者的安装说明](https://github.com/ShiroEirin/dsh-web-ui/blob/main/packages/dsh-remote-web-ui/README.zh.md)。

## 配对与切换共同规则

1. 配对页选择连接方式，扫码或输入该方式对应的完整配对链接，可选填一个名称。
2. Wi-Fi / Tailscale 地址没有协议前缀时补 `http://`；HTTPS 名称必须保留 `https://`。
3. 设置 → 连接管理：统一选择连接方式。Tailscale、WireGuard 各自进入专属二级页面，在同页完成账号/配置、授权、选择电脑与“连接并打开 DSH”。不再分别操作 VPN 设置和入口切换两个页面。切换有未发送内容时先提醒。
4. 新地址可能需要首次配对。App 保留 WebView 的认证状态；切回已配对地址不主动清 Cookie，但服务端撤销、到期、域名变化仍可能要求重新配对。
5. Cookie 的域规则不是按“电脑”通用共享：不同主机名不能假设复用；同一主机的不同端口也不能视为完全独立的 Cookie 隔离。当前 Tailscale 流程可按同一被控 DSH 的有效 token 重新接受配对，仍是服务端正常认证，不是共享 Cookie 或绕过授权。不要向不明主机发送配对链接或 token。
6. 加载失败时的“重新配对”进入添加入口，不删除其他连接；只有设置里明确确认解除全部配对才清除记录。
7. 普通返回桌面、通知点击不切换连接。USB 开发构建与正式构建的连接列表、当前项及一次性链接分别保存。
8. 普通返回桌面保留 VPN；明确从最近任务中划掉 DSH 会停止内置通道并禁止后台自动拉起。重新打开 App 后，所选组网入口可再次连接，账号、配置和配对记录保留。服务单独被系统重启时不能创建新的 VPN 会话；若后台进程被整个结束，重新打开 App 才恢复 VPN。

## Cloudflare Tunnel（推荐）

此入口表示直接使用 Cloudflare 隧道，与下面的 Relay 分开。两者仍共用同一个 DSH 远控插件、配对协议和界面，不需要重复安装插件。App 中的类型拆分不会修改电脑默认公布地址的优先级。

### 电脑与手机步骤

1. 已有远程访问插件时直接复用；缺少时按前文匹配 profile 的安装命令安装，不为手机切换重复升级 DSH。
2. 在电脑的远程访问面板确认自动隧道正在运行，记录它当前的 Tunnel 地址。Quick 通常以 `trycloudflare.com` 结尾，重启可能变化；Named 使用已经部署的自有域名。
3. 面板默认二维码可能仍是 Relay。不要认为“同一个插件生成的二维码”就一定代表 Tunnel。使用下方回环签发步骤，或让电脑 AI 核实 Tunnel 归属后生成它的新配对链接。
4. 手机打开 **设置 → 连接管理 → 添加连接 → Cloudflare Tunnel**，扫码或粘贴对应链接，保存并连接。
5. 检查最终页面顶部仍为 Cloudflare Tunnel，并确认配对、静态资源、业务接口及实时连接。进入欢迎页不代表所有插件权限都已开放。

Quick 地址变化后，新域名需要首次配对；旧域名记录不会被自动覆盖。Named 自有域名不能靠名字自动识别，用户明确选择 Cloudflare Tunnel 后会保留这一类型，重启或深链返回不会把它改成其他 HTTPS。

## Relay 固定域名中继（原插件常见扫码入口）

你之前使用的电脑面板默认二维码，若地址为 `*.dsh-market.com`，实际就是 Relay。它和 Cloudflare Tunnel 来自同一个插件，但访问链路不同：Relay 额外经过中转，地址通常固定。固定地址不保证每个网络下都更快。

1. 复用电脑现有远控插件与运行中的 Relay，不为手机切换修改插件优先级、停止 Tunnel、改 LAN 绑定或改 webserver。
2. 在手机选择 **Relay 固定域名中继**，扫描面板为该 Relay 地址生成的二维码，或粘贴新配对链接。
3. 旧版混合记录会按已知域名归到 Relay；原地址、ID、使用时间、Cookie 和自定义名称保留。无需为了改分类重新配对。
4. 与 Tunnel 来回切换时只选各自的已保存记录。没有复制 Cookie、替换保存地址或将 Relay 的探测结果当成 Tunnel 的结果。

入口不可达仍按原逻辑最多重试四次；失败保留原连接。日志要注明使用的是 Relay，分别说明入口、资源下载和实时连接的情况。

## 其他 HTTPS / frp

用于已有 frp、HTTPS 反向代理，以及无法从域名确认服务商的历史公网地址。域名在 Cloudflare 托管不代表远控链路就是 Cloudflare Tunnel。

管理员需要提供真正部署好的 HTTPS 地址，转发 HTTP 和 WebSocket 到 DSH 当前回环服务，并保留配对校验。手机选择此项后使用对应配对链接。App 不自动创建服务器、不改防火墙，不假定 DSH 固定监听示例端口。

历史未知域名会保守地归入本项，地址和凭据不变。若部署者确认该域名属于 Named Tunnel 或自建 Relay，可在对应专属页面保存同一地址来纠正类型；该地址的身份 ID 和 Cookie 不变。

## 多手机、多入口的配对容量

同一物理手机在不同域名首次配对，可能会在 DSH 远控插件中分别占用设备记录。当前适配插件的默认 `maxDevices` 为 4；达到上限时，新配对会淘汰最旧记录。因此“添加第二台手机或新入口后旧入口失效”不能一概归为网络或手机切换错误。

电脑管理员可在 DSH 的远控插件设置卡中找到“设备数量上限”（`maxDevices`），按手机数量、入口数量和其他浏览器预留容量。例如两台手机各保存三个域名入口，至少需要六条会话，默认四条不足；可按自己的情况设置为 16 并保留余量。该字段支持即时更新，无须为了它重启整个 DSH。已被淘汰的凭据不能靠重载恢复，需要容量准备好后补一次配对。App 不自动增加此安全上限、不合并或撤销其他设备、不把凭据复制给陌生域名。排查时先核对设备记录和上限，避免反复扫码持续挤掉旧入口。
## Tailscale 登录超时、退出后如何重新登录

在“连接管理 → Tailscale / WireGuard”里，“读取登录状态”只用于检查账号状态，不会打开浏览器。未登录或授权页面超时，请点“登录 / 重新打开授权页”，App 会重新请求官方授权页面；不需要清数据、卸载或删除 DSH 配对记录。

完成官方账号和设备授权后返回 App，再选择电脑或点“连接并打开 DSH”。退出账号会取消本页旧的连接等待；重新登录与刷新状态是两个明确操作，不复用旧授权页去反复重试。登录浏览器中的网络超时仍需检查到 Tailscale 官方服务的访问，不代表 DSH 配对失败。
## 为已运行的 Tunnel / Relay 取得正确配对链接

下面只读取**当前 DSH 回环状态**并签发新令牌，选择该服务自己报告的现有 Tunnel 或 Relay 地址。它不改插件配置，也不是盲目更换历史二维码域名。适用于插件已经报告了对应公网地址的情况；自有入口未报告时请由部署者先核实服务归属，不随便代入别的域名。

```powershell
$dshPort = [int](Read-Host '输入当前 DSH 的实际回环端口')
if ($dshPort -lt 1 -or $dshPort -gt 65535) { throw '端口无效' }
$route = Read-Host '选择 Tunnel 或 Relay'
if ($route -notin @('Tunnel', 'Relay')) { throw '请选择 Tunnel 或 Relay' }
$status = Invoke-RestMethod "http://127.0.0.1:$dshPort/api/pair/status"
$offered = if ($route -eq 'Tunnel') { $status.tunnel.url } else { $status.relay.url }
if (-not $status.ok -or -not $offered) { throw '该 DSH 没有报告所选入口，请检查其运行状态' }
$routeUri = [uri]$offered
if ($routeUri.Scheme -ne 'https' -or $routeUri.UserInfo) { throw '入口不是有效 HTTPS 地址' }
$pair = Invoke-RestMethod "http://127.0.0.1:$dshPort/api/pair/issue" `
    -Method Post -ContentType 'application/json' -Body '{}'
$issuedUri = [uri]$pair.url
if ($issuedUri.AbsolutePath -ne '/pair-accept') { throw '配对接口返回格式不符' }
$routeLink = $routeUri.GetLeftPart([UriPartial]::Authority) + $issuedUri.PathAndQuery
$routeLink | Set-Clipboard
Write-Host '所选入口的新配对链接已复制，请在手机对应页面粘贴，勿发布到 Issue。'
```

端口应从本次 DSH 启动日志、电脑本机地址或进程监听确认，不固定假定 3080。公网 `/api/pair/issue` 的 403 是安全设计，不关闭认证。刷新令牌可能使旧的待扫描码失效，不循环签发。

## 扫码识别与切换保护

- 扫码是配对方式，二维码里的地址才决定通道。已知 `trycloudflare.com`、`dsh-market.com`、`ts.net` 子域分别识别为 Tunnel、Relay、Tailscale；相似拼写或后缀伪装不会命中。
- 所选页面与实际已知地址不一致时，App 会切换到识别出的类型并提示确认，尚不替换当前连接。未知自有域名按用户明确选择保存，不猜后端。
- 配对协议的服务器可能将新鲜一次性 grant 回跳到默认公布地址。仅对已知服务的该类协议重定向，App 让 grant 留在用户原先选择的入口继续验证，避免选了 Tunnel 又悄悄回到 Relay。普通链接、OAuth、旧配对令牌不会这样改写。
- 每个地址独立保留配对状态。探测成功后才提交切换，失败保留原选择，并恢复原连接需要的内置 VPN；探测非组网入口时先关闭本 App 的内置 VPN，确保实际测试的是目标路径。
- 自有代理、WG 和 LAN 没有实际测试端点时，不能仅凭共用代码就宣称实网验收通过。

## Tailscale

### 电脑端

1. 先按“第一步”安装公共远程访问插件；Tailscale 不替代它。再从 [Tailscale Windows 官方页面](https://tailscale.com/docs/install/windows) 安装客户端并登录，两端加入自己的同一 tailnet。
2. 在 DSH 运行后确认真实回环端口，再运行：

```powershell
tailscale serve --bg http://127.0.0.1:<DSH实际端口>
tailscale serve status
```

3. 按官方提示启用 HTTPS，记录显示的 `https://<设备>.<网络>.ts.net` 名称。Serve 代理回环服务，不要求把 DSH 改绑到 Tailscale IP。
4. 备份远程插件原配置，将 Serve 域名（不带协议）加入 `trustedHosts`。要与 Cloudflare / Relay 共存，保持原来的 `autoTunnel: true`、`relay: true`；Serve 独立转发到同一个 DSH 回环端口。不要为启用 Tailscale 而停掉已有公网入口。只有明确只需要 Tailscale 时才关闭自动公网通道并将 `publicBaseUrl` 设置为 Serve 地址。这些都是远控插件配置，不能修改 `webserver` 的 host/port。
5. 每个入口首次使用都要完成 DSH 配对；配对凭据按域名隔离，不能自动复制给另一个域名。签发只使用电脑 `http://127.0.0.1:<实际端口>/api/pair/issue`。共存模式默认二维码可能仍公布 Relay（以实际地址为准）；需要为 Serve 配对时，让电脑端 AI 先验证 Serve 的归属、转发目标和主机信任，再从同一 DSH 的回环接口签发新令牌，生成对应 Serve 入口的配对链接，并确认接收后的重定向仍留在 Serve。不要拿历史二维码盲目换域名，也不要为了临时配对关闭公网服务。已经配对的入口在 App 中切换时无需重配。
6. 可选配套插件 `pc-plugin/index.mjs` 会跟踪 `ctx.webServer.port`，更新这台设备对应的 Serve 根转发。只管理已指定的 HTTPS 入口，遇到其他应用的路由或 Funnel 会拒绝覆盖；不启动第二个 DSH、不签发登录凭据、不修改 DSH 绑定。安装示例见 `pc-plugin/README.md`。

### 手机端

App 打开 **连接管理 → Tailscale**，已保存的电脑入口会自动带入，点 **连接并打开 DSH**。需要时由同页发起系统 VPN 授权与官方账号登录，返回后继续连接。没有地址时可登录并查找电脑，也可重新检测。发现不绕过 DSH 首次配对；同页提供扫描 DSH 配对码、粘贴链接和导出日志。优先填 Serve 的 HTTPS 名称或对应配对链接。若已有转发到 `100.64.0.0/10` 地址的入口，也可填它的实际端口；仅填电脑 Tailscale IP 不会自动创建转发。

在“设置 → 连接管理”保存不同入口后切换。切换前检查目标通道与入口，显示当前阶段和等待时间；失败保留原页面选择。内置 VPN 按所选连接方式启停：切到 Cloudflare、Relay、其他 HTTPS、Wi-Fi 直连或 USB 时关闭 Tailscale / WireGuard，关闭自动恢复意愿；切回组网入口按已保存的提供方自动启动，账号与配置保留。目标探测失败或取消切换时，恢复原入口需要的通道。原入口为公网时，失败的 VPN 切换也会关闭试启的内置通道。普通返回桌面保留当前需要的通道，清理最近任务则停止。更换到 WireGuard 时因 Android VPN 互斥需要停止前一个内置 VPN，配置与账号仍保留。WireGuard 的默认路由可能覆盖公网访问，切出时由 App 处理其路由停止。旧版把 `.ts.net` 标成公网连接的记录会自动纠正，不改变地址、记录 ID 或 Cookie。公网故障、电脑离线、账号过期仍需处理；配对失效单独显示，不伪装成无限重连。WireGuard 缺配置或未授权时不能自动建立通道，需先完成配置。

### 验证方法

电脑运行 `tailscale status`、`tailscale serve status`；在 DSH Pal 内检测 Serve 的 HTTPS 入口，再验证配对、业务响应与实时连接。内置专用 VPN 不会让手机其他浏览器走隧道。

### 已知坑

ACL、设备离线、HTTPS/MagicDNS 配置或中继路径会影响访问。App 已内置 Tailscale，通过官方浏览器完成账号授权，凭据存放在 Android Keystore 加密存储；只为本 App 建立 VPN。普通 HTTP IP 页面可能不能使用网页麦克风，HTTPS Serve 更适合完整功能。

参考：[Tailscale Serve](https://tailscale.com/docs/features/tailscale-serve)、[Serve 命令](https://tailscale.com/docs/reference/tailscale-cli/serve)。

## WireGuard

WireGuard 没有 Tailscale 式的账号登录、设备目录和自动中继。仅在手机内置客户端、或在电脑安装 WireGuard，均不代表已有可用通道；必须有一个手机能够到达的 WireGuard 对端，通常由自己的电脑或服务器承担。没有服务端时，应先部署对端并签发手机配置，不能填入示例配置就宣布连接成功。公网 IPv6 可作为部署候选，但能否接收入站 UDP 仍需实测，不能仅凭存在 IPv6 地址判断。

### 电脑端

先按“第一步”安装公共远程访问插件，再从 [WireGuard 官方页面](https://www.wireguard.com/install/) 安装客户端。它是独立软件，不是 DSH 插件。使用自己的 WireGuard 配置，确认两端密钥、隧道地址、AllowedIPs、路由及访问规则。由部署者提供仅在该网络可用的独立转发入口，目标仍是 DSH 回环端口，并保留 HTTP / WebSocket 和配对校验。本 App 不生成 VPN 密钥或更改路由。

### 手机端

选择 App 的 **连接管理 → WireGuard**，在同页导入 .conf、粘贴或扫描服务端配置，再填写已部署 DSH 服务的地址、端口或配对链接，点“连接并打开 DSH”发起授权与连接。支持私有 IPv4、CGNAT 和 IPv6 ULA；不接受手机回环地址。服务端配置码与 DSH 配对码是不同用途，同页分别提供入口。

### 验证方法

先在内置通道界面确认收发字节和 Peer 握手，再在 App 内检测转发入口。专用 VPN 不会接管手机其他浏览器的流量。VPN 握手成功并不能证明 DSH HTTP 入口可用。

App 切换到使用内置 WireGuard 的已保存入口时，同时等待近期真实 Peer 握手和 DSH 入口响应。本机 VPN 接口启用、旧握手记录或发送字节增加，都不足以证明远端可用。缺少配置时不能启动，失败保留原连接选择。验收还需要页面可见、资源加载、实时通道、以及和其他入口的双向切换；自动化检查不代替实网握手。

### 已知坑

AllowedIPs 不包含目标地址、NAT/防火墙、入口未部署都会阻断。HTTP 页面有安全上下文能力限制。不要将示例端口直接当成已部署端口。

## Wi-Fi 直连

### 电脑端

先按“第一步”安装公共远程访问插件，不存在本 App 要求的另一个“Wi-Fi 插件”。DSH 仅监听回环时，手机无法直接通过电脑 Wi-Fi IP 访问。这个入口要求**已经由部署者提供的 LAN 转发服务**，将它指向 DSH 的真实回环端口，并保留认证和 WebSocket。App 不创建监听、不改 LAN 绑定、不改防火墙。

用 `ipconfig` 查电脑所在网段，从部署者取得入口 IP 与端口。不要为了让示例地址可用而修改 DSH 的 webserver 配置。

### 手机端

连接相同 Wi-Fi → **Wi-Fi 直连** → 填写 `192.168.1.10:<入口端口>` 或完整配对链接。输入必须是有效的 RFC1918 私有 IPv4。首次授权仍走远程插件原有配对流程。

### 验证方法

手机浏览器打开 `http://<入口IP>:<入口端口>/`。电脑可运行：

```powershell
Test-NetConnection <入口IP> -Port <入口端口>
```

浏览器能看到登录/配对页后，再验证 App。没有预先部署 LAN 入口时，此方案不能仅靠填一个地址成立。

### 已知坑

AP/客户端隔离、电脑防火墙、网络不通、入口 IP 变化、服务自动端口变动。普通 HTTP 的网页麦克风可能不可用；如有需求应部署可信 HTTPS。

## USB 数据线

### 电脑端

先按“第一步”安装公共远程访问插件。ADB 与 USB 助手是开发工具，不是 DSH 插件；需已安装项目构建所需的 JDK / Android SDK 与 Node.js。然后在本项目根目录运行便携脚本：

```powershell
.\tools\start-usb-development.ps1 -Serial <设备序列号>
# 已安装开发版，恢复映射与配对：
.\tools\start-usb-development.ps1 -Serial <设备序列号> -AttachOnly
```

脚本使用回环代理和 `adb reverse`，不修改 DSH 绑定。端口探测失败可加 `-LocalPort <DSH实际端口>`；需要检查 WebView 时加 `-Inspect`。只使用覆盖安装，不能卸载或清数据。

### 手机端

授权 USB 调试，脚本会投递配对。开发版可选 **USB 数据线**，入口固定为 `http://dsh-usb.localhost:18765`。这个别名是已有远控路径兼容的一部分，不应换成裸 `127.0.0.1`。

### 验证方法

`adb devices` 显示 `device`；App 日志出现 `ws OPEN`。CSS 可通过数据线热更新。拔线后可切回同版本已保存的公网连接。

### 已知坑

正式构建禁用 USB 调试入口；不同构建的连接数据分别保存。线材、授权、ADB 反向映射或代理未运行时不可用；不是修改 LAN 配置可以修复的问题。

## frp（已有 VPS 的高级部署）

归类在 **其他 HTTPS / frp** 入口；它与 Cloudflare Tunnel、Relay 分开保存和展示。

- VPS：部署 frps，设置认证与明确的入口策略，用域名和可信 TLS 终端发布 HTTPS。
- 电脑：frpc 的后端为 `127.0.0.1:<DSH实际端口>`，不改 DSH 服务监听；保证 WebSocket 可持续连接。
- 插件：手动 `publicBaseUrl` 为已发布 HTTPS 域名，关闭会覆盖该地址的自动 Tunnel。不要把 frp 凭据交给手机。
- 手机：扫码这个入口的配对码，或把配对链接保存为一条“其他 HTTPS / frp”类型的连接。
- 验证：`frpc verify -c ./frpc.toml` / `frps verify -c ./frps.toml` 检查配置，再从手机打开 HTTPS 入口、验证实时消息。
- 已知坑：TLS/Host 配置不匹配、WebSocket 超时、VPS 成本与维护、端口变化。本 App 不提供或自动配置 VPS。

完整示例以 [frp 官方配置文档](https://gofrp.org/en/docs/features/common/configure/) 为准，避免套用与安装版本不符的模板。

## 让电脑端 AI 协助配置（可复制）

在**被控端电脑**打开能操作本机的 AI，复制下方“公共要求”及所选方案的指令，一起发送。App 帮助页也有 **复制 AI 配置提示词**，会自动带入所选 `desktop` / `web`，不需要手工拼接。AI 若只有聊天能力，不能代替你安装或点设置；登录、系统授权和手机扫码仍需本人完成。

### 公共要求（每个方案都要带上）

```text
请在当前被控端电脑配置 DSH Pal，尽量直接完成你有权限执行的步骤，不要只给概念建议。
先识别我实际使用的 DSH 实例、profile、版本、回环端口和已有插件；桌面客户端通常为 desktop，dsh web 通常为 web，自定义 profile 要按现场确认。
缺少公共远程访问插件时，执行下面匹配的一条命令，不要两条都跑：
dsh plugin --profile desktop add @linxin666/dsh-remote-web-ui@latest
dsh plugin --profile web add @linxin666/dsh-remote-web-ui@latest
已有插件则复用，不为连接重复升级。找不到 dsh 时先定位与当前 DSH 匹配的 CLI，不擅自升级整个 DSH。
配置前备份将要更改的插件设置，保留其他连接与授权。不得修改 cordis.patch.yml 中 webserver 的 host/port、LAN 绑定或防火墙，不关闭认证，不卸载手机 App、不清数据，不打断正在执行的任务。需要重启时先说明影响，待任务保存后处理。
能执行的检查与配置请直接完成；没有配置页操作工具时，准确列出要点击的选项，不冒充已执行。登录、系统授权和扫码才由我完成。
记录步骤、失败阶段与原因。最终交付 profile、实际入口、配对位置及验证结果。日志脱敏，不回显 token、Cookie、密钥、私人地址，不把二维码或配对链接放进公开 Issue。
```

### 推荐：Cloudflare Tunnel（Relay 可同时保留）

```text
选择 Cloudflare Quick Tunnel；先使用不需要 Cloudflare 账号或自有域名的方案，保留已有 Relay 供切换备用。
检查公共远程访问插件已启用且存在远程访问入口。将插件 autoTunnel 和 relay 开启，LAN 绑定保持关闭。cloudflared 由插件准备；若准备失败，检查插件日志、官方下载来源、网络和权限，避免重复启动隧道。
确认隧道状态、Relay 注册与最终 HTTPS 配对地址。Relay 不可用时明确标注 Quick 临时地址的限制。
从电脑回环接口签发，优先展示插件面板二维码或私下提供配对链接。不要从公网 /api/pair/issue 签发，403 是保护机制。
检查公网 HTTP；401/403 认证响应不能直接等同于网络不通。先核对二维码实际域名：Tunnel 链接使用 Cloudflare Tunnel 入口，Relay 链接使用 Relay 固定域名中继入口；不能把默认 Relay 二维码当成 Tunnel。
扫码后检查 bridge ready、ws OPEN、服务端连接状态和实际页面。只有电脑准备完毕而手机没扫码时，请明确报告“等待手机配对”。
```

### Tailscale / WireGuard

```text
选择 Tailscale；若我明确使用 WireGuard，再检查已有 WireGuard 配置。
检查公共远程访问插件与电脑官方客户端。手机使用 DSH Pal 内置 Tailscale 登录或导入 WireGuard 配置；登录与系统 VPN 授权由我完成，不读取其他客户端的私有凭据。Android VPN 通常互斥，切换前说明影响。
Tailscale：确认 DSH 实际回环端口后，执行 tailscale serve --bg http://127.0.0.1:实际端口，运行 tailscale serve status，按官方提示完成 HTTPS 配置。
WireGuard：确认握手、路由与现有 DSH 转发入口；没有入口时先给出独立转发方案，不能把 DSH 改绑到 VPN IP。
保留已有公网入口与插件配置，不为 Tailscale 关闭 autoTunnel 或 Relay。先验证 Serve 与原公网入口归属同一 DSH，再从回环签发新令牌，生成对应 Serve 的新配对链接；接收后的重定向必须留在选择的入口。不要盲改历史二维码域名。
先在 App 内检测入口，再生成该入口的配对码，让手机选 Tailscale / WireGuard，并验证实时通道。
```

### Wi-Fi 直连

```text
选择 Wi-Fi 直连。先确认公共远程访问插件、两端网络、DSH 回环端口，以及现有独立 LAN 转发入口。
不改 DSH 监听或 LAN 绑定，不自动创建防火墙规则。没有现成入口时，请明确指出缺少条件，给出需我另行批准的部署方案，不要仅填电脑 IP 就宣布完成。
已有入口时检查 HTTP、WebSocket 和认证，记录原设置后配置插件的 publicBaseUrl、关闭 autoTunnel，核对 Relay 与最终二维码。
用同一 Wi-Fi 手机浏览器验证，再在 App 的 Wi-Fi 入口配对，检查可见页面与实时通道。
```

### USB 开发调试

```text
选择项目的 USB 开发通道。定位 DSH Pal 项目，先完整检查 tools/start-usb-development.ps1 的副作用，确认 JDK、Android SDK、Node.js、ADB 与已授权真机。
用脚本指定设备建立 adb reverse 与回环代理；已有合适开发版可用 -AttachOnly。安装只用 adb install -r，不卸载、不清数据、不启动模拟器、不改 DSH 绑定。
确认手机使用 dsh-usb.localhost 开发入口，验证 bridge ready、ws OPEN 和实际页面。说明拔线后如何恢复映射或切回已保存公网入口。
```

## 输入框原生功能与外观

- 文件选择交给 Android 系统文件选择器，支持取消与多选；只访问用户选中的文件。实际上传协议由 DSH 的文件上传插件继续处理。
- 日志/附件下载可选择系统保存位置；Blob 导出分块写入，不把配对凭据或文件内容记进日志。保存失败时可能保留未完成文件。
- 网页语音需要用户授予系统麦克风权限，且页面必须满足浏览器安全上下文及 DSH 语音插件自身配置。App 不自动配置转写服务。
- 计划、目标、压缩、权限与模型等菜单由 DSH 实现。App 不伪造执行成功，不在用户真实会话中自动执行这些会改变任务的指令。
- 设置 → 外观：默认 **跟随 DSH 背景**，无需任何壁纸插件；有壁纸显示壁纸，没有壁纸沿用 DSH 默认主题。本地色调仅覆盖手机显示，切回跟随后撤掉所有本地覆盖，不调用远端设置接口。

## 排查

| 现象 | 先查什么 |
|---|---|
| 白屏、一直加载插件 | 先查入口是否可达，再查日志；可 ping 目标观察丢包。高丢包提示网络差，但 ICMP 被禁不能单独证明 HTTP 不通 |
| 公网配对签发 403 | 从电脑回环接口签发，保留安全限制 |
| VPN 已连接但 App 不通 | 入口是否已转发、端口、路由、ACL、防火墙 |
| 新地址提示重新配对 | 不同 origin 的首次授权、服务端是否认可该入口；不要清掉全部旧连接 |
| 切换时旧连接关闭 | App 分别跟踪 mux 实例，旧连接关闭不再覆盖已经打开的新连接状态 |
| 文件/导出/语音不工作 | 查看 File / Download / Permission 日志；区分系统选择器、HTTP 错误、转写服务与浏览器安全上下文 |
| 灵动岛不显示 | 当前使用 MediaStyle 音乐通道，无需私有焦点通知授权；普通任务通知是否允许、ROM 是否支持音乐岛 |

## 导出问题日志与提交 Issue

未连接时可在方案首页点 **导出问题日志**；已连接时进入 **设置 → 支持与诊断 → 导出并分享**。生成的是文本附件，由用户选择保存或分享；App 不会自动发出 Issue。

附件包括 App / Android / WebView 版本、连接方式、加载与桥接阶段、地址检测耗时与 HTTP 结果、文件与权限结果，并附期望行为、实际行为及复现步骤模板。URL、授权参数、IP 与本机路径经过脱敏；不记录录音、文件内容或输入框正文。发布前仍应检查附件及截图。

地址检测只检查入口可达性，不代替配对或实时通道验收。它不携带一次性配对参数或浏览器 Cookie；因此检测返回 401 而已配对页面正常是合理结果。USB 别名仅在开发构建的原生检测与下载中映射回环，正式构建没有这个映射。

## 本次验证范围（2026-10-27）

- 真机：两级入口、帮助与设置页面；已保存两条连接，覆盖安装后保留；切回 USB 复用认证，实时 mux 通道打开。
- 原生检测：USB 入口约 31 ms 返回 401，提示入口可达且需要认证。
- 输入功能：系统文件选择与 256 KiB Blob 导出完成，文件哈希一致；同源 HTTP 下载完成，18,006 字节哈希一致；麦克风授权后打开音轨并立即停止。
- 外观：页面没有壁纸插件 iframe 时，本地色调生效；切回跟随 DSH 后本地覆盖移除，不重新加载页面。
- 导出：生成含版本、诊断结果及复现模板的附件，打开系统分享面板；未自动分享给任何第三方。
- USB 与正式构建通过，91 项单元测试通过。测试覆盖地址分类、连接持久化模型与迁移、Socket 信任范围、任务 Socket 生命周期、下载策略、开发专用 DNS、脱敏等。
- 边界：公网线路实测有较高丢包，仅验证了入口切换，未宣布公网完整流程通过；没有现成 LAN / Tailscale / WireGuard DSH 入口，未做这些路径的真机端到端测试。语音转写和会改变真实会话的指令未执行。

插件与官方输入功能兼容：[兼容规则与验证边界](plugin-compatibility.md)。

技术与开发说明：[真机迭代](development.md)、[静态缓存](static-resource-cache.md)。


## 内置 VPN 与网络状态的边界

手机只把 DSH Pal 加入 VPN；其他 App 使用原有网络。Tailscale 和 WireGuard 互斥，断开不会删除 DSH 连接记录。电脑使用 Serve，不设置出口节点，不改变 DSH webserver 绑定。Android 要求运行中的 VPN 有系统通知，不能将它与可关闭的任务通知混为一谈。

WebSocket OPEN 仅证明通道握手完成。顶部会分别指出手机断网、公网隧道 HTTP 530、服务端 5xx、接口超过 8 秒仍未完成、脚本/样式失败和待验证业务。入口探测失败率来自最近最多 10 次 HTTP HEAD，不是 ICMP 丢包率；业务检测旁路观察原请求，不发送测试消息。收到响应头后，JSON/正文消费卡住也会标记等待。自动恢复不重放消息、上传或指令，有未发送内容及活动传输时延后页面刷新。

DSHgo 的 `dsh-phone-remote` 是它自己的电脑桥接插件，负责 minter、代理与 Serve。本项目默认复用 `@linxin666/dsh-remote-web-ui` 的配对和远控通道，不能把两套插件的安装步骤混为一谈。已有公网入口不应为了 Tailscale 被静默替换；需检查额外主机信任和配对重定向。


## 插件合并包缓存与 USB 静态预览

DSH 还会使用 `/plugins/??<模块列表>&rev=<版本>` 返回合并后的 JavaScript。此类地址需要同时验证固定路径、模块文件名和非空版本号；带凭据、额外未知参数或路径穿越的请求不能进入缓存。服务器明确声明 immutable 的同一版本可直接复用；版本变化使用新键。未声明 immutable 时继续按响应策略检查，no-store 仍不落盘。单资源上限 12 MiB，总量 96 MiB。

USB 开发版的“缓存与加载”提供 **USB 静态资源预览**，默认关闭。仅在电脑建立 `adb -s <serial> reverse tcp:18768 tcp:<当前DSH端口>` 后启用。它只改变合格静态资源的传输路径，缓存仍按原始 URL 与认证上下文分隔；配对、API、WebSocket、上传和任务不会转到数据线。正式版不支持这个开关。预热后关闭开关，可继续使用本地缓存和原业务路径。

首次启动内置 VPN 时，页面导航会先等待通道与入口可达（有界等待），避免网页在 VPN 建立前产生 DNS 阻塞。已有输入、活动上传或录音仍受刷新保护。

## “资源失败”与“插件接口受限”

`/plugins/<插件>/state`、`settings`、`status` 等是插件接口，不是 JavaScript 文件。返回 401/403 表示访问被拒绝，应单独显示“插件接口受限”，不能记成资源包丢失或因此自动刷新。计数按接口路径去重，不按会话参数重复累加。Qoder Connect 和 Sentinel 的部分本机接口存在明确的 loopback Host/Origin 限制；手机走 VPN 也不会改变这些校验。此类限制保留，不能通过放宽桌面授权来消除提示。

接口超时、真正的脚本加载失败、插件授权受限和入口请求失败分别记录。顶部入口探测失败率仅描述最近的 HTTP 探测，不是 ICMP 丢包率，也不保证每个插件功能均已验收。
