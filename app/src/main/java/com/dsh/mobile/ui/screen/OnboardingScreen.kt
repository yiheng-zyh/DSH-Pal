package com.dsh.mobile.ui.screen

import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.core.*
import com.dsh.mobile.ui.motion.rememberTimeline
import com.dsh.mobile.ui.theme.LocalDshAccents
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Method choice first; a form is only shown after a deliberate choice. */
@Composable
fun OnboardingScreen(settings: SettingsStore, onPaired: (String) -> Unit, onCancel: (() -> Unit)? = null,
                     onConnectionRequested: ((String,String,ConnectionProfile.Kind,ConnectionProfile.MeshProvider?)->Unit)? = null) {
    var selected by remember { mutableStateOf<ConnectionProfile.Kind?>(null) }
    var selectedProvider by remember { mutableStateOf(com.dsh.mobile.vpn.VpnProvider.TAILSCALE) }
    var input by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var routeNotice by remember {mutableStateOf<String?>(null)}
    var scanner by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var vpn by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var autoConnect by remember {mutableStateOf(settings.autoConnectOnLaunch)}
    var autoReconnect by remember {mutableStateOf(settings.autoReconnect)}
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var probeTicket by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colorScheme
    val accent = LocalDshAccents.current.accent
    val timeline = rememberTimeline(1100)
    val markIn = timeline.at(0, 420)
    val actionIn = timeline.at(180, 720)
    val guideIn = timeline.at(420, 1000)
    fun connect(raw: String) {
        val kind = selected ?: return
        val candidate = raw.trim().let { if (kind in setOf(ConnectionProfile.Kind.LAN, ConnectionProfile.Kind.MESH) && "://" !in it) "http://$it" else it }
        runCatching {
            check(kind != ConnectionProfile.Kind.USB || BuildConfig.USB_DEVELOPMENT)
            ConnectionProfile.create(label,kind,candidate)
        }.onSuccess {
            if(it.kind!=kind) {
                input=candidate;selected=it.kind
                if(it.kind==ConnectionProfile.Kind.MESH)selectedProvider=com.dsh.mobile.vpn.VpnProvider.TAILSCALE
                routeNotice="这个地址实际属于「${it.kind.title}」，已切换到对应页面。请确认后连接，现有连接尚未更改。"
                return
            }
            if(onConnectionRequested!=null) onConnectionRequested(candidate,label,it.kind,null)
            else { settings.saveConnection(candidate,label,it.kind);onPaired(candidate) }
        }.onFailure {
            error = if (kind == ConnectionProfile.Kind.LAN) "请填写有效的 Wi-Fi 直连私有地址（10.x、172.16–31.x、192.168.x）" else "请检查链接是否属于所选连接方式，并保留完整配对参数"
        }
    }
    BackHandler(enabled = scanner) {scanner=false}
    BackHandler(enabled = (selected != null || onCancel != null) && !help && !scanner) { if(selected!=null){selected=null;error=null}else onCancel?.invoke() }
    Box(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.graphicsLayer { alpha = markIn }) {
                com.dsh.mobile.ui.DshBrandMark(52.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (selected == null) "连接 DSH" else selected!!.title, fontSize = if(selected==ConnectionProfile.Kind.MESH||selected?.publicEntry==true) 18.sp else 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (selected == null) "选择适合你的连接方式" else "首次使用这个地址可能需要配对", fontSize = 12.sp, color = colors.onBackgroundVariant)
                }
                if (selected != null || onCancel != null) Text("返回", color = accent, modifier = Modifier.clickable {
                    if (selected != null) { selected = null; error = null } else onCancel?.invoke()
                }.padding(10.dp))
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = actionIn; translationY = (1f-actionIn)*20f }) {
                if (selected == null) {
                    // Relay leads: it carries the recommendation and is the route the
                    // desktop panel's default QR actually points at, so the first card
                    // is the one a new user should tap.
                    listOf(ConnectionProfile.Kind.RELAY to null, ConnectionProfile.Kind.TUNNEL to null, ConnectionProfile.Kind.CUSTOM to null, ConnectionProfile.Kind.MESH to com.dsh.mobile.vpn.VpnProvider.TAILSCALE, ConnectionProfile.Kind.MESH to com.dsh.mobile.vpn.VpnProvider.WIREGUARD, ConnectionProfile.Kind.LAN to null, ConnectionProfile.Kind.USB to null).forEach { (kind,provider) ->
                        val entries=settings.profiles.filter {p->p.kind==kind && (provider==null || p.meshProvider?.name==provider.name ||
                            (p.meshProvider==null && provider==com.dsh.mobile.vpn.VpnProvider.TAILSCALE && java.net.URI(p.baseUrl).host.endsWith(".ts.net")))}
                        val available = kind != ConnectionProfile.Kind.USB || BuildConfig.USB_DEVELOPMENT
                        Card(Modifier.fillMaxWidth().padding(bottom=12.dp).clickable(enabled=available) {
                            val previous=entries.maxByOrNull {it.lastUsedAt}
                            selected = kind; input = previous?.baseUrl ?: if(kind == ConnectionProfile.Kind.USB) "http://dsh-usb.localhost:18765" else ""; label=previous?.label.orEmpty();routeNotice=null; error = null; probeTicket++; probe=null; probing=false
                            if(provider!=null)selectedProvider=provider
                        }) {
                            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    // Relay carries the recommendation because the desktop
                                    // plugin's default QR issues a dsh-market.com address:
                                    // that is what a new user actually has in front of them,
                                    // and marking the other route "推荐" sent them looking for
                                    // an address their panel never printed.
                                    Text(provider?.title ?: if(kind == ConnectionProfile.Kind.RELAY) "${kind.title}（推荐）" else kind.title, fontSize=17.sp, fontWeight=FontWeight.Medium, color=if(available) colors.onBackground else colors.onBackgroundVariant)
                                    Spacer(Modifier.height(6.dp))
                                    Text(when(kind) {
                                        ConnectionProfile.Kind.TUNNEL -> "直达 Cloudflare 隧道；Quick 临时地址 / Named 自有域名"
                                        ConnectionProfile.Kind.RELAY -> "扫码最直接：电脑面板的二维码通常就是这一项，固定域名"
                                        ConnectionProfile.Kind.CUSTOM -> "已有 frp 或其他 HTTPS 反向代理，按实际部署填写"
                                        ConnectionProfile.Kind.MESH -> if(provider==com.dsh.mobile.vpn.VpnProvider.TAILSCALE) "登录、授权、选择电脑并连接，在同一页完成" else "导入服务端配置、查看握手并连接，在同一页完成"
                                        ConnectionProfile.Kind.LAN -> "同一 Wi-Fi，使用已部署的电脑访问入口"
                                        ConnectionProfile.Kind.USB -> if(available) "开发调试，资源通过数据线传输" else "需要 USB 开发版与电脑 ADB"
                                    }, fontSize=13.sp, color=colors.onBackgroundVariant)
                                    val count=entries.size
                                    if(count>0)Text("已保存 $count 个入口${if(entries.any {it.id==settings.activeProfileId}) " · 当前使用" else ""}",fontSize=12.sp,color=accent,modifier=Modifier.padding(top=6.dp))
                                }
                                if (available) Text("›", fontSize=25.sp, color=accent)
                            }
                        }
                    }
                    val saved = settings.profiles
                    if (saved.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("已保存的连接", fontWeight=FontWeight.Medium)
                        saved.forEach { profile ->
                            Card(Modifier.fillMaxWidth().padding(top=8.dp).clickable {
                                if(profile.kind==ConnectionProfile.Kind.MESH) {
                                    selectedProvider=runCatching {com.dsh.mobile.vpn.VpnProvider.valueOf(profile.meshProvider?.name.orEmpty())}.getOrDefault(com.dsh.mobile.vpn.VpnProvider.TAILSCALE)
                                    input=profile.baseUrl;label=profile.label;selected=profile.kind
                                }
                                else if(onConnectionRequested!=null)onConnectionRequested(profile.baseUrl,profile.label,profile.kind,profile.meshProvider)
                                else if(settings.activateProfile(profile.id)) onPaired(profile.baseUrl)
                            }) { Text("${profile.label} · ${profile.kind.title}", modifier=Modifier.padding(16.dp)) }
                        }
                    }
                } else {
                    routeNotice?.let {Text(it,fontSize=13.sp,color=accent,modifier=Modifier.padding(bottom=12.dp))}
                    if (selected != ConnectionProfile.Kind.MESH) {
                        Card(Modifier.fillMaxWidth().padding(bottom=14.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(when(selected) {
                                    ConnectionProfile.Kind.RELAY -> "1 · 扫电脑面板上的二维码（推荐）"
                                    ConnectionProfile.Kind.TUNNEL -> "1 · 获取 Cloudflare Tunnel 配对链接"
                                    ConnectionProfile.Kind.CUSTOM -> "1 · 确认自有 HTTPS 转发入口"
                                    ConnectionProfile.Kind.LAN -> "1 · 确认 Wi-Fi 与现有访问入口"
                                    else -> "1 · 在电脑准备 USB 通道"
                                }, fontWeight=FontWeight.Medium)
                                Text(when(selected) {
                                    ConnectionProfile.Kind.RELAY -> "电脑装了 DSH 远程访问插件后，面板里那张二维码的地址通常就是这一项（以 dsh-market.com 结尾）。\n下一步：点下面的按钮扫码即可，不用改电脑配置。地址固定，以后重开 App 不用重新扫。"
                                    ConnectionProfile.Kind.TUNNEL -> "与 Relay 共用 DSH 远控插件。Quick 地址通常以 trycloudflare.com 结尾，重启可能变化；Named 使用你已配置的自有域名。请使用电脑为 Tunnel 入口生成的配对链接。默认二维码可能仍是 Relay，App 会识别提示，不能只改二维码域名。"
                                    ConnectionProfile.Kind.CUSTOM -> "用于已部署的 frp 或其他 HTTPS 反向代理。自有域名无法仅凭名字判断后端，请按电脑实际方案选择。转发需支持 WebSocket 并保留 DSH 配对认证；App 不改电脑绑定或插件配置。"
                                    ConnectionProfile.Kind.LAN -> "手机与电脑接入同一 Wi-Fi，填写已经部署的转发入口和真实端口。App 不修改电脑绑定；只知道电脑 IP 还不足以连接。"
                                    else -> "连接数据线并授权 USB 调试，在电脑项目目录运行 USB 助手。通道准备好后，下方地址已自动填好。"
                                }, fontSize=13.sp, color=colors.onBackgroundVariant, modifier=Modifier.padding(top=8.dp))
                                Text("安装命令 · AI 自动配置指引", color=accent, modifier=Modifier.clickable { help=true }.padding(top=12.dp))
                            }
                        }
                    }
                    if (selected == ConnectionProfile.Kind.MESH) {
                        Card(Modifier.fillMaxWidth().padding(bottom=14.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("1 · 启用内置通道", fontWeight=FontWeight.Medium)
                                Text("Tailscale：授权 VPN 并登录，自动发现电脑。WireGuard：导入管理员提供的配置后启用。电脑端仍需配置 DSH 入口并完成首次配对。", fontSize=13.sp, color=colors.onBackgroundVariant, modifier=Modifier.padding(vertical=8.dp))
                                Button(onClick={vpn=true}) {Text("配置 Tailscale / WireGuard", fontSize=13.sp)}
                                Text("安装命令与电脑配置帮助",color=accent,modifier=Modifier.clickable{help=true}.padding(top=10.dp))
                            }
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("扫描配对二维码", fontSize=17.sp, fontWeight=FontWeight.Medium)
                            Text("使用 DSH「远程访问」为这个入口生成的二维码", fontSize=13.sp, color=colors.onBackgroundVariant, modifier=Modifier.padding(vertical=8.dp))
                            Button(onClick={ scanner=true }, modifier=Modifier.fillMaxWidth()) { Text("打开相机扫码") }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text(if(selected == ConnectionProfile.Kind.MESH) "2 · 填写电脑 DSH 的入口" else "或填写连接地址", fontWeight=FontWeight.Medium)
                            Spacer(Modifier.height(14.dp))
                            TextField(value=input, onValueChange={ input=it; error=null; probeTicket++; probe=null; probing=false }, modifier=Modifier.fillMaxWidth(), singleLine=true,
                                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled=false),
                                label=when(selected) {
                                    ConnectionProfile.Kind.LAN -> "Wi-Fi 入口或配对链接"
                                    ConnectionProfile.Kind.MESH -> "DSH 地址或配对链接"
                                    ConnectionProfile.Kind.USB -> "USB 入口地址"
                                    else -> "HTTPS 地址或配对链接"
                                })
                            Spacer(Modifier.height(10.dp))
                            TextField(value=label, onValueChange={ label=it }, label="连接名称（可选）", modifier=Modifier.fillMaxWidth(), singleLine=true)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), modifier=Modifier.padding(top=12.dp)) {
                                Button(onClick={
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    input = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    error=null; probe=null; probeTicket++; probing=false
                                }) { Text("粘贴链接") }
                                Button(enabled=!probing, onClick={
                                    val raw=input.trim().let { if("://" !in it && selected in setOf(ConnectionProfile.Kind.LAN,ConnectionProfile.Kind.MESH)) "http://$it" else it }
                                    val target=runCatching { ConnectionProfile.create("检测", selected!!, raw) }.getOrNull()
                                    if(target==null) error="请先填写该连接方式的有效地址" else {
                                        probing=true; probe=null
                                        val ticket=++probeTicket
                                        scope.launch { try { val result=ConnectionProbe.check(target); if(ticket==probeTicket) probe=result } finally { if(ticket==probeTicket) probing=false } }
                                    }
                                }) { Text(if(probing) "检测中…" else "检测地址") }
                            }
                            if(probing) Text("正在检查入口（最多约 5 秒）…", fontSize=12.sp, modifier=Modifier.padding(top=8.dp))
                            probe?.let { result -> Text("${result.outcome.message} · ${result.elapsedMs} ms${result.status?.let { " · HTTP $it" }.orEmpty()}", fontSize=12.sp, modifier=Modifier.padding(top=8.dp), color=colors.onBackgroundVariant) }
                            if(probe != null) Text("检测用于排查网络，实际配对状态以连接页面为准。", fontSize=11.sp, color=colors.onBackgroundVariant)
                            if (error != null) Text(error!!, color=LocalDshAccents.current.error, fontSize=13.sp, modifier=Modifier.padding(top=10.dp))
                            Spacer(Modifier.height(16.dp))
                            Button(onClick={ connect(input) }, modifier=Modifier.fillMaxWidth()) { Text("保存并连接") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.graphicsLayer { alpha=guideIn; translationY=(1f-guideIn)*12f }) {
                if(selected==null)Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Text("启动时连接上次入口",modifier=Modifier.weight(1f));Switch(checked=autoConnect,onCheckedChange={autoConnect=it;settings.autoConnectOnLaunch=it})}
                    Row(verticalAlignment=Alignment.CenterVertically) {Text("网络恢复后自动重连",modifier=Modifier.weight(1f));Switch(checked=autoReconnect,onCheckedChange={autoReconnect=it;settings.autoReconnect=it})}
                    Text("已开启的 Tailscale 会保持运行；切换到公网、取消等待或关闭本页均不会自动断开。",fontSize=12.sp,color=colors.onBackgroundVariant)
                }}
                Text("连接帮助 · 优缺点与两端配置", color=accent, modifier=Modifier.clickable { help=true }.padding(vertical=14.dp))
                Text("导出问题日志", color=accent, modifier=Modifier.clickable {
                    scope.launch { runCatching {
                        val file=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RunLog.export(context) }
                        val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                        val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(android.content.Intent.createChooser(intent,"导出连接诊断"))
                    }.onFailure { error="日志导出失败，请稍后重试"; RunLog.error("Export","failed ${it.javaClass.simpleName}") } }
                }.padding(bottom=14.dp))
                Text("二维码是配对方式，里面的实际地址决定通道。Cloudflare Tunnel、Relay 与其他 HTTPS 分开保存；App 不会为了切换而改写电脑入口。", fontSize=12.sp, color=colors.onBackgroundVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (scanner) QrScannerSheet(onScanned={ raw -> scanner=false; connect(raw) }, onCancel={ scanner=false })
        if (selected==ConnectionProfile.Kind.MESH) VpnSettingsScreen(
            onBack={selected=null},onSelectedServer={},settings=settings,initialProvider=selectedProvider,initialAddress=input,
            onConnectRequested={url,name,provider->
                if(onConnectionRequested!=null)onConnectionRequested(url,name,ConnectionProfile.Kind.MESH,ConnectionProfile.MeshProvider.valueOf(provider.name))
                else {settings.saveConnection(url,name,ConnectionProfile.Kind.MESH,ConnectionProfile.MeshProvider.valueOf(provider.name));onPaired(url)}
            })
        if (help) ConnectionHelpScreen(initialKind=selected, onBack={ help=false })
    }
}
