package com.dsh.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dsh.mobile.core.ChatArchive
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.theme.LocalDshAccents
import com.dsh.mobile.ui.web.ChatArchiveCollector
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「选择要同步的对话」窗口。
 *
 * 用户要求的完整流程：
 *  1. 打开「对话本地缓存」开关 → **直接弹出本窗口**开始实际下载；
 *  2. 列表里显示会话标题与时间（不加载正文，够区分即可）；
 *  3. 提供快捷筛选：全选 / 一周内 / 三天内 / 一个月内；
 *  4. 可逐个勾选，滑列表选择；
 *  5. 点「开始同步」完成。
 *
 * 设计取舍：
 *  - **列表来自网页侧栏**，不是本地缓存 —— 用户要选的是"电脑上有哪些对话"，
 *    所以必须先连着工作台；连不上时明确提示，不伪造列表。
 *  - 时间筛选依赖网页给的时间片段；网页没给时间的会话在时间筛选中**保守保留**
 *    （宁可多选，不因识别不出而漏掉），并在该行标注"时间未知"。
 *  - 只记标题与时间，正文仍由事件流/快照按需采集，不在本窗口加载。
 */
@Composable
fun ArchiveSelectionDialog(
    settings: SettingsStore,
    onDismiss: () -> Unit,
    onConfirmed: (selected: Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<ChatArchiveCollector.ArchiveSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 沿用上次的选择：用户操作过一次之后，下次打开就应该按上次的来。
    var selected by remember { mutableStateOf(settings.chatArchiveSelectedSessions) }
    var filter by remember { mutableStateOf(QuickFilter.ALL) }
    // 工作区分组默认全部展开（用户要"展开的样式"），可点组头折叠。
    var collapsedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 同步进度：0=未开始，>0 表示正在逐个打开会话抓取。
    var syncing by remember { mutableStateOf(false) }
    var progressDone by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(0) }
    var progressTitle by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf("") }
    val lastPickedAt = remember { settings.chatArchiveLastPickAt }
    val lastTitles = remember { settings.chatArchiveSelectedTitles }
    // 列表还没加载完时，先展示上次选中的标题，用户立刻能认出"上次同步的是这几条"。
    val rememberedMissing = remember(selected, sessions) {
        selected.filter { key -> sessions.none { it.key == key } }
    }

    // 打开即加载侧栏会话列表（只读标题与时间）。
    LaunchedEffect(Unit) {
        ChatArchiveCollector.loadSessionList(context) { list ->
            sessions = list
            loading = false
        }
    }

    /**
     * 快捷筛选：把网页给的相对时间（刚刚 / N分 / N小时 / N天 ...）换算成天数再比。
     * 解析不出的**保守保留**（宁可多选，不因识别失败而漏掉用户的对话）。
     */
    fun matches(session: ChatArchiveCollector.ArchiveSession, option: QuickFilter): Boolean {
        val text = session.whenText
        if (text.isBlank()) return true
        val days = relativeToDays(text) ?: return true
        return when (option) {
            QuickFilter.ALL, QuickFilter.NONE -> true
            QuickFilter.DAYS_3 -> days <= 3
            QuickFilter.DAYS_7 -> days <= 7
            QuickFilter.DAYS_30 -> days <= 30
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择要同步的对话", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(
                    "只读取标题与时间，不加载正文。勾选后开始下载这些对话的数据。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                // 沿用上次选择：用户操作过一次就不用重选，但随时可以改。
                if (lastPickedAt > 0L && selected.isNotEmpty()) {
                    Text(
                        "已沿用上次的选择（" + agoTextLocal(lastPickedAt) + "，${selected.size} 个对话）。" +
                            "可以直接点「开始同步」，或重新勾选。",
                        fontSize = 11.sp,
                        color = LocalDshAccents.current.accent,
                    )
                } else if (lastPickedAt == 0L) {
                    Text(
                        "第一次使用：勾选要同步的对话，之后会自动记住这次选择。",
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                // 快捷筛选
                Text("快捷选择", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickFilter.entries.forEach { option ->
                        Button(onClick = {
                            filter = option
                            if (option == QuickFilter.ALL) {
                                // 全选：把当前能看到的全部勾上（最直接的"一起全勾"）
                                selected = sessions.map { it.key }.toSet()
                            } else if (option != QuickFilter.NONE) {
                                selected = sessions.filter { matches(it, option) }.map { it.key }.toSet()
                            }
                        }) {
                            Text(option.title, fontSize = 12.sp)
                        }
                    }
                    Button(onClick = { selected = emptySet() }) { Text("清空", fontSize = 12.sp) }
                }
                Text(
                    "已选 ${selected.size} / ${sessions.size} 个" +
                        if (filter != QuickFilter.NONE) " · 当前筛选：${filter.title}" else "",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                // 上次选过、但当前列表里没出现（侧栏没展开/已归档）：如实说明，
                // 不假装它还在，也不偷偷把它从选择里删掉。
                if (rememberedMissing.isNotEmpty()) {
                    Text(
                        "另有 ${rememberedMissing.size} 个上次选中的对话不在当前列表里：" +
                            rememberedMissing.mapNotNull { lastTitles[it] }.take(3)
                                .joinToString("、").ifBlank { "（标题未知）" } +
                            "。保持勾选，能访问到时会照常同步。",
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }

                // 会话列表
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在读取会话列表…", fontSize = 13.sp)
                    }
                    sessions.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("读不到会话列表", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                "需要先连接工作台并展开左侧会话列表。\n连上后重开这个窗口即可。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                    else -> {
                        // 按工作区分组，可折叠展开 —— 侧栏里会话本来就挂在工作区下面，
                        // 平铺成一列会让用户分不清哪条属于哪个项目。
                        val grouped = sessions.groupBy { it.group.ifBlank { "" } }
                        LazyColumn(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            grouped.forEach { (groupName, items) ->
                                val expanded = groupName !in collapsedGroups
                                item(key = "group:$groupName") {
                                    val checkedInGroup = items.count { it.key in selected }
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                collapsedGroups = if (expanded) collapsedGroups + groupName
                                                else collapsedGroups - groupName
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(if (expanded) "▾" else "▸", fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                                            modifier = Modifier.width(20.dp))
                                        Text(
                                            groupName.ifBlank { "未分组" },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                        )
                                        Text(
                                            if (checkedInGroup > 0) "$checkedInGroup/${items.size}" else "${items.size}",
                                            fontSize = 11.sp,
                                            color = if (checkedInGroup > 0) LocalDshAccents.current.accent
                                            else MiuixTheme.colorScheme.onBackgroundVariant,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        // 整组一键勾选/取消，不同工作区可以分开同步。
                                        Text(
                                            if (checkedInGroup == items.size) "取消本组" else "全选本组",
                                            fontSize = 11.sp,
                                            color = LocalDshAccents.current.accent,
                                            modifier = Modifier.clickable {
                                                val keys = items.map { it.key }.toSet()
                                                selected = if (checkedInGroup == items.size) selected - keys
                                                else selected + keys
                                            }.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                if (expanded) {
                                    items(items.size, key = { idx -> items[idx].key }) { idx ->
                                        val session = items[idx]
                                        val checked = session.key in selected
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable {
                                                    selected = if (checked) selected - session.key
                                                    else selected + session.key
                                                }
                                                .padding(start = 24.dp, top = 9.dp, bottom = 9.dp, end = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (checked) "✓" else "○",
                                                fontSize = 15.sp,
                                                color = if (checked) LocalDshAccents.current.accent
                                                else MiuixTheme.colorScheme.onBackgroundVariant,
                                                modifier = Modifier.width(26.dp),
                                            )
                                            Column(Modifier.weight(1f)) {
                                                Text(session.title, fontSize = 14.sp, maxLines = 1)
                                                Text(
                                                    session.whenText.ifBlank { "时间未知" },
                                                    fontSize = 11.sp,
                                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 同步进度：逐个打开会话抓取，用户能看见走到第几个。
                if (syncing) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "正在同步 $progressDone / $progressTotal" +
                                if (progressTitle.isNotBlank()) " · $progressTitle" else "",
                            fontSize = 12.sp,
                            color = LocalDshAccents.current.accent,
                        )
                        // 自绘进度条：不引入 material3 依赖（项目只用 Miuix + foundation）。
                        val fraction = if (progressTotal == 0) 0f
                        else (progressDone.toFloat() / progressTotal).coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth().height(4.dp)
                                .background(
                                    MiuixTheme.colorScheme.secondaryVariant,
                                    androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                ),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(fraction).height(4.dp)
                                    .background(
                                        LocalDshAccents.current.accent,
                                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                        Text(
                            "在后台拉取，你的界面不会切换、也不会被打断。",
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                    }
                }
                if (resultText.isNotBlank()) {
                    Text(resultText, fontSize = 12.sp, color = LocalDshAccents.current.accent)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss, enabled = !syncing, modifier = Modifier.weight(1f)) {
                        Text("取消", fontSize = 14.sp)
                    }
                    Button(
                        onClick = {
                            settings.chatArchiveSelection = ChatArchive.Selection.SELECTED
                            settings.chatArchiveSelectedSessions = selected
                            // 记住标题与时间：下次打开时先用它们回显"上次同步的是哪几条"，
                            // 用户不必重新翻列表去猜 row-key 对应哪个对话。
                            val titles = sessions.filter { it.key in selected }
                                .associate { it.key to it.title } +
                                lastTitles.filterKeys { it in selected }
                            settings.chatArchiveSelectedTitles = titles
                            settings.chatArchiveLastPickAt = System.currentTimeMillis()
                            if (selected.isEmpty()) {
                                resultText = "还没有勾选任何对话。"
                                return@Button
                            }
                            // 后台拉取，**不切换用户界面**（用户要求无感）。
                            // 走页面内 fetch（同源 + 已登录 Cookie），界面完全不动。
                            syncing = true
                            resultText = ""
                            progressDone = 0
                            progressTotal = selected.size
                            val keys = selected.toList()
                            var okCount = 0
                            var failCount = 0
                            var failReason = ""
                            fun nextAt(i: Int) {
                                if (i >= keys.size) {
                                    syncing = false
                                    resultText = "同步完成：成功 $okCount 个" +
                                        if (failCount > 0) "，失败 $failCount 个（$failReason）" else ""
                                    onConfirmed(selected)
                                    return
                                }
                                val sid = keys[i].removePrefix("session:")
                                ChatArchiveCollector.pullSession(context, sid) { ok, why ->
                                    if (ok) okCount++ else {
                                        failCount++
                                        if (failReason.isBlank()) failReason = why
                                    }
                                    progressDone = i + 1
                                    nextAt(i + 1)
                                }
                            }
                            // 直接同步（session/page 已真机验证可用，不再先探测，
                            // 否则探测的超时会与拉取抢同一个回调）。
                            nextAt(0)
                        },
                        enabled = !loading && !syncing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            if (syncing) "同步中…（$progressDone/$progressTotal）"
                            else "开始同步（${selected.size}）",
                            fontSize = 14.sp,
                        )
                    }
                }
                Text(
                    "同步时在后台读取每个对话的完整记录（含用户与助手的全部往来），" +
                        "界面不会切换、也不会被打断。",
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
        }
    }
}

/** 快捷筛选档位。 */
private enum class QuickFilter(val title: String) {
    NONE("自定义"),
    ALL("全选"),
    DAYS_3("3 天内"),
    DAYS_7("一周内"),
    DAYS_30("一个月内"),
}

/** 把网页的相对时间文本换算成"约几天前"；解析不出返回 null（调用方保守放行）。 */
private fun relativeToDays(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    if (t.contains("刚刚") || t.contains("今天")) return 0
    if (t.contains("昨天")) return 1
    if (t.contains("前天")) return 2
    Regex("(\\d+)\\s*(?:秒|分钟|分)").find(t)?.let { return 0 }
    Regex("(\\d+)\\s*(?:小时|时)").find(t)?.let { return 0 }
    Regex("(\\d+)\\s*天").find(t)?.let { return it.groupValues[1].toIntOrNull() }
    Regex("(\\d+)\\s*周").find(t)?.let { v -> v.groupValues[1].toIntOrNull()?.let { return it * 7 } }
    Regex("(\\d+)\\s*个?月").find(t)?.let { v -> v.groupValues[1].toIntOrNull()?.let { return it * 30 } }
    // 中文日期 / ISO 片段：按"日"差近似，跨月时保守放大
    val md = Regex("(\\d{1,2})\\s*[-/月]").find(t)?.groupValues?.get(1)?.toIntOrNull()
    if (md != null) {
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        val diff = today - md
        return if (diff >= 0) diff else diff + 31
    }
    return null
}

/** 相对时间：让"上次选择"一眼看出新旧。 */
private fun agoTextLocal(at: Long): String {
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
