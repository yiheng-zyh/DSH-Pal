package com.dsh.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.core.ChatArchive
import com.dsh.mobile.core.ChatRole
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import com.dsh.mobile.ui.AppIconButton
import com.dsh.mobile.ui.AppActionIcon
import com.dsh.mobile.ui.web.ChatArchiveCollector
import top.yukonga.miuix.kmp.basic.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 离线对话（实验功能）——按 DSH 原页面的配色与排版复刻。
 *
 * 为什么复刻而不是重写一套风格：用户要的是"像原来那个界面"。原页面是深色
 * (#222120 底 / #2b2a29 卡片)，与 App 原生浅色设置页观感差异很大；离线时
 * 用浅色白底会让人觉得"这不是我的 DSH"。配色从真机截图取样，不是猜的。
 *
 * 完全离线：不联网、不建 WebView、不发请求，只读本机加密快照。
 */
private object ArchiveSkin {
    /** 原页面背景（真机截图取样 #222120）。 */
    val background = Color(0xFF222120)
    /** 原页面顶部栏（#1e2025）。 */
    val topBar = Color(0xFF1E2025)
    /** 卡片/气泡底（略亮于背景，保持层次）。 */
    val card = Color(0xFF2B2A29)
    /** 用户消息气泡（偏冷，与助手区分）。 */
    val userBubble = Color(0xFF2F3A47)
    val assistantBubble = Color(0xFF2B2A29)
    val toolBubble = Color(0xFF262524)
    val onBackground = Color(0xFFEDEDED)
    val onBackgroundMuted = Color(0xFF9A9A9A)
    val accent = Color(0xFF6EA8FE)
}

@Composable
fun OfflineChatScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<ChatArchive.Archive>>(emptyList()) }
    var opened by remember { mutableStateOf<ChatArchive.Archive?>(null) }
    var body by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        entries = ChatArchiveCollector.list(context)
        loading = false
    }
    LaunchedEffect(opened) {
        body = opened?.let { ChatArchiveCollector.text(context, it.id).orEmpty() }.orEmpty()
    }
    BackHandler { if (opened != null) opened = null else onBack() }

    Column(Modifier.fillMaxSize().background(ArchiveSkin.background).safeDrawingPadding()) {
        TopBar(opened?.title ?: "离线对话", onBack = { if (opened != null) opened = null else onBack() })
        if (opened == null) SessionList(entries, loading) { opened = it }
        else MessageView(body)
    }
}

/** 顶部栏：与原页面一致的深色条 + 返回箭头。 */
@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ArchiveSkin.topBar)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回箭头固定 48dp；标题从它右侧起排并允许省略。
        // 早先标题与箭头重叠，是因为标题列没有起点约束，长标题向左压住了图标。
        AppIconButton(AppActionIcon.Back, "返回", onBack, tint = ArchiveSkin.onBackground)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(
                title,
                color = ArchiveSkin.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text("离线快照 · 不联网", color = ArchiveSkin.onBackgroundMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SessionList(
    entries: List<ChatArchive.Archive>,
    loading: Boolean,
    onOpen: (ChatArchive.Archive) -> Unit,
) {
    if (loading) {
        Hint("正在读取本地记录…")
        return
    }
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "还没有本地记录。\n到「设置 → 缓存与加载 → 网页资源 → 对话本地缓存」打开，\n然后正常连接一次。",
                color = ArchiveSkin.onBackgroundMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(
            "共 ${entries.size} 个会话 · 对话正文 ${ChatArchive.formatBytes(entries.sumOf { it.bytes })}",
            color = ArchiveSkin.onBackgroundMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        // 体积差异说明（用户反馈第 4 条：「缓存下来的都没超过 1mb，对话不是有 9mb 吗」）。
        // 两个数字都对：原始记录里绝大部分是工具调用的参数与返回，按隐私边界不落盘。
        val rawTotal = entries.sumOf { it.rawBytes }
        if (rawTotal > entries.sumOf { it.bytes } * 3) {
            Text(
                "网页侧原始记录合计 ${ChatArchive.formatBytes(rawTotal)}，这里只存对话正文" +
                    "（工具调用的命令与输出按隐私边界不保存），所以体积小很多。",
                color = ArchiveSkin.onBackgroundMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp),
                lineHeight = 16.sp,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries, key = { it.id }) { entry ->
                Column(
                    Modifier.fillMaxWidth()
                        .background(ArchiveSkin.card, RoundedCornerShape(10.dp))
                        .clickable { onOpen(entry) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(entry.title, color = ArchiveSkin.onBackground, fontSize = 15.sp, maxLines = 1)
                    Text(
                        "${formatTime(entry.updatedAt)} · ${entry.lines} 段 · " +
                            sizeText(entry),
                        color = ArchiveSkin.onBackgroundMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    "离线只读：不能发消息、不能追问、看不到比快照更新的内容。",
                    color = ArchiveSkin.onBackgroundMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageView(raw: String) {
    // 归档正文按角色编码（[User]/[Assistant]/[Tool]）；旧的无前缀数据整段视为未知。
    val transcript = remember(raw) { ChatRole.Transcript.decode(raw) }
    val state = rememberLazyListState()
    if (transcript.isEmpty()) {
        Hint("这条记录读不出来（可能已被清理，或换机后密钥失效）。")
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(transcript.messages) { message -> MessageRow(message) }
        }
        // 底部灰显：这里做不到什么，说清楚，不做假按钮。
        Row(
            Modifier.fillMaxWidth().background(ArchiveSkin.topBar)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "只读快照 · 回复与实时更新需要联网",
                color = ArchiveSkin.onBackgroundMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 一条消息。
 *
 * 三种角色用**位置 + 配色 + 角色标签**三重区分，不只靠颜色（色弱与深色主题下
 * 单靠颜色不可靠）：
 *  - 我：靠右、冷色气泡、显示「我」
 *  - 助手：靠左、中性气泡、显示「助手」
 *  - 工具：靠左、更暗的条、显示「工具」且字号更小（它是过程记录，不是对话）
 *
 * 关于 Unknown（用户反馈第 2、3 条）：
 * 用户要的是「统一像图二那样的分角色气泡：我靠右蓝、助手靠左灰」。
 * 所以 Unknown **不再走另一套灰暗样式** —— 那种样式才是"展示不统一"的根源。
 * 现在它和助手一样是正常气泡（靠左、正常字号），只在标签上如实写明原因，
 * 而不是干巴巴一句「历史记录（无角色）」让人以为功能坏了。
 */
@Composable
private fun MessageRow(message: ChatRole.Message) {
    val isUser = message.role == ChatRole.Role.User
    val isTool = message.role == ChatRole.Role.Tool
    val isUnknown = message.role == ChatRole.Role.Unknown
    val bubble = when (message.role) {
        ChatRole.Role.User -> ArchiveSkin.userBubble
        ChatRole.Role.Tool -> ArchiveSkin.toolBubble
        // Unknown 用助手同款气泡：它本来就是"一段说不清谁说的对话正文"，
        // 单独做一套暗色反而破坏统一观感。
        else -> ArchiveSkin.assistantBubble
    }
    val rowAlign = if (isUser) Alignment.End else Alignment.Start
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = rowAlign,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 角色标签：颜色之外的第二重线索。
            Text(
                when {
                    // 说清原因（这条是早期整段文本快照，无法分辨谁说的），
                    // 而不是让人以为缓存坏了。
                    isUnknown -> "早期快照 · 未记录角色"
                    else -> message.role.title
                },
                color = if (isUser) ArchiveSkin.accent else ArchiveSkin.onBackgroundMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            if (message.at > 0) {
                Text(
                    "  " + formatClock(message.at),
                    color = ArchiveSkin.onBackgroundMuted,
                    fontSize = 10.sp,
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        SelectionContainer {
            Text(
                message.text,
                // 只有工具调用是"过程记录"才弱化；Unknown 是对话正文，正常显示。
                color = if (isTool) ArchiveSkin.onBackgroundMuted else ArchiveSkin.onBackground,
                fontSize = if (isTool) 12.sp else 14.sp,
                lineHeight = if (isTool) 18.sp else 21.sp,
                modifier = Modifier
                    // 宽度上限：用户消息靠右但不铺满，读起来像对话。
                    .widthIn(max = 320.dp)
                    .background(bubble, RoundedCornerShape(12.dp))
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            )
        }
    }
}

private fun formatClock(at: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ArchiveSkin.onBackgroundMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Split a paragraph block into bubbles by blank-line-separated paragraphs. */
private fun String.lines(): List<String> =
    split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }

private fun formatTime(at: Long): String =
    if (at <= 0) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/**
 * 单条会话的体积文案：把「原始记录」与「纯对话正文」分开写。
 *
 * 用户反馈「缓存下来的都没超过 1mb，对话不是有 9mb 吗」—— 两个数字都对，不解释就会被
 * 当成"缓存没生效"。只有能拿到原始体积时才写，拿不到就只写正文（不外推）。
 */
private fun sizeText(entry: ChatArchive.Archive): String {
    val body = "对话正文 " + ChatArchive.formatBytes(entry.bytes)
    if (entry.rawBytes <= entry.bytes) return body
    return body + "（原始记录 " + ChatArchive.formatBytes(entry.rawBytes) + "，工具调用不落盘）"
}
