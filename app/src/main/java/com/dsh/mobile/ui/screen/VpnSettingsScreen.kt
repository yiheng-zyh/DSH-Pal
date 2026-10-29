package com.dsh.mobile.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.core.*
import com.dsh.mobile.vpn.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun VpnSettingsScreen(onBack:()->Unit,onSelectedServer:(DiscoveredServer)->Unit,
    settings:SettingsStore?=null,initialProvider:VpnProvider?=null,initialAddress:String="",
    onConnectRequested:((String,String,VpnProvider)->Unit)?=null) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val state by VpnController.state.collectAsState()
    val latestState by rememberUpdatedState(state)
    var provider by remember(initialProvider) { mutableStateOf(initialProvider ?: VpnController.preferred) }
    val saved=settings?.profiles.orEmpty().filter {it.kind==ConnectionProfile.Kind.MESH &&
        (it.meshProvider?.name==provider.name || (it.meshProvider==null && provider==VpnProvider.TAILSCALE && java.net.URI(it.baseUrl).host.endsWith(".ts.net")))}
    var address by remember(provider,initialAddress) { mutableStateOf(initialAddress.ifBlank {saved.maxByOrNull {it.lastUsedAt}?.baseUrl.orEmpty()}) }
    var pendingAddress by remember { mutableStateOf<String?>(null) }
    var waitingForTarget by remember { mutableStateOf(false) }
    var elapsed by remember { mutableStateOf(0) }
    var pendingProvider by remember { mutableStateOf(provider) }
    var launchAuth by remember { mutableStateOf(false) }
    var pendingAuthAttempt by remember {mutableStateOf<Long?>(null)}
    var scanner by remember { mutableStateOf(false) }
    var pairScanner by remember {mutableStateOf(false)}
    var help by remember {mutableStateOf(false)}
    var logout by remember { mutableStateOf(false) }
    // One automatic open per visit: the effect below fires on every tunnel state
    // change, and re-opening after the user backed out would trap them.
    var autoOpened by remember { mutableStateOf(false) }
    var switchTo by remember { mutableStateOf<VpnProvider?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoDiscover by remember { mutableStateOf(VpnController.autoDiscover) }
    val accent=com.dsh.mobile.ui.theme.LocalDshAccents.current.accent
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) { launchAuth=pendingProvider==VpnProvider.TAILSCALE;VpnController.connect(pendingProvider) }
        else { launchAuth=false;pendingAddress=null;waitingForTarget=false;error="VPN 授权已取消，原连接没有更改" }
    }
    fun connect(target:VpnProvider) {
        pendingAuthAttempt=null
        pendingProvider=target
        runCatching { VpnService.prepare(context) }.onSuccess { intent ->
            if(intent!=null)permissions.launch(intent) else {launchAuth=target==VpnProvider.TAILSCALE;VpnController.connect(target)}
        }.onFailure {error="系统阻止 VPN 授权，请检查现有 VPN 或设备策略"}
    }
    fun requestConnect() {
        error=null
        if(state.active!=null&&state.active!=provider)switchTo=provider else connect(provider)
    }
    fun openTarget(raw:String) {
        error=null
        val url=raw.trim().let {if("://" in it)it else "http://$it"}
        if(raw.isBlank()) {
            waitingForTarget=true
            connect(provider)
            return
        }
        // A pairing link minted by the desktop QR always points at the public
        // base (relay/tunnel), never at the tunnel host, because publicBaseUrl
        // decides the QR's origin. Opening that public host through the VPN
        // cannot authenticate: the plugin gate judges the request by origin, so
        // the cookie it plants belongs to the public host, not to this tunnel
        // (measured: tunnel up on tun1, every <host>.ts.net/pair-app load still
        // 401 because clearing app data dropped the cookie while the VPN login
        // survived). The plugin's token pool is origin-blind (pairing.accept
        // reads only the token table) and its accept page plants the cookie for
        // whichever host served it — so replaying the same token against the
        // tunnel entry pairs this origin, with no second QR scan and without
        // the desktop QR ever having to name ts.net.
        if(ConnectionAddress.origin(url)==null || !ConnectionAddress.allowed(url,ConnectionProfile.Kind.MESH)) {
            error="请填写电脑的 DSH 入口或完整配对链接";return
        }
        val token=MeshPairing.token(url)
        val scannedOrigin=ConnectionAddress.origin(url)!!
        val scannedIsMesh=ConnectionAddress.recognizedRemoteKind(scannedOrigin)==ConnectionProfile.Kind.MESH
        if(token!=null&&!scannedIsMesh) {
            val meshEntry=saved.maxByOrNull {it.lastUsedAt}?.baseUrl
            if(meshEntry.isNullOrBlank()) {
                error="这是公网配对码。先在上面“重新检测目标”得到 VPN 入口再重试；配对码可复用，不必重新扫码。"
                return
            }
            val rebuilt=MeshPairing.pairingUrlFor(url,meshEntry)
            if(rebuilt==null) {error="配对链接格式无法识别";return}
            RunLog.info("VpnPair","replaying pairing token on mesh origin; token excluded")
            pendingAddress=rebuilt
        } else pendingAddress=url
        if(state.active==provider&&state.up&&(provider!=VpnProvider.TAILSCALE||state.tailscaleStatus=="Running")) {
            val target=pendingAddress!!;pendingAddress=null;onConnectRequested?.invoke(target,saved.firstOrNull {it.baseUrl==ConnectionAddress.origin(target)}?.label ?: provider.title,provider)
        } else connect(provider)
    }
    LaunchedEffect(pendingAddress,waitingForTarget) {
        elapsed=0
        while(pendingAddress!=null||waitingForTarget) {
            kotlinx.coroutines.delay(1000);elapsed++
            if(elapsed>=90) {
                error="${if(!latestState.up) "VPN 尚未建立，请检查账号授权与网络" else "尚未找到电脑 DSH 入口，请确认电脑 Tailscale 在线且已配置 Serve"}。通道不会自动关闭，可重试或导出日志。"
                pendingAddress=null;waitingForTarget=false
            }
        }
    }
    // Tell "the tunnel has no data path" apart from "this is just slow".
    //
    // Waiting out the full timeout before saying anything is what made a DERP
    // mismatch look like a frozen app: the VPN showed 已建立, the counter climbed
    // past a minute, and nothing explained why. One quick HEAD decides it — the
    // far end either answers (any status) or it does not. Only runs for MESH while
    // a navigation is pending, and it never cancels that navigation: a slow link
    // that answers on the second try must still be allowed to finish.
    LaunchedEffect(state.up,state.active,pendingAddress) {
        if(!state.up||state.active!=provider||pendingAddress==null)return@LaunchedEffect
        kotlinx.coroutines.delay(8_000)
        if(pendingAddress==null)return@LaunchedEffect
        val entry=ConnectionAddress.origin(pendingAddress!!) ?: return@LaunchedEffect
        if(com.dsh.mobile.core.ConnectionProbe.tunnelCarriesData(entry))return@LaunchedEffect
        RunLog.warn("VPN","tunnel up but no data path to peer; relay-level problem suspected")
        // Do not stop the attempt; explain it. The route may still come through,
        // and the user can decide to switch instead of waiting blind.
        error="VPN 隧道已建立，但电脑方向没有数据回传（两端中继不一致时常见）。可以先切回公网连接使用，或稍后重试 VPN。"
    }
    LaunchedEffect(state.up,state.active,state.tailscaleStatus,pendingAddress,state.found,waitingForTarget) {
        if(state.active==provider&&state.up&&(provider!=VpnProvider.TAILSCALE||state.tailscaleStatus=="Running")) {
            if(waitingForTarget) {
                if(state.found.size==1) {address=state.found.single().origin;waitingForTarget=false;pendingAddress=address}
            } else pendingAddress?.let {url->pendingAddress=null;onConnectRequested?.invoke(url,saved.firstOrNull {it.baseUrl==ConnectionAddress.origin(url)}?.label ?: provider.title,provider)}
        }
    }
    // Once the tunnel is up, the route should open itself.
    //
    // The user's expectation is exactly right and the app was not meeting it:
    // "登录了 Tailscale 之后不应该就是直接开启 VPN 通道进去了吗，为什么还要填
    // 一个链接". Two different layers were being conflated. The tunnel is network
    // reachability; the plugin gate (gate.ts isLoopbackClient) only trusts a
    // loopback peer, so a request arriving through the tunnel is an outside
    // visitor until *this origin* holds a device cookie. Cookies are per-origin,
    // so a Cloudflare pairing does not authenticate the ts.net host.
    //
    // What that means for the UI: when the tunnel comes up and this origin is
    // already paired (cookie still valid), just open it — no prompt, no link.
    // When it is not paired, a pairing token saved from an earlier scan can be
    // replayed silently, because the plugin's token pool is origin-blind. Only
    // when both fail does the user have to act, and then the action is "scan the
    // code on the desktop", never "paste a URL".
    LaunchedEffect(state.up,state.active,state.tailscaleStatus) {
        if(!state.up||state.active!=provider)return@LaunchedEffect
        if(provider==VpnProvider.TAILSCALE&&state.tailscaleStatus!="Running")return@LaunchedEffect
        if(autoOpened||pendingAddress!=null||waitingForTarget)return@LaunchedEffect
        val entry=state.found.singleOrNull()?.origin
            ?: saved.maxByOrNull {it.lastUsedAt}?.baseUrl
            ?: return@LaunchedEffect
        // Record the discovered PC as a switchable route now, before any pairing
        // is attempted. Whether the page opens or 401s, this address is where the
        // tunnel leads, and the connection list must show it — otherwise a later
        // attempt to come back to the VPN has nothing to select.
        settings?.rememberConnection(entry, provider.title, ConnectionProfile.Kind.MESH,
            ConnectionProfile.MeshProvider.valueOf(provider.name))
        autoOpened=true
        // Reuse the token remembered from the last successful pairing, if any.
        // That is what makes "log in and it just connects" true: the token is
        // origin-blind, so this origin mints its own cookie without a new scan.
        val rebuilt=settings?.pairingUrlFor(entry)
            ?: settings?.pairingUrl?.takeIf {it.isNotBlank()}?.let {MeshPairing.pairingUrlFor(it,entry)}
        if(rebuilt!=null) {
            RunLog.info("VpnPair","auto pairing on tunnel connect; token excluded")
            pendingAddress=rebuilt
        } else {
            // No token to replay: open the origin directly. If a cookie from a
            // previous pairing is still valid this just works; if not, the shell
            // reports a pairing failure and offers the scan action.
            RunLog.info("VpnPair","auto open tunnel entry; no saved token")
            pendingAddress=entry
        }
    }
    val file=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null)scope.launch {
            runCatching {withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)!!.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);var n=input.read(buffer);while(n>=0){require(out.size()+n<=65536);out.write(buffer,0,n);n=input.read(buffer)};out.toString("UTF-8")
            }}}.onSuccess {VpnController.importWireGuard(it)}.onFailure {error="无法读取配置，请选择不超过 64 KB 的 WireGuard .conf 文件"}
        }
    }
    LaunchedEffect(state.authUrl,state.authAttempt,state.busy,launchAuth,state.up) {
        if(state.up&&state.tailscaleStatus=="Running")launchAuth=false
        if(launchAuth&&state.authUrl.isNotBlank()&&!state.busy&&
            (pendingAuthAttempt==null||pendingAuthAttempt==state.authAttempt)) {
            val uri=Uri.parse(state.authUrl)
            if(uri.scheme=="https"&&(uri.host=="tailscale.com"||uri.host?.endsWith(".tailscale.com")==true)) {
                runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,uri))}.onFailure {error="无法打开官方登录页，请检查浏览器"}
                launchAuth=false
            } else {error="授权地址不受支持，已阻止打开";launchAuth=false}
        }
    }
    BackHandler {onBack()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=840.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            com.dsh.mobile.ui.AppPageHeader(initialProvider?.title ?: "内置通道",onBack=onBack)
            Text("仅本 App 的流量进入内置通道。Android 的 VPN 通常互斥；启用时可能替换正在运行的其他 VPN。",fontSize=12.sp)
            if(initialProvider==null)FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) { VpnProvider.entries.forEach { item ->
                // Browsing the other provider's form must not change which
                // tunnel is restored for an already-working connection.
                Button(onClick={provider=item}) {Text("${if(provider==item) "● " else ""}${item.title}",fontSize=14.sp)}
            } }
            if(onConnectRequested!=null)Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("连接电脑 DSH",fontWeight=FontWeight.Medium)
                Text("登录、VPN 授权和打开 DSH 都在此完成。VPN 按连接方式启停：切到公网会关闭，切回组网会自动开启；返回桌面保持当前通道，划掉 DSH 则停止。",fontSize=13.sp)
                TextField(value=address,onValueChange={address=it;error=null},label="DSH 入口或配对链接",singleLine=true,modifier=Modifier.fillMaxWidth())
                // The main button does everything: log in, bring the tunnel up,
                // find the PC, pair and open. Nothing else should be required.
                Button(enabled=pendingAddress==null&&!waitingForTarget&&(provider!=VpnProvider.WIREGUARD||state.configured),onClick={openTarget(address)},modifier=Modifier.fillMaxWidth()) {
                    Text(if(pendingAddress!=null||waitingForTarget) "正在连接 · ${elapsed}秒" else if(provider==VpnProvider.TAILSCALE&&address.isBlank()) "登录并查找电脑" else "连接并打开 DSH")
                }
                // Everything below is a fallback, so it is labelled as such and
                // collapsed by default. The earlier build led with a link field
                // and two buttons, which read as "you must supply a URL" — the
                // opposite of what the user asked for, and confusing because the
                // only QR in this flow belongs to DSH, never to Tailscale.
                var showManual by remember { mutableStateOf(false) }
                Text(
                    if(showManual) "收起手动配对" else "连不上？手动配对（可选）",
                    color=accent,
                    fontSize=13.sp,
                    modifier=Modifier.clickable{showManual=!showManual}.padding(vertical=4.dp)
                )
                if(showManual) {
                    Text(
                        "这一步和 Tailscale 无关。它用在 DSH 从来没有配过这台设备时：电脑上打开 DSH 的远程控制面板，扫屏幕上那张二维码，或者把面板里的配对链接粘贴进来。配对过一次之后，以后登录 Tailscale 会自动沿用，不用再扫。",
                        fontSize=12.sp,
                        color=MiuixTheme.colorScheme.onBackgroundVariant
                    )
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={pairScanner=true}) {Text("扫描 DSH 配对码",fontSize=13.sp)}
                        Button(onClick={
                            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            address=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty();error=null
                        }) {Text("粘贴配对链接",fontSize=13.sp)}
                    }
                }
                saved.forEach {profile->Text("${if(ConnectionAddress.origin(address)==profile.baseUrl) "●" else "○"} ${profile.label}",color=accent,modifier=Modifier.clickable(enabled=pendingAddress==null&&!waitingForTarget){address=profile.baseUrl}.padding(vertical=8.dp))}
                Text(when {
                    provider==VpnProvider.WIREGUARD&&!state.configured -> "当前停在：缺少 WireGuard 服务端配置。请先在本页导入配置。"
                    state.active==provider&&state.up -> "VPN 接口已建立；下一步检查电脑入口、配对与页面。"
                    state.tailscaleStatus in setOf("NeedsLogin","NeedsMachineAuth")&&provider==VpnProvider.TAILSCALE -> "当前停在：账号登录或设备授权，请完成官方页面提示后返回。"
                    pendingAddress!=null||waitingForTarget -> "当前停在：启动 VPN / 等待网络；账号与配置会保留。"
                    else -> "选择已保存电脑或填写入口，然后点一次连接。"
                },fontSize=13.sp,color=accent)
                if(error!=null)Text(error!!,fontSize=13.sp,color=com.dsh.mobile.ui.theme.LocalDshAccents.current.error)
                if(pendingAddress!=null||waitingForTarget)Button(onClick={pendingAddress=null;waitingForTarget=false}) {Text("取消等待（保持通道）")}
            }}
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(if(provider==VpnProvider.TAILSCALE) "Tailscale 登录与连接" else "WireGuard 配置与连接",fontWeight=FontWeight.Medium)
                Text(if(provider==VpnProvider.TAILSCALE) "内核状态：${state.tailscaleStatus}\nVPN：${if(state.active==provider&&state.up) "已建立" else "未建立"}" else "配置：${if(state.configured) "已加密保存" else "尚未导入"}\nVPN：${if(state.active==provider&&state.up) "已启用" else "未启用"}",fontSize=14.sp)
                Text(state.message,fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                if(state.busy||state.scanning)Text(if(state.scanning) "正在检测 ${state.scanned}/${state.total}…" else "正在处理，请稍候…",color=accent,fontSize=13.sp)
                if(provider==VpnProvider.TAILSCALE) {
                    if(state.tailscaleStatus!="Running")Button(enabled=!state.busy,onClick={
                        pendingAddress=null;waitingForTarget=false;error=null;launchAuth=false
                        pendingAuthAttempt=VpnController.login()
                        launchAuth=pendingAuthAttempt!=null
                    }) {Text("登录 / 重新打开授权页")}
                    Button(enabled=!state.busy,onClick={VpnController.inspectTailscale()}) {Text("读取登录状态")}
                    if(onConnectRequested==null)Button(enabled=!state.busy,onClick={requestConnect()}) {Text("授权、登录并连接")}
                    Text("“读取登录状态”只检查状态，不打开浏览器。未登录或授权超时，请点“登录 / 重新打开授权页”获取新的官方授权页面；返回后再连接电脑。",fontSize=12.sp)
                    Text("退出 Tailscale 账号",color=accent,modifier=Modifier.clickable {logout=true}.padding(vertical=8.dp))
                } else {
                    Button(onClick={file.launch(arrayOf("text/*","application/octet-stream"))}) {Text("导入 WireGuard 配置文件")}
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(onClick={
                            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val text=clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            VpnController.importWireGuard(text)
                        }) {Text("粘贴配置")}
                        Button(onClick={scanner=true}) {Text("扫描配置码")}
                    }
                    if(onConnectRequested==null)Button(enabled=state.configured&&!state.busy,onClick={requestConnect()}) {Text("授权并启用 WireGuard")}
                    Text("请使用网络管理员提供的 .conf 或配置二维码。导入不会自动连接；私钥不进入日志，应用范围会限制为 DSH Pal。",fontSize=12.sp)
                    if(state.active==provider)Text("接收 ${state.rx} B · 发送 ${state.tx} B · ${if(state.handshakeAt>0) "已有 Peer 握手" else "尚无 Peer 握手"}",fontSize=12.sp)
                }
                if(state.active!=null)Button(onClick={VpnController.disconnect()}) {Text("断开 ${state.active!!.title}")}
                if(error!=null)Text(error!!,color=com.dsh.mobile.ui.theme.LocalDshAccents.current.error,fontSize=13.sp)
            } }
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("目标服务器",fontWeight=FontWeight.Medium)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end=12.dp)){Text("自动发现 Tailscale 目标",fontSize=14.sp);Text("仅检测账号中在线设备，不扫描局域网",fontSize=12.sp)}
                    Switch(checked=autoDiscover,onCheckedChange={autoDiscover=it;VpnController.autoDiscover=it})
                }
                Button(enabled=!state.scanning,onClick={VpnController.discover()}) {Text("重新检测目标")}
                Text(if(provider==VpnProvider.TAILSCALE) "电脑需已加入同一 Tailscale 网络，并用 Serve 或已有桥接公开 DSH。自动发现不会绕过首次配对。" else "WireGuard 没有设备目录；这里检测已保存的 MESH 地址，也可返回配对页手动填写 DSH 的隧道内入口。",fontSize=12.sp)
                if(state.found.isEmpty())Text("尚未发现可识别的 DSH 入口；请先按帮助配置电脑端。",fontSize=13.sp)
                state.found.forEach { server -> Card(Modifier.fillMaxWidth().clickable {if(onConnectRequested!=null){address=server.origin;openTarget(server.origin)}else onSelectedServer(server)}) {Column(Modifier.padding(12.dp)) {
                    Text(server.name,fontWeight=FontWeight.Medium);SelectionContainer {Text(server.origin,fontSize=12.sp)};Text("使用此入口 ›",color=accent,fontSize=13.sp)
                }} }
            } }
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp)) {
                Text("连接诊断与日志",fontWeight=FontWeight.Medium)
                Text("通道已建立不代表 DSH 业务可用，请结合顶部状态及入口检测。登录、VPN、发现、配对是独立阶段。",fontSize=12.sp,modifier=Modifier.padding(vertical=8.dp))
                Button(onClick={com.dsh.mobile.ui.shareDiagnostics(context,scope){error=it}}){Text("导出问题日志")}
                Button(onClick={help=true}) {Text("电脑与手机配置帮助")}
            } }
        }
        if(scanner)QrScannerSheet(onScanned={scanner=false;VpnController.importWireGuard(it)},onCancel={scanner=false})
        if(pairScanner)QrScannerSheet(onScanned={pairScanner=false;address=it;openTarget(it)},onCancel={pairScanner=false})
        if(help)ConnectionHelpScreen(initialKind=ConnectionProfile.Kind.MESH,onBack={help=false})
        com.dsh.mobile.ui.ConfirmationDialog(show=logout,title="退出 Tailscale？",message="将断开本 App 的 Tailscale 通道并退出账号；保留 DSH 连接记录。",confirmText="退出账号",cancelText="取消",onDismiss={logout=false},onConfirm={logout=false;pendingAddress=null;waitingForTarget=false;launchAuth=false;pendingAuthAttempt=null;error=null;VpnController.logout()})
        com.dsh.mobile.ui.ConfirmationDialog(show=switchTo!=null,title="切换内置 VPN？",message="将断开当前内置通道，保留账号和配置，再启用所选通道。",confirmText="切换",cancelText="取消",onDismiss={switchTo=null},onConfirm={val target=switchTo;switchTo=null;if(target!=null)connect(target)})
    }
}
