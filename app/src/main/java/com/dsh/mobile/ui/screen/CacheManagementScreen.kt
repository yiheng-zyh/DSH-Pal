package com.dsh.mobile.ui.screen

import android.webkit.WebView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.dsh.mobile.core.ChatArchive
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.AppPageHeader
import com.dsh.mobile.ui.theme.LocalDshAccents
import com.dsh.mobile.ui.web.*
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DataPage(val title:String) { Overview("缓存与数据"),Resources("网页资源"),Preferences("页面偏好"),Accounts("网页登录与站点数据") }
@Composable
fun CacheManagementScreen(settings:SettingsStore,webView:WebView?,onBack:()->Unit,onReload:()->Unit) {
    var page by remember {mutableStateOf(DataPage.Overview)}
    var pending by remember {mutableStateOf<BrowserCleanup?>(null)}
    var busy by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf<String?>(null)}
    var offline by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope();val context=androidx.compose.ui.platform.LocalContext.current
    val full=remember {BrowserDataManager.fullCleanupSupported()}
    val origin=settings.currentBaseUrl
    val domain=origin.toHttpUrlOrNull()?.let {it.topPrivateDomain()?:it.host}
    fun back(){if(!busy){if(page==DataPage.Overview)onBack()else{page=DataPage.Overview;result=null}}}
    if (offline) { OfflineChatScreen { offline = false }; return }
    BackHandler {back()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=androidx.compose.ui.Alignment.TopCenter) {
    Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)) {
        AppPageHeader(page.title,onBack={back()})
        AnimatedContent(targetState=page,modifier=Modifier.weight(1f),
            transitionSpec={(fadeIn(tween(160))+slideInHorizontally(tween(200)){it/24}).togetherWith(fadeOut(tween(100)))},label="data-page") { section ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(section==DataPage.Overview) {
                DataLink("网页资源","更新插件、样式或图片；保留登录",{page=DataPage.Resources})
                DataLink("页面偏好","当前站点的设置、草稿与本地存储",{page=DataPage.Preferences})
                DataLink("网页登录与站点数据","单独管理 Cookie 或完整清理",{page=DataPage.Accounts})
                Text("清理均需确认。连接地址、主题、Tailscale 账号和 WireGuard 配置不会被删除。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(section==DataPage.Resources) {
                ChatArchiveCard(settings) { offline=true }
                Card {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("资源复用",fontWeight=FontWeight.Medium)
                    Text(WebResourceCache.summary(),fontSize=13.sp)
                    Text("静态资源最多 96 MB，自动检查更新。对话是否离线可用由上面那个实验开关决定，默认关闭。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }}
                DataAction(BrowserCleanup.RESOURCES,busy){pending=it}
                Button(onClick=onReload,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("重新加载页面")}
                Text("未发送内容会先提醒。仅清资源后，下一次访问需要重新下载。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(section==DataPage.Preferences) {
                Text("清理当前地址的本地存储，可能影响草稿、插件设置或网页授权。保留 Cookie、连接记录和原生账号。",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.PAGE_PREFERENCES,busy||webView==null){pending=it}
            }
            if(section==DataPage.Accounts) {
                Text("配对记录保留，但网页授权被清理后可能需要重新配对。",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.COOKIES,busy){pending=it}
                DataAction(BrowserCleanup.SITE,busy||!full||domain==null){pending=it}
                if(domain!=null)Text("当前主域：$domain · 包含子域",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                DataAction(BrowserCleanup.ALL,busy||!full){pending=it}
                if(!full)Text("完整清理需要更新 Android System WebView；当前不会执行不完整的替代清理。",fontSize=12.sp)
                Text("网页登录清理不会退出本 App 的 Tailscale 账号，也不会删除 WireGuard 密钥。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
            }
            if(busy)Text("正在清理，请稍候…",fontSize=13.sp)
            result?.let {Text(it,fontSize=13.sp)}
        }
        }
    }
    com.dsh.mobile.ui.ConfirmationDialog(pending!=null,pending?.title.orEmpty()+"？",
        pending?.detail.orEmpty()+(if(pending==BrowserCleanup.SITE)"\n作用范围：${domain.orEmpty()} 及其子域。"else "")+"\n保留原生账号与连接地址。请先保存未发送内容。",
        confirmText="确认清理",onDismiss={pending=null},onConfirm={
            val action=pending;pending=null
            if(action!=null)scope.launch {busy=true;result=null
                try {BrowserDataManager.perform(context,webView,origin,action);result="清理完成"}
                catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;result=e.message?:"清理未完成，请重试"}
                finally{busy=false}
            }
        })
    }
}
@Composable private fun DataLink(title:String,subtitle:String,onClick:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick=onClick)){Row(Modifier.padding(18.dp)) {
        Column(Modifier.weight(1f)){Text(title,fontSize=16.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(6.dp));Text(subtitle,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)}
        Text("›",fontSize=24.sp)
    }}
}
@Composable private fun DataAction(action:BrowserCleanup,disabled:Boolean,onClick:(BrowserCleanup)->Unit) {
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(action.title,fontSize=15.sp,fontWeight=FontWeight.Medium)
        Text(action.detail,fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
        Button(enabled=!disabled,onClick={onClick(action)}){Text("选择清理",fontSize=13.sp)}
    }}
}

/** 缓存范围的中文标签。 */
private fun selectionTitle(selection: ChatArchive.Selection): String = when (selection) {
    ChatArchive.Selection.AUTO -> "全部对话"
    ChatArchive.Selection.SELECTED -> "仅勾选的对话"
}

/** 相对时间：让"上次同步"一眼看出新旧，而不是一串时间戳。 */
private fun agoText(at: Long): String {
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 5_000 -> "刚刚"
        diff < 60_000 -> "${diff / 1000} 秒前"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        else -> "${diff / 3_600_000} 小时前"
    }
}

/**
 * 「实验功能：对话本地缓存」卡片。
 *
 * 三条硬要求（用户明确提的）：
 *  1. **默认关闭** —— 初始值来自 SettingsStore，预置 false。
 *  2. **标注实验** —— 标题带「实验」徽标，正文写清边界（只读、不同步、占空间）。
 *  3. **容量可选** —— 档位按钮，默认 1 GB；用户要求「优先缓存最新对话」，淘汰由
 *     ChatArchive.planEviction 按更新时间倒序执行。
 *
 * 另外必须诚实说明隐私差异：它会保存对话正文（用户输入与助手回复），
 * 以及**工具调用的一句话标签**（例如「调用 写入文件」），
 * 与任务通知「只显示公开步骤」的边界不同。开启即代表用户接受这一点。
 */
@Composable private fun ChatArchiveCard(settings: SettingsStore, onOpenOffline: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(settings.chatArchiveEnabled) }
    var quota by remember { mutableStateOf(settings.chatArchiveQuota) }
    var selection by remember { mutableStateOf(settings.chatArchiveSelection) }
    var selectedSessions by remember { mutableStateOf(settings.chatArchiveSelectedSessions) }
    var usage by remember { mutableStateOf(ChatArchiveCollector.usageBytes(context)) }
    var sessions by remember { mutableStateOf(ChatArchiveCollector.list(context).size) }
    var confirmClear by remember { mutableStateOf(false) }
    // 「选择要同步的对话」窗口：开关打开时自动弹，也可随时手动打开重选。
    var showPicker by remember { mutableStateOf(false) }
    // 同步状态：定时刷新，让用户能看见"到底有没有在工作"。
    var status by remember { mutableStateOf(ChatArchiveCollector.status()) }
    var syncedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(enabled) {
        while (enabled) {
            status = ChatArchiveCollector.status()
            kotlinx.coroutines.delay(2_000)
        }
        status = ChatArchiveCollector.status()
    }

    /** 勾选/取消某个会话；只在 SELECTED 模式下有意义。 */
    fun toggleSelected(key: String) {
        selectedSessions = if (key in selectedSessions) selectedSessions - key else selectedSessions + key
        settings.chatArchiveSelectedSessions = selectedSessions
    }

    // 开关变化立即生效：采集器按设置决定启停，不需要重启或重连。
    // 注意 sync(active=true)：用户往往在页面已加载完之后才来打开这个开关，
    // 那时不会再有一次"页面就绪"回调；若这里传 false，采集器永远起不来。
    LaunchedEffect(enabled) {
        settings.chatArchiveEnabled = enabled
        ChatArchiveCollector.sync(context, active = true)
        // 打开开关的那一刻就弹出「选择要同步的对话」——用户要求的流程是
        // 「开启之后直接开始实际下载」，不该让他再去别处找入口。
        if (enabled) showPicker = true
    }
    LaunchedEffect(quota) {
        settings.chatArchiveQuota = quota
    }
    LaunchedEffect(selection) {
        settings.chatArchiveSelection = selection
    }
    // 外部通道状态：隧道挂了会让"同步"看起来毫无反应，必须先让用户看到这一层。
    var tunnel by remember { mutableStateOf<com.dsh.mobile.core.TunnelStatus?>(null) }
    LaunchedEffect(Unit) {
        val profile = settings.activeProfile
        if (profile != null) {
            while (true) {
                tunnel = com.dsh.mobile.core.TunnelProbe.check(profile)
                kotlinx.coroutines.delay(10_000)
            }
        }
    }
    tunnel?.takeIf { it.state != "unknown" && it.state.isNotBlank() }?.let { status ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("外部通道", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    status.summary,
                    fontSize = 12.sp,
                    color = if (status.working) MiuixTheme.colorScheme.onBackgroundVariant
                    else LocalDshAccents.current.error,
                )
                if (!status.working && status.reason.isNotBlank()) {
                    Text(
                        "这会导致同步、对话、插件全部不可用。可先改用局域网直连入口。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("对话本地缓存", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    "实验",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier
                        .background(MiuixTheme.colorScheme.secondaryVariant, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(
                "开启后，连接时把当前对话正文存到手机；断网也能在这里翻看，联网后自动更新。" +
                    "工具调用只记「调用了什么」，不记命令正文与返回结果。只读快照，不发送、不重试。默认关闭。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("开启对话缓存", fontSize = 14.sp)
                        Text("关闭时不会写入任何对话内容", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            if (enabled) {
                Text("缓存范围", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatArchive.Selection.entries.forEach { option ->
                        val selected = option == selection
                        Button(onClick = { selection = option }) {
                            Text(
                                if (selected) "✓ " + selectionTitle(option) else selectionTitle(option),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                if (selection == ChatArchive.Selection.SELECTED) {
                    val known = ChatArchiveCollector.knownSessions()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selectedSessions.isEmpty()) "还没有选中的对话"
                            else "已选 ${selectedSessions.size} 个对话",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { showPicker = true }) { Text("选择对话", fontSize = 12.sp) }
                    }
                    if (known.isNotEmpty()) {
                        Text(
                            "本次连接后已产生内容的对话：",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        known.forEach { (key, title) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { toggleSelected(key) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (key in selectedSessions) "✓ " else "○ ",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                                Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            }
                        }
                    }
                }
                // ---- 同步状态（用户要求：进度必须可见、要有立即同步）----
                // 缓存是后台行为，没有可见反馈等于不存在。这里显示的是真实计数，
                // 不是装饰性动画：采集器在跑没有、攒了几条、上次什么时候写的。
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("同步状态", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(
                                when {
                                    status.lastError.isNotBlank() -> "上次失败"
                                    status.running -> "正在同步"
                                    else -> "未在同步"
                                },
                                fontSize = 11.sp,
                                color = if (status.lastError.isNotBlank()) LocalDshAccents.current.error
                                else if (status.running) LocalDshAccents.current.accent
                                else MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                        Text(
                            when {
                                !status.running ->
                                    "采集未运行：需要先连接工作台（页面就绪后才开始）。"
                                status.lastCaptureAt <= 0L ->
                                    "已开始监听，还没写入第一条。发一条消息或等一次自动同步（约 45 秒）。"
                                else ->
                                    "上次同步：" + agoText(status.lastCaptureAt) + " · 会话「" +
                                        status.lastTitle.ifBlank { "未命名" } + "」"
                            },
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Text(
                            "待写入 ${status.pendingMessages} 条 · 本次已同步 ${status.savedSessions} 个会话 · 自动同步每 45 秒",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        // 缓存要持续更新：如实显示"网页上还有几个对话没缓存过"。
                        // App 不主动切换用户的会话，所以这里说清补全发生在打开对话时。
                        val pendingUpdates = ChatArchiveCollector.pendingUpdates
                        if (pendingUpdates > 0) {
                            Text(
                                "还有 $pendingUpdates 个对话尚未缓存 · 打开对应对话时会补进来（不主动切换你正在用的会话）",
                                fontSize = 11.sp,
                                color = LocalDshAccents.current.accent,
                            )
                        }
                        if (status.lastError.isNotBlank()) {
                            Text(status.lastError, fontSize = 11.sp, color = LocalDshAccents.current.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    // 立即同步：把内存里攒着的消息立刻落盘，不等下一个 45 秒周期。
                                    ChatArchiveCollector.captureNow()
                                    syncedAt = System.currentTimeMillis()
                                    status = ChatArchiveCollector.status()
                                    usage = ChatArchiveCollector.usageBytes(context)
                                    sessions = ChatArchiveCollector.list(context).size
                                },
                            ) { Text("立即同步", fontSize = 13.sp) }
                            Text(
                                if (syncedAt > 0L) "已手动同步" else "",
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically),
                            )
                        }
                        Text(
                            "说明：手机端只能缓存「它看见过的内容」。当前会话在本次连接后产生的新消息可以完整带角色；" +
                                "打开是历史会话时，只能按页面已渲染的文字留一份快照。",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
                Text("容量上限（超出后按「最久未更新」淘汰，优先保留最新对话）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatArchive.Quota.options().forEach { option ->
                        val selected = option == quota
                        Button(onClick = { quota = option }) {
                            Text(if (selected) "✓ ${option.title}" else option.title, fontSize = 12.sp)
                        }
                    }
                }
                Text(
                    "已用 ${ChatArchive.formatBytes(usage)} / 上限 ${ChatArchive.formatBytes(quota.bytes)} · 已缓存 $sessions 个会话",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                // 体积差异说明。两个数字都对 —— 网页侧会话记录里 98% 是工具调用的参数与返回
                // （写文件时整个文件内容都在 arguments 里），按"不落命令正文与输出"
                // 的既有隐私边界，这些不落盘。所以几百 KB 是**正常且正确**的，
                // 不解释清楚用户会以为缓存没生效。
                val rawBytes = ChatArchiveCollector.rawBytesTotal(context)
                if (rawBytes > usage * 3 && usage > 0) {
                    Text(
                        "网页侧原始记录合计 ${ChatArchive.formatBytes(rawBytes)}，本机只存对话正文 " +
                            "${ChatArchive.formatBytes(usage)}。差的部分是工具调用的命令与输出 —— " +
                            "按隐私边界不保存，不是缓存没生效。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        usage = ChatArchiveCollector.usageBytes(context)
                        sessions = ChatArchiveCollector.list(context).size
                    }) { Text("刷新统计", fontSize = 13.sp) }
                    Button(onClick = { onOpenOffline() }) { Text("离线查看", fontSize = 13.sp) }
                    Button(onClick = { confirmClear = true }) { Text("清除缓存", fontSize = 13.sp) }
                }
                Text(
                    "注意：与任务通知不同，这里会保存对话正文，以及工具调用的一句话标签（不含命令正文与输出）。" +
                        "只存在本机 App 私有目录，加密保存、不外发；关闭开关后停止新增，已存内容仍在，可点「清除缓存」删除。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
    com.dsh.mobile.ui.ConfirmationDialog(
        confirmClear,
        "清除对话缓存？",
        "会删除全部本地对话快照（${ChatArchive.formatBytes(usage)}）。保留连接、配对、草稿和设置。此操作不可撤销。",
        confirmText = "确认清除",
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            ChatArchiveCollector.clear(context)
            usage = ChatArchiveCollector.usageBytes(context)
            sessions = ChatArchiveCollector.list(context).size
        },
    )
    // 「选择要同步的对话」窗口：开关打开时自动弹，也可随时重选。
    if (showPicker) {
        ArchiveSelectionDialog(
            settings = settings,
            onDismiss = { showPicker = false },
            onConfirmed = { chosen ->
                showPicker = false
                selectedSessions = chosen
                selection = ChatArchive.Selection.SELECTED
                // 选完立刻同步一次，用户不用再回去点按钮。
                ChatArchiveCollector.captureNow()
                usage = ChatArchiveCollector.usageBytes(context)
                sessions = ChatArchiveCollector.list(context).size
            },
        )
    }
}
