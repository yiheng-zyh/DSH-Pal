package com.dsh.mobile.ui.screen

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.dsh.mobile.ui.motion.rememberTimeline
import com.dsh.mobile.ui.motion.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.dsh.mobile.ui.AppPageHeader
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.island.IslandSupport
import com.dsh.mobile.island.IslandNotificationBuilder
import com.dsh.mobile.core.TaskState
import com.dsh.mobile.ui.motion.AnimatedScaleFade
import com.dsh.mobile.ui.motion.AnimatedSlideFade
import com.dsh.mobile.ui.theme.LocalDshAccents
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import android.content.Context
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.ui.motion.pressClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween

private enum class SettingsPage(val title: String) {
    Home("设置"), Connection("设备与连接"), Appearance("外观"), Notifications("通知与后台"), Cache("缓存与加载"), Diagnostics("支持与诊断"), Logs("日志记录"), About("关于"), NotificationControls("通知选项"), NotificationSupport("通知能力检测"), NotificationDemo("通知演示")
}

/**
 * Diagnostics + settings.
 *
 * The island is the kind of feature where "it didn't show up" has five possible
 * causes (ROM lacks it, protocol too old, focus permission off, notification
 * permission off, device is not Xiaomi). Rather than have users guess, this
 * screen runs the official probes and prints the verdict, plus offers a test
 * island so the whole path can be exercised in one tap.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    webView: android.webkit.WebView? = null,
    onBack: () -> Unit,
    onReloadPage: () -> Unit = {},
    onAddConnection: () -> Unit = {},
    onSwitchConnection: (String) -> Unit = {},
    onConnectAddress: (String, String) -> Unit = { _, _ -> },
    onConnectMesh: (String, String, com.dsh.mobile.core.ConnectionProfile.MeshProvider) -> Unit = { _, _, _ -> },
    onAppearanceChanged: (com.dsh.mobile.core.LocalAppearance) -> Unit = {},
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SettingsPage.Home) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var showConnectionHelp by remember { mutableStateOf(false) }
    var showVpn by remember { mutableStateOf(false) }
    var selectedAppearance by remember { mutableStateOf(settings.localAppearance) }
    fun parent() = when(page) {
        SettingsPage.NotificationControls,SettingsPage.NotificationSupport,SettingsPage.NotificationDemo -> SettingsPage.Notifications
        SettingsPage.Logs -> SettingsPage.Diagnostics
        else -> SettingsPage.Home
    }
    BackHandler { if(page==SettingsPage.Home)onBack() else page = parent() }
    var capability by remember { mutableStateOf<IslandSupport.Capability?>(null) }
    var probing by remember { mutableStateOf(false) }
    // Whether the OS actually accepted our last notification. Posting succeeds
    // silently even when the app's notification importance is NONE, so the only
    // honest signal is asking NotificationManager whether the record exists.
    var lastPostResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val entrance = rememberTimeline(640)
    fun cardMotion(index: Int) = Modifier.fillMaxWidth().graphicsLayer {
        val progress = entrance.at(index * 40, index * 40 + 340)
        alpha = progress
        translationY = (1f - progress) * 24.dp.toPx()
    }

    // Probe once on entry, off the main thread (it IPCs into SystemUI).
    androidx.compose.runtime.LaunchedEffect(page) {
        if (page == SettingsPage.NotificationSupport) capability = withContext(Dispatchers.IO) { IslandSupport.probe(context) }
    }

    if(page==SettingsPage.Cache){com.dsh.mobile.ui.BackGestureSurface {CacheManagementScreen(settings,webView,{page=SettingsPage.Home},onReloadPage)};return}
    if(page==SettingsPage.Logs){com.dsh.mobile.ui.BackGestureSurface {LogSettingsScreen{page=SettingsPage.Diagnostics}};return}
    if(page==SettingsPage.About){com.dsh.mobile.ui.BackGestureSurface {AboutScreen{page=SettingsPage.Home}};return}
    Box(
        Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 16 }).togetherWith(fadeOut(tween(120))) },
            label = "settings-page",
        ) { section ->
        Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max=840.dp).fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
                AppPageHeader(section.title,if(section==SettingsPage.Home) "DSH Pal ${com.dsh.mobile.BuildConfig.VERSION_NAME}" else null) { if(section==SettingsPage.Home)onBack()else page=parent() }
            }
        Column(Modifier.weight(1f).widthIn(max=840.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp)) {
            if (section == SettingsPage.Home) {
                SettingsLink("连接管理", "查看当前连接 · 切换 VPN / 公网 / USB") { page = SettingsPage.Connection }
                SettingsLink("连接帮助", "Cloudflare、Relay、自有 HTTPS 与 VPN 两端配置") { showConnectionHelp = true }
                SettingsLink("外观", "跟随 DSH 背景，或选择手机本地色调") { page = SettingsPage.Appearance }
                SettingsLink("通知与后台", "灵动岛、任务进度与后台连接") { page = SettingsPage.Notifications }
                SettingsLink("缓存与加载", "自动更新与本地资源复用") { page = SettingsPage.Cache }
                Spacer(Modifier.height(14.dp))
                SettingsLink("支持与诊断", "分类日志与问题记录") { page = SettingsPage.Diagnostics }
                SettingsLink("关于", "版本、开源许可与项目链接") { page = SettingsPage.About }
            }

            if(section==SettingsPage.Notifications) {
                SettingsLink("通知选项","灵动岛、任务进度、后台连接与震动"){page=SettingsPage.NotificationControls}
                SettingsLink("通知能力检测","查看系统支持与通知权限"){page=SettingsPage.NotificationSupport}
                SettingsLink("通知演示","验证进度通知和媒体岛"){page=SettingsPage.NotificationDemo}
            }
            if (section == SettingsPage.Appearance) {
                Text("默认沿用 DSH 自带的主题或背景；不要求安装壁纸插件。本地色调仅影响手机，不会修改被控端。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(16.dp))
                if(android.os.Build.VERSION.SDK_INT>=34) {
                    ToggleRow("预测性返回", "页面与设置随返回手势预览；回滑取消。退出时仍需再次返回。", settings.predictiveBackEnabled) {
                        settings.predictiveBackEnabled=it
                    }
                } else Text("预测性返回预览需要 Android 14 或以上；普通返回不受影响。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(12.dp))
                Text("网页布局",fontWeight=FontWeight.Medium)
                var layout by remember {mutableStateOf(settings.webLayoutMode)}
                com.dsh.mobile.core.WebLayoutMode.entries.forEach {mode ->
                    SettingsLink(mode.title + if(mode==layout) " · 当前" else "",mode.detail) {
                        layout=mode;settings.webLayoutMode=mode
                        webView?.evaluateJavascript(com.dsh.mobile.ui.web.WebViewport.script(),null)
                    }
                }
                Text("仅改变手机显示方式，保留连接与登录。桌面布局内容较密，建议横屏或平板；可随时切回。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.height(18.dp))
                Text("本地色调",fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                com.dsh.mobile.core.LocalAppearance.entries.forEach { theme ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).pressClickable {
                        selectedAppearance = theme; onAppearanceChanged(theme)
                    }) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(theme.background))))
                            Spacer(Modifier.width(14.dp))
                            Text(theme.title, modifier = Modifier.weight(1f))
                            if (selectedAppearance == theme) Text("当前", color = LocalDshAccents.current.accent)
                        }
                    }
                }
            }
            // ---- notification capability -----------------------------------
            if (section == SettingsPage.NotificationSupport) {
            Card(cardMotion(0)) {
                Column(Modifier.padding(16.dp)) {
                    Text("通知与灵动岛", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))

                    val cap = capability
                    if (cap == null) {
                        Text("正在探测…", color = MiuixTheme.colorScheme.onBackgroundVariant)
                    } else {
                        StatusRow(
                            label = "设备",
                            value = if (cap.isXiaomiDevice) "小米 / Redmi / POCO" else "非小米设备",
                            ok = cap.isXiaomiDevice,
                        )
                        StatusRow(
                            label = "岛支持",
                            value = when (cap.level) {
                                IslandSupport.Level.SuperIsland -> "支持（OS3 超级岛）"
                                IslandSupport.Level.FocusV2 -> "仅焦点通知（OS2）"
                                IslandSupport.Level.FocusV1 -> "仅焦点通知（OS1）"
                                IslandSupport.Level.None -> "不支持"
                            },
                            ok = cap.level == IslandSupport.Level.SuperIsland,
                        )
                        StatusRow(
                            label = "协议版本",
                            value = cap.protocolVersion.toString(),
                            ok = cap.protocolVersion >= 3,
                        )
                        StatusRow(
                            label = "通知权限",
                            value = if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) "已开启" else "未开启",
                            ok = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
                        )

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "任务通知使用 Android 标准通道；小米灵动岛使用媒体通道，显示效果由系统决定。无需焦点通知权限。",
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Spacer(Modifier.height(10.dp))

                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    if (probing) return@Button
                                    probing = true
                                    // Re-probe on a background thread.
                                    scope.launch {
                                        try { capability = withContext(Dispatchers.IO) { IslandSupport.probe(context) } }
                                        finally { probing = false }
                                    }
                                },
                            ) { Text(if (probing) "探测中…" else "重新探测") }

                            Button(
                                onClick = {
                                    val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    runCatching { context.startActivity(intent) }
                                },
                            ) { Text("打开权限设置") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            }
            if(section==SettingsPage.NotificationDemo) {
            // ---- test the island end to end --------------------------------
            Card(cardMotion(1)) {
                Column(Modifier.padding(16.dp)) {
                    Text("跑一遍岛演示", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "会发一条边跑边更新的演示任务：进度条每 1.2 秒前进一格，工具名跟着变。" +
                            "按 Home 回桌面看状态栏/灵动岛。若岛没出现，先看上面的能力结论。",
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    var demoRunning by remember { mutableStateOf(false) }

                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                demoRunning = true
                                com.dsh.mobile.island.IslandDemo.start(
                                    context = context,
                                    scope = scope,
                                ) { verdict ->
                                    lastPostResult = verdict
                                }
                            },
                        ) { Text(if (demoRunning) "重新演示" else "开始演示") }

                        Button(
                            onClick = {
                                com.dsh.mobile.island.IslandDemo.stop(context)
                                demoRunning = false
                                lastPostResult = "演示已停止"
                            },
                        ) { Text("停止") }
                    }

                    // A single static post is kept for comparison: it explains the
                    // "stuck at one percentage" symptom if the user sees it again.
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            lastPostResult = com.dsh.mobile.island.postDebugIsland(context)
                        },
                    ) { Text("只发一条静态岛") }

                    val result = lastPostResult
                    AnimatedSlideFade(visible = result != null) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = result.orEmpty(),
                                color = if (result?.startsWith("已发出") == true) {
                                    LocalDshAccents.current.success
                                } else {
                                    LocalDshAccents.current.error
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- feature toggles -------------------------------------------
            }
            if (section == SettingsPage.NotificationControls) {
            Card(cardMotion(2)) {
                Column(Modifier.padding(16.dp)) {
                    Text("显示选项", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))

                    ToggleRow(
                        title = "灵动岛（默认关闭）",
                        subtitle = "通过音乐通道近似显示，不是完全体灵动岛",
                        checked = settings.islandEnabled,
                        onChange = { settings.islandEnabled = it },
                    )
                    // Write the limitation down where the switch is, not only in a
                    // changelog: this app cannot render a true island. Xiaomi's focus
                    // notification protocol is closed to third-party signatures
                    // (FocusPlugin returns onAuthFailed), so the only available route
                    // is the MediaStyle music channel. Users who enable it will see a
                    // media-style card with playback-shaped controls, and they need
                    // to know that before they think the feature is broken.
                    Text(
                        "为什么不是完全体：小米的焦点通知通道需要企业认证、上架与签名白名单，" +
                            "第三方 App 无法使用（系统会直接拒绝）。所以这里只能借「音乐播放」通知上岛，" +
                            "显示效果由系统决定：可能没有自定义图标，操作区也按媒体按钮呈现。\n\n" +
                            "任务状态、进度与步骤信息仍然是真实的；「通知栏进度」开关独立控制标准通知，两者可分别开关。",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                    )
                    ToggleRow(
                        title = "通知栏进度",
                        subtitle = "关闭后移除任务进度；后台保活仍需系统通知",
                        checked = settings.notificationEnabled,
                        onChange = { settings.notificationEnabled = it },
                    )
                    ToggleRow(
                        title = "后台保持连接",
                        subtitle = "关闭后停止后台保活；前台使用不受影响",
                        checked = settings.backgroundLive,
                        onChange = {
                            settings.backgroundLive = it
                            com.dsh.mobile.service.DshLiveService.current()?.let { /* preference listener applies mode without dropping task state */ }
                        },
                    )
                    ToggleRow(
                        title = "审批时震动",
                        subtitle = "只有需要你确认时才震动",
                        checked = settings.hapticsOnApproval,
                        onChange = { settings.hapticsOnApproval = it },
                    )
                    ToggleRow(
                        title = "结束后立刻收起",
                        subtitle = "关闭则停留 4 秒显示结果",
                        checked = settings.islandRetractImmediately,
                        onChange = { settings.islandRetractImmediately = it },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- connection -------------------------------------------------
            }
            if (section == SettingsPage.Connection) {
            // Switching how you reach the PC is the one thing this page is for, so
            // it leads. Before this, the page opened with a "内置通道" card holding
            // a Tailscale/WireGuard button, then a toggle card, and only then the
            // connection list — so someone who had just scanned a Cloudflare code
            // and wanted to go back to the VPN had to guess that the road back ran
            // through a card called 内置通道 rather than the list of connections
            // they were looking at. Report from the tablet: "在 CF 扫码登录之后，
            // 再想切换回 VPN 途径，却发现不知道怎么弄了". The route you are on, and
            // the routes you can move to, now come first.
            val currentProfile = settings.activeProfile
            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前连接", fontWeight = FontWeight.Medium)
                    if (currentProfile == null) {
                        Text("还没有连接，先选择一种方式接入电脑", fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    } else {
                        Text(currentProfile.label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            "${currentProfile.kind.title} · ${settings.baseUrl}",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        // The VPN's own state belongs here: a user on the VPN route
                        // needs to know whether the tunnel is up before deciding to
                        // stay, and a user on a public route needs to know the VPN
                        // is off rather than broken.
                        val vpn = com.dsh.mobile.vpn.VpnController.state.collectAsState().value
                        if (currentProfile.kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH) {
                            Text(
                                "内置通道：" + when {
                                    vpn.active == null -> "未开启"
                                    vpn.up -> "${vpn.active?.title} 已连接"
                                    else -> "${vpn.active?.title} 连接中或待登录"
                                },
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
            }
            // One entry per way in, whether or not it is saved yet. The old page
            // could only switch to an *already saved* profile, so "go back to the
            // VPN" was invisible until a MESH profile happened to exist — exactly
            // the dead end reported after scanning a Cloudflare code on a fresh
            // install. Unconfigured routes now appear with their own action.
            Card(cardMotion(1)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("切换连接方式", fontWeight = FontWeight.Medium)
                    Text("随时可以换回任意一种方式；已配对的地址会保留登录状态，不用重新扫码。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    Spacer(Modifier.height(2.dp))
                    val grouped = settings.profiles.sortedByDescending { it.lastUsedAt }.groupBy { it.kind }
                    // Order by what this user can actually use, not by a hard-coded
                    // type ranking. A fixed list put "Cloudflare Tunnel" above
                    // "Relay" while the desktop QR in this setup hands out a
                    // Relay address, so the active route — the one the user just
                    // scanned — appeared as the second row and looked like the
                    // wrong choice. Sort key: active first, then kinds that have a
                    // saved profile (most usable), then the rest; ties keep a
                    // stable public→VPN→local order so the page still reads as a
                    // map when nothing is configured yet.
                    val kindRank = listOf(
                        com.dsh.mobile.core.ConnectionProfile.Kind.TUNNEL,
                        com.dsh.mobile.core.ConnectionProfile.Kind.RELAY,
                        com.dsh.mobile.core.ConnectionProfile.Kind.CUSTOM,
                        com.dsh.mobile.core.ConnectionProfile.Kind.MESH,
                        com.dsh.mobile.core.ConnectionProfile.Kind.LAN,
                        com.dsh.mobile.core.ConnectionProfile.Kind.USB,
                    )
                    kindRank.sortedWith(
                        compareBy(
                            { kind -> if (currentProfile?.kind == kind) 0 else 1 },
                            { kind -> if (grouped[kind].orEmpty().isNotEmpty()) 0 else 1 },
                            { kind -> kindRank.indexOf(kind) },
                        )
                    ).forEach { kind ->
                        val entries = grouped[kind].orEmpty()
                        if (entries.isEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(kind.title, fontSize = 14.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                                    Text(if (kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH) "尚未配置；登录 Tailscale 后自动发现电脑" else "尚未配置", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                                }
                                Button(onClick = {
                                    if (kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH) showVpn = true else onAddConnection()
                                }) { Text(if (kind == com.dsh.mobile.core.ConnectionProfile.Kind.MESH) "去设置" else "添加") }
                            }
                        }
                        entries.forEach { profile ->
                            val current = profile.id == currentProfile?.id
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${if (current) "●" else "○"} ${profile.label}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                    Text(profile.kind.title, fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                                    Text(if (profile.lastUsedAt > 0) "上次连接：" + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(profile.lastUsedAt)) else "尚未确认连接", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                                }
                                if (current) Text("当前", color = LocalDshAccents.current.accent)
                                else Button(onClick = { onSwitchConnection(profile.id) }) { Text("切换") }
                            }
                        }
                    }
                    // The VPN page also owns first-time setup (login, discovery,
                    // pairing), which is not a saved profile yet, so it keeps its
                    // own always-visible entry next to the per-kind rows.
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { showVpn = true }) { Text("内置通道（Tailscale / WireGuard）") }
                        Button(onClick = onAddConnection) { Text("添加其他连接") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    ToggleRow("启动时自动连接", "关闭后先选择入口，已保存连接仍保留", settings.autoConnectOnLaunch) { settings.autoConnectOnLaunch = it }
                    ToggleRow("自动重连", "网络恢复后重连；保留未发送内容和传输", settings.autoReconnect) { settings.autoReconnect = it }
                    Text("顶部延迟来自 DSH 入口 HTTP 检测；请求失败率不是 ICMP 丢包率。", fontSize = 12.sp)
                }
            }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前连接的维护", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text("地址：${settings.baseUrl.ifBlank { "（未设置）" }}", fontSize = 12.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onReloadPage) { Text("重新加载页面") }
                    Text("更新电脑插件或背景后，可在这里刷新。未发送内容会先提醒。", color = MiuixTheme.colorScheme.onBackgroundVariant, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            confirmUnpair = true
                        },
                    ) { Text("解除配对") }
                    Text("只清除当前地址的配对记录；其他已保存连接不受影响。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                }
            }

            }
            if(section==SettingsPage.Diagnostics) {
                SettingsLink("日志记录","开关与分类 · 查看记录 · 导出与循环保留"){page=SettingsPage.Logs}
                SettingsLink("连接帮助","按连接方案查看配置与排查步骤"){showConnectionHelp=true}
                Text("提交问题时请说明连接方式、网络、发生时间与操作步骤，再附上导出的日志。请勿上传密码或配对链接。",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
        }
        com.dsh.mobile.ui.ConfirmationDialog(
            show = confirmUnpair, title = "解除全部配对？",
            message = "将清除当前版本保存的所有连接记录，之后需要重新添加连接。", confirmText = "解除配对",
            onDismiss = { confirmUnpair = false },
            onConfirm = {
                confirmUnpair = false
                settings.unpair()
                com.dsh.mobile.service.DshLiveService.stop(context)
                onBack()
            },
        )
        if (showVpn) com.dsh.mobile.ui.BackGestureSurface { VpnSettingsScreen(
            onBack = { showVpn = false },
            onSelectedServer = {},
            settings = settings,
            onConnectRequested = { url, name, provider ->
                showVpn = false
                onConnectMesh(url, name, com.dsh.mobile.core.ConnectionProfile.MeshProvider.valueOf(provider.name))
            },
        )
        }
        if (showConnectionHelp) com.dsh.mobile.ui.BackGestureSurface {ConnectionHelpScreen(onBack = { showConnectionHelp = false })}

    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).pressClickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
            Text("›", fontSize = 26.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) LocalDshAccents.current.success else LocalDshAccents.current.waiting),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = if (ok) LocalDshAccents.current.success else LocalDshAccents.current.waiting)
    }
}

@Composable
private fun ToggleRow(    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var value by remember(checked) { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) { Switch(checked = value, onCheckedChange = { value = it; onChange(it) }) }
    }
}

/**
 * Post the sample island notification and report what the OS actually did.
 *
 * `NotificationManager.notify` never throws for a suppressed notification, so the
 * only trustworthy check is to ask afterwards whether an active record exists for
 * our id. Returning that verdict to the UI turns "the button does nothing" into a
 * specific, actionable message.
 */
