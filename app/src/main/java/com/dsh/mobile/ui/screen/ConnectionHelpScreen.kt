package com.dsh.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class ConnectionGuide(val name: String, val sections: List<Pair<String, String>>)
private val guides = listOf(
    // Ordered so the route a new user is most likely to have comes first, and so
    // the "推荐" marker matches the address the desktop panel actually prints.
    ConnectionGuide("Relay 固定域名中继（推荐）", listOf(
        "这是什么（先看这条）" to "电脑上的 DSH 装了远程访问插件后，面板里那张二维码里的地址，通常就是这一项。你扫码之后手机上显示「Relay 固定域名中继」，说明你走的就是这条路，不是错误。\n\n判断方法：地址以 dsh-market.com 结尾 → Relay；以 trycloudflare.com 结尾 → Cloudflare Tunnel；以 ts.net 结尾 → Tailscale。App 会按地址自动认类型，你不用手选。",

        "电脑端①：安装远程访问插件" to "Relay 依赖 DSH 的「远程访问」插件，手机本身不产生这个地址。\n\n1. 在电脑上打开 DSH。\n2. 进入插件市场，搜索并安装 dsh-remote-web-ui（远程控制 / 远程访问插件）。\n3. 安装后 DSH 侧边栏或设置里会出现「远程访问」入口，打开它。\n\n如果你不确定装没装：直接在 DSH 里问 AI「我的远程访问插件装好了吗，二维码在哪」它会告诉你。\n\n注意：只装 Tailscale 或 Cloudflare 的客户端不会产生这个二维码；它是 DSH 插件提供的，和 VPN 是两回事。",

        "电脑端②：拿到二维码" to "打开「远程访问」面板后，会看到一张二维码，下面通常写着类似：\nhttps://your-instance.dsh-market.com/pair-accept?pair=...\n\n这个地址就是 Relay 入口。手机上扫它即可。\n\n如果面板提示隧道/中继未运行，先点启动或刷新，等它显示 running 再扫。",

        "手机端：三步完成" to "1. 连接管理（或初次打开的引导页）→ 选择「Relay 固定域名中继」。\n2. 点「打开相机扫码」，对着电脑屏幕上的二维码扫。\n3. 扫完自动配对并进入界面。\n\n没有相机权限、或二维码在另一台设备上：用「粘贴链接」，把电脑面板里那条完整链接复制到手机粘贴。\n\n提示「配对已失效」：电脑面板刷新一次二维码再扫，配对码有时效。",

        "优点与限制" to "地址固定，重启电脑或重开 App 都不用重新配对，一次配对长期有效（凭据保留约一年）。\n访问会经过 Relay 中转，速度取决于当时网络：同一条线路也可能时快时慢，慢的时候不代表坏了。\n如果当前网络对 Relay 不友好，可以随时在「连接管理」里切到 Tailscale，配对记录会保留，切回来不用重扫。",

        "和 Cloudflare Tunnel 的区别" to "两者都是同一个插件提供的入口，只是地址和链路不同。\n\nRelay：地址固定，扫码即用，经过中转。\nCloudflare Tunnel：直连 Cloudflare 隧道，但 Quick 模式地址会随重启变化，变了就要重新配对；Named 模式用你自己的域名才固定。\n\n不知道选哪个就先用 Relay。电脑面板默认给出的二维码一般就是 Relay。",

        "验证成功" to "顶部状态条显示「已收到业务响应 · 入口 xxx ms」；能看到会话列表和输入框；日志里出现 bridge ready 与 ws OPEN。\n\n只看到「通道已建立」但没有会话列表，说明配对还没完成，回到手机端第二步重新扫码。",

        "连不上或很慢" to "1. 先在电脑上 ping 一下入口域名，丢包超过 20% 时问题通常在网络，不在 App。\n2. 换一条网络试试（例如从 Wi-Fi 切到手机热点），App 会用已保存的凭据自动重连，不需要重新扫码。\n3. 仍然不行，可切到 Tailscale 这条更独立的路（见下一条教程）。"
    )),
    ConnectionGuide("Cloudflare Tunnel", listOf(
        "这是什么" to "同一个远控插件的另一条出口：不经过 Relay 中转，直连 Cloudflare 隧道。地址以 trycloudflare.com（Quick）或你自己的域名（Named）结尾。\n注意：面板默认二维码若指向 dsh-market.com，那其实是 Relay，直接扫就会走 Relay，这里不适用。",
        "Quick 模式（最简单）" to "① 电脑插件里开启自动公网隧道，等它生成 trycloudflare.com 地址。\n② 手机连接管理 → 选择「Cloudflare Tunnel」→ 扫描该地址对应的二维码。\n③ Quick 地址每次重启可能变化，变化后需要重新扫一次。",
        "Named 模式（需要自己的域名）" to "① 在 Cloudflare 建好 Named Tunnel，后端指向 DSH 的实际回环端口。\n② 插件设置里填写 tunnelToken 与 publicBaseUrl，关闭 autoTunnel。\n③ 确认最终二维码地址是你要的那个域名，再用手机扫。\n只有这个模式需要 Cloudflare 账号和域名；token 不要发到 Issue 或聊天里。",
        "手机端" to "连接管理 → 添加连接 → Cloudflare Tunnel → 扫码或粘贴配对链接。如果扫到的其实是已知 Relay 地址，App 会把它归到 Relay 并提示，不会悄悄混在 Tunnel 里。",
        "验证与限制" to "先用手机浏览器打开同一个地址，能出页面再回 App 扫码。日志应有 bridge ready、ws OPEN。\nQuick 地址变化属正常现象，不是插件坏了。"
    )),
    ConnectionGuide("其他 HTTPS / frp", listOf(
        "适用范围" to "用于已经部署的 frp、HTTPS 反向代理，或后端方案尚不明确的自有域名。域名在 Cloudflare 托管，不代表实际远控路径就是 Cloudflare Tunnel。",
        "电脑端" to "安装同一个公共远控插件；管理员提供已验证的 HTTPS 入口，将 HTTP 和 WebSocket 转发到 DSH 当前回环服务，保留配对认证。转发端口是实际部署值，不是假定端口。App 不替你修改 LAN、防火墙或插件公布地址。",
        "手机端" to "选择其他 HTTPS / frp，粘贴为此入口生成的完整配对链接。已知 Cloudflare、Relay、Tailscale 域名会自动识别；其他自有域名按你明确选择的类型保存。不能仅靠陌生域名自动猜出服务商。",
        "地址变化与兼容" to "相同地址保持原凭据；新域名需首次配对。旧版混合记录里的未知域名迁入本项，不改地址和登录状态。若确认它是 Named Tunnel，可在对应入口重新保存同一地址以纠正类型，不用删旧凭据。"
    )),
    ConnectionGuide("Tailscale / WireGuard", listOf(
        "先弄明白两件事（必读）" to "第一层：网络。手机和电脑登录同一个 Tailscale 账号后，就能互相访问——这一步叫「VPN 已建立」。\n第二层：应用。DSH 需要认得这台手机，所以要配对一次——这一步叫「已配对」。\n\n两层都完成才能进界面。只做第一层时，App 会明确显示「VPN 已连通，但这个入口还没有配对凭据」，那不是故障，是还差第二步。\n\n好处：配对一次之后长期有效。以后换 Wi-Fi、切流量、重开 App，都不需要重新登录 GitHub，也不需要重新扫码。",

        "电脑端①：安装并登录 Tailscale" to "1. 下载安装 Tailscale 官方客户端（tailscale.com/download），Windows 版装完右下角托盘会出现图标。\n2. 点托盘图标 → Log in，浏览器里用你的账号登录（Google / GitHub / Microsoft 都行）。\n3. 记下这台电脑的 Tailscale 名称，例如 example-node。\n\n验证：托盘菜单里能看到自己的设备列表，且电脑显示 Connected。",

        "电脑端②：查出 DSH 的端口（关键一步）" to "DSH 每次启动的端口是随机分配的，所以不能照抄别人教程里的数字，必须自己查当前的。\n\n方法一（推荐，最省事）：\n在 DSH 里直接问 AI：「DSH webserver 当前监听哪个端口？」它会告诉你，例如 12345。\n\n方法二（手动）：\nWindows 按 Win+R 输入 cmd 回车，粘贴下面这行：\nnetstat -ano | findstr LISTENING | findstr 127.0.0.1\n在结果里找 DeepSeek Harness 进程占用的端口。也可用任务管理器 → 详细信息 → 找到 DeepSeek Harness 记下 PID，再对上 netstat 最后一列相同的 PID。\n\n这个端口号后面要填进命令里。",

        "电脑端③：开启 Tailscale Serve" to "Serve 的作用是把 Tailscale 网络里的地址，转发到你电脑上的 DSH。\n\n按 Win+R 输入 powershell 回车，然后依次执行（把 12345 换成上一步查到的真实端口）：\n\ntailscale serve --bg http://127.0.0.1:12345\n\n执行后检查是否成功：\n\ntailscale serve status\n\n成功时输出类似：\nhttps://example-node.example-tailnet.ts.net\n|-- / proxy http://127.0.0.1:12345\n\n把这个以 ts.net 结尾的地址记下来（每人不同，形如 名称.xxxxx.ts.net）。\n\n常见问题：如果提示需要启用 HTTPS，按提示在 Tailscale 管理后台打开 HTTPS Certificates 即可。Serve 只需配置一次，重启电脑后依然有效。",

        "手机端：登录并自动连接" to "1. 连接管理 → 「内置通道（Tailscale / WireGuard）」→ 点「登录 / 重新打开授权页」。\n2. 浏览器里完成 Tailscale 登录，然后返回 App。\n3. 内核状态显示 Running、VPN 显示已建立后，App 会发现电脑入口，选择目标后连接；有有效配对凭据时继续打开工作台。\n\n第一次使用如果还没有配对过，App 会自动打开配对页；若提示需要配对，点「连不上？手动配对」扫一次电脑面板的二维码即可，之后都会自动沿用。\n\n注意：不要清空 App 数据排障，这可能丢失内置账号、配置和配对记录；换手机或退出账号后需要重新登录。",

        "怎么确认成功了" to "App 顶部状态条出现「VPN · 已收到业务响应 · 入口 xxx ms」，并且能看到会话列表和输入框。\n\n如果显示「VPN 已连通，但还没有配对凭据」→ 只差配对，扫一次码。\n如果显示「VPN 尚未建立」→ 回到电脑端③，确认 serve 命令已成功执行。\n如果 VPN 已建立但一直进不去 → 多半是 Tailscale 中继（DERP）距离太远，属于网络问题，可以先切回 Relay 使用。",

        "WireGuard 怎么写" to "WireGuard 没有自动发现，需要你已经有可用的服务端配置：\n1. 在 WireGuard 服务端配置好手机与电脑互通，导出手机的 .conf 文件。\n2. 手机连接管理 → 内置通道 → 选 WireGuard → 导入配置文件。\n3. 由于 WireGuard 没有域名发现能力，你还需要一个能从 VPN 内访问到的 DSH 入口（通常是电脑的 VPN 内网 IP 加 DSH 端口，再经反向代理转发）。这个入口需要你在电脑端自行准备。\n\n如果你只是想让手机连上电脑的 DSH，用 Tailscale 比 WireGuard 省事得多。",

        "限制与注意事项" to "· 启用内置通道会占用系统 VPN 槽位，可能替换你正在用的其他 VPN。\n· 切到公网入口（Relay / Cloudflare）时 App 会自动关闭内置 VPN；切回来会自动开启。\n· 直连失败时会走 Tailscale 中继（DERP），跨地区中继慢是正常现象。\n· 普通 HTTP 的 IP 页面无法使用网页麦克风，应使用 HTTPS。"
    )),
    ConnectionGuide("Wi-Fi 直连", listOf(
        "优点与限制" to "同一 Wi-Fi 下通常路径较短，无需公网隧道。离开该网络后不能使用；AP 隔离、电脑防火墙、变化的 IP 都会影响连接。",
        "需要安装" to "公共远程访问插件 + 已部署的 LAN 转发入口。不需要另找 Wi-Fi DSH 插件；仅安装公共插件不会自动创建此转发。",
        "电脑端" to "DSH 桌面服务仍保持 127.0.0.1 和自动端口。先由网络管理员提供独立的 LAN 转发入口，将它转发到 DSH 回环服务，并保留认证与 WebSocket。端口使用入口的真实值，不是假定的 3080。本 App 不修改绑定、防火墙或 LAN 配置。",
        "插件配置" to "转发入口准备好后，在公共插件设置中填写该入口的 publicBaseUrl，关闭自动隧道并核对最终二维码；保留配对认证。不要启用会修改 webserver 的 LAN 绑定开关。",
        "手机端" to "接入相同 Wi-Fi → 选择 Wi-Fi 直连 → 填入已有入口，例如 192.168.1.10:入口端口，自动补 http://。若提示配对，使用为这个入口生成的完整配对链接。",
        "验证方法" to "手机浏览器可打开入口；电脑可用 Test-NetConnection <入口地址> -Port <入口端口> 检查监听。只有浏览器可用后再验 App 的实时连接。",
        "已知问题" to "只有回环服务时，直接填电脑 Wi-Fi IP 无法访问。不要为此修改 cordis.patch.yml 的 webserver。HTTP 的麦克风能力受浏览器安全上下文限制。"
    )),
    ConnectionGuide("USB 数据线", listOf(
        "优点与限制" to "适合开发适配和排查公网问题，资源走数据线。需要电脑、ADB 和手机 USB 调试授权；正式版不开放调试接口。",
        "需要安装" to "公共远程访问插件 + 电脑 ADB、项目构建环境及 USB 助手 + 手机 USB 开发版。ADB 是电脑开发工具，不是 DSH 插件。",
        "电脑端" to "在项目目录运行：\n.\\tools\\start-usb-development.ps1 -Serial <设备序列号>\n脚本构建开发版、覆盖安装、建立反向端口和回环代理，不改变 DSH 绑定。端口探测失败可加 -LocalPort <DSH实际端口>。",
        "手机端" to "授权 USB 调试后由脚本投递配对。USB 开发版中可选择 USB 数据线，入口为 http://dsh-usb.localhost:18765。不要改成裸 127.0.0.1。",
        "验证方法" to "adb devices 显示 device，顶部显示 USB 调试，日志出现 ws OPEN。断线重连可给脚本加 -AttachOnly。",
        "已知问题" to "拔线后此连接不可用，可手动切回已保存的公网连接。USB 版和正式版分别保存连接记录，切版不会覆盖对方数据。"
    ))
)

@Composable
fun ConnectionHelpScreen(initialKind: com.dsh.mobile.core.ConnectionProfile.Kind? = null, onBack: () -> Unit) {
    var profile by remember { mutableStateOf("desktop") }
    var selected by remember { mutableStateOf(when(initialKind) {
        // Index map must follow the guides list order (Relay now leads). Mismatched
        // indices here would open the wrong tutorial from every entry point.
        com.dsh.mobile.core.ConnectionProfile.Kind.RELAY -> 0
        com.dsh.mobile.core.ConnectionProfile.Kind.TUNNEL -> 1
        com.dsh.mobile.core.ConnectionProfile.Kind.CUSTOM -> 2
        com.dsh.mobile.core.ConnectionProfile.Kind.MESH -> 3
        com.dsh.mobile.core.ConnectionProfile.Kind.LAN -> 4
        com.dsh.mobile.core.ConnectionProfile.Kind.USB -> 5
        else -> 0
    }) }
    com.dsh.mobile.ui.AppBackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max=840.dp).fillMaxWidth().padding(20.dp)){com.dsh.mobile.ui.AppPageHeader("连接帮助",onBack=onBack)}
        Column(Modifier.weight(1f).widthIn(max=840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            guides.forEachIndexed { i, guide ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { selected = i }) {
                    Text("${if(i == selected) "●" else "○"} ${guide.name}", modifier = Modifier.padding(14.dp), color = if(i == selected) com.dsh.mobile.ui.theme.LocalDshAccents.current.accent else MiuixTheme.colorScheme.onBackground)
                }
            }
            PluginInstallCard(profile) { profile = it }
            AiSetupCard(selected, profile)
            SelectionContainer {
                Column {
                    guides[selected].sections.forEach { (title, body) ->
                        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(title, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(8.dp))
                                Text(body, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                            }
                        }
                    }
                    Text("每个新地址可能需要首次配对；切回已配对地址会复用其登录状态。切换前请保存草稿。服务更新后实际回环端口可能变化。", fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun PluginInstallCard(profile: String, onProfileChange: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val command = "dsh plugin --profile $profile add @linxin666/dsh-remote-web-ui@latest"
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("先在电脑安装 DSH 插件", fontWeight = FontWeight.SemiBold)
            Text("所有路径共用这个插件，区别在配套工具。已有远程访问入口可跳过安装；尚未安装时，按你使用的 DSH 选择一条命令。", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("desktop" to "桌面客户端", "web" to "dsh web").forEach { (value, title) ->
                    Text("${if(profile == value) "●" else "○"} $title", fontSize = 13.sp,
                        color = com.dsh.mobile.ui.theme.LocalDshAccents.current.accent,
                        modifier = Modifier.clickable { onProfileChange(value) }.padding(vertical = 10.dp))
                }
            }
            SelectionContainer {
                Text(command, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.onBackground.copy(alpha = .04f)).padding(12.dp))
            }
            top.yukonga.miuix.kmp.basic.Button(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DSH 插件安装命令", command))
                android.widget.Toast.makeText(context, "已复制，请在被控端电脑终端执行", android.widget.Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(vertical = 8.dp)) { Text("复制安装命令") }
            Text("电脑执行 → 确认该 DSH 已启用插件并出现远程访问入口 → 按下方方案配置 → 手机再扫码。自定义 profile 请替换命令里的名称。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            Text("提示找不到 dsh？先安装并启用 DSH CLI，再打开新终端；也可在桌面客户端插件管理中按完整包名安装。不要为此更改 webserver 绑定。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun setupPrompt(index: Int, profile: String): String {
    // Index order follows `guides` (Relay is 0 now). These prompts are pasted into a
    // desktop AI, so a stale index would ask it to build the wrong route entirely.
    val route = when(index) {
        0 -> """
            方案：DSH 插件的 Relay 固定域名中继（推荐路线），和直接 Cloudflare Tunnel 分开保存。
            复用现有公共远控插件与已运行 Relay，确认实际固定域名、入口响应和WebSocket；不要因为手机选择此入口就修改插件优先级或关闭其他通道。
            仅从该 DSH 的回环 /api/pair/issue 用 POST 签发，核对返回链接确为所选 Relay；若不是，先解释当前公布地址，不盲目替换旧二维码。
            手机在 Relay 专属页面扫码，检查实际地址类型、配对、资源和实时连接；与 Tunnel、Tailscale 来回切换，失败保留原连接。日志脱敏且区分 Relay 的探测失败与业务错误。
        """
        1 -> """
            方案：直接 Cloudflare Tunnel，不是 Relay 固定域名中继。先按 Quick Tunnel 无需自有域名的方案处理；若我明确提供 Named 域名再按实际部署检查。
            复用现有自动隧道与 cloudflared，不关闭已有 Relay、Tailscale，不改插件域名优先级。确认 Tunnel 的真实地址与 HTTPS、WebSocket 响应。
            面板默认二维码可能仍是 Relay。先从当前 DSH 回环状态确认 Tunnel URL 是它自己的隧道，再从回环接口签发新令牌，为这个已核实入口生成完整配对链接或二维码；不能盲改历史二维码域名。
            从电脑回环接口签发配对，优先展示插件面板二维码；不能从公网 /api/pair/issue 签发，403 不是要关闭的保护。
            用 HTTP 检查公网入口，不把 401/403 的认证响应直接当成网络失败；扫码后验证 bridge ready、ws OPEN、服务端已连接和可见页面。缺少手机操作时说明等待扫码，不能声称已全部验证。
        """
        2 -> """
            方案：我已有的其他 HTTPS / frp 转发。先识别真实部署，不因域名托管在 Cloudflare 就认定是 Cloudflare Tunnel。
            核对 HTTPS、回环转发目标、WebSocket、配对认证和外部访问，复用已运行的入口；缺少配置先列出需要部署者提供的条件，不擅自新建公网服务或改防火墙。
            从同一 DSH 回环签发对应入口的新配对；不要复制另一域名的 Cookie 或盲改历史二维码。手机选择其他 HTTPS / frp，验证切回旧入口无需重新配对。
        """
        3 -> """
            方案：优先 Tailscale Serve；若我明确使用 WireGuard，再按已有 WireGuard 网络配置。
            检查电脑官方客户端与网络状态；缺少时引导安装。手机使用 DSH Pal 内置通道登录 Tailscale 或导入 WireGuard 配置，账号登录、系统 VPN 授权由我完成；不导出其他客户端私有凭据。
            Tailscale 登录后对确认的 DSH 回环端口执行 tailscale serve --bg http://127.0.0.1:实际端口，并运行 tailscale serve status 检查，按官方提示处理 HTTPS。
            WireGuard 则检查现有握手、路由与入口，缺少转发时先给出独立转发方案，不更改 DSH 监听。
            多路径共存时保留现有 autoTunnel 与 relay；将 Serve 主机加入 trustedHosts，保留各入口认证。核实 Serve 属于当前电脑且确实转发到同一 DSH 后，从回环签发新令牌，为对应入口生成链接并验证重定向不跳回公网；不要盲目替换历史二维码。只在用户明确只用一个手动入口时才改 publicBaseUrl 并关闭自动通道。手机保存各入口后，来回切换并检查业务接口、静态资源与实时通道；失败保留原连接。
        """
        4 -> """
            方案：同一 Wi-Fi 的已有独立转发入口。
            查明两端网络、DSH 回环端口与现有 LAN 转发监听，检查 HTTP、WebSocket 和认证是否可用。
            不允许把 DSH 改绑 LAN，也不自动创建防火墙规则。若没有独立转发入口，明确说明缺少条件，给出待我批准的方案，不能只填电脑 IP 就宣布成功。
            已有入口可用时，记录插件原设置后配置对应 publicBaseUrl，关闭 autoTunnel，核对 Relay 与二维码地址，保留配对认证。手机选择 Wi-Fi 直连扫码并验证页面、实时通道。
        """
        else -> """
            方案：项目的 USB 开发通道。
            查找 DSH Pal 项目，先完整检查 tools/start-usb-development.ps1 的副作用，再检查 JDK/Android SDK/Node.js/ADB 与授权真机。
            使用脚本的设备选择参数建立 adb reverse 和回环代理，安装必须 adb install -r；已有合适开发版可用 -AttachOnly。
            不修改 DSH 绑定，不启动模拟器；确认 dsh-usb.localhost 开发入口、bridge ready 与 ws OPEN，说明拔线后如何恢复映射或切换公网。
        """
    }
    return """
        请在这台被控端电脑帮我配置 DSH Pal 连接，尽量自动完成可执行的检查和配置，不要只给泛泛建议。
        我选择的 DSH profile 是 $profile；先核对它是否就是当前使用的 DSH，不确定先识别，不要安装到另一个 profile。
        先检查正在运行的 DSH、实际回环端口、插件是否已安装启用和 dsh CLI 可用性。缺少公共插件时执行：
        dsh plugin --profile $profile add @linxin666/dsh-remote-web-ui@latest
        已有插件则复用，不为连接重复升级。CLI 不在 PATH 时先定位匹配现有 DSH 的入口，不擅自升级 DSH。配置前备份受影响的插件设置，保留旧连接和授权。
        不得修改 cordis.patch.yml 中 webserver 的 host/port，保持回环与自动端口；不得卸载 App、清数据、修改 LAN 绑定、关闭认证或打断正在运行的任务。需要重启时先说明影响，等任务保存后处理。
        ${route.trimIndent()}
        能通过现有工具完成的步骤请直接执行；没有工具控制配置页时，准确指出需要我点击的选项，不冒充已执行。必须登录、授权或扫码时才让我完成该动作。
        记录每一步结果与失败原因。交付所选 profile、实际入口、配对位置、连通与实时通道验证结果；日志输出脱敏，不回显 token、Cookie、密钥或私人地址。给出日志导出和后续复查方法。
    """.trimIndent()
}

@Composable
private fun AiSetupCard(index: Int, profile: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(index, profile) { mutableStateOf(false) }
    val prompt = setupPrompt(index, profile)
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("让电脑端 AI 帮你配置", fontWeight = FontWeight.SemiBold)
            Text("复制后发给能操作被控端电脑的 AI。提示词已包含所选方案和 $profile。AI 有对应权限才能自动执行；登录、系统授权和扫码由你完成。", fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
            top.yukonga.miuix.kmp.basic.Button(onClick = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DSH 连接配置提示词", prompt))
                android.widget.Toast.makeText(context, "已复制完整配置提示词", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制 AI 配置提示词") }
            Text(if(expanded) "收起提示词" else "查看提示词", color = com.dsh.mobile.ui.theme.LocalDshAccents.current.accent,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 12.dp))
            if(expanded) SelectionContainer { Text(prompt, fontSize = 12.sp) }
        }
    }
}
