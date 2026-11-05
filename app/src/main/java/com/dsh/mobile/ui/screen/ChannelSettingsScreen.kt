package com.dsh.mobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.core.ChannelHealth
import com.dsh.mobile.core.ChannelPolicy
import com.dsh.mobile.core.ConnectionProfile
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.ui.AppPageHeader
import com.dsh.mobile.ui.motion.pressClickable
import com.dsh.mobile.ui.theme.LocalDshAccents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「通道自动选路」设置页。
 *
 * ## 为什么单独一页
 *
 * 三件事必须同时可见，否则用户没法判断「要不要让它自己切」，而这个判断又很影响观感：
 *  1. 自动切换开关 —— 保命能力能不能关；
 *  2. 优先级拖动排序 —— 顺序最终由用户说了算（默认 LAN → IPv6 → 公网/国内中转 → Tailscale）；
 *  3. 各通道实时状态 —— 「为什么它把我切了」要能当场看出来。
 *
 * 做成独立文件 + 独立页面（而不是往 SettingsScreen 里再塞一张卡）还有一个更实际的原因：
 * 这一轮改动和另一端正在改的缓存设置页共用目录，独立文件让两边互不覆盖。
 * 入口由调用方挂载（onBack 由调用方提供）。
 */
@Composable
fun ChannelSettingsScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
    onSwitchConnection: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            AppPageHeader("通道自动选路") { onBack() }
        }
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ChannelFailoverCard(settings, onSwitchConnection)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 自动选路卡片本体（设置页内联版）。
 *
 * 与 [ChannelSettingsScreen] 同一套内容：一个是整页，一个是内联卡片。
 * 为什么两处都要：整页方便第一次配置时慢慢拖顺序；内联版让用户在日常设置里
 * 就能一眼看到「当前各通道通不通」，而不用再点进一层。
 */
@Composable
fun ChannelFailoverCard(
    settings: SettingsStore,
    onSwitchConnection: (String) -> Unit = {},
) {
    val accents = LocalDshAccents.current
    val scope = rememberCoroutineScope()

    var order by remember { mutableStateOf(settings.channelPriority) }
    var autoSwitch by remember { mutableStateOf(settings.channelAutoSwitch) }
    var health by remember { mutableStateOf<Map<String, ChannelHealth>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var lastProbeAt by remember { mutableStateOf(0L) }
    var suggestion by remember { mutableStateOf<ConnectionProfile?>(null) }
    var suggestionReason by remember { mutableStateOf("") }
    // 网关（电脑侧）自报的通道状态，按 profile.id 索引。
    // 只用来解释「这条故障该怪中转服务还是怪电脑配置」，**不参与可用性判定** ——
    // 网关报 ok 只代表电脑准备好接客了，不代表手机此刻这个网络能到它。
    var gateway by remember { mutableStateOf<Map<String, com.dsh.mobile.core.GatewayChannel>>(emptyMap()) }

    val profiles = remember(settings.profiles) { settings.profiles }

    /** 测一轮全部通道。抽成局部函数，因为首次进入和「重新实测」按钮都要用。 */
    suspend fun probeAll() {
        if (profiles.isEmpty()) return
        probing = true
        val results = withContext(Dispatchers.IO) {
            runCatching { com.dsh.mobile.core.ChannelFailover.measureAll(profiles) }
                .onFailure { RunLog.warn("Channel", "measure failed ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
        }
        health = results.associateBy { it.profileId }
        lastProbeAt = System.currentTimeMillis()
        probing = false
        // 顺带取一次网关自报的通道清单：**只用于解释故障归属**（中转服务侧 vs 电脑配置），
        // 不参与「可不可用」的判定。当前正在用的那条能问到就够了 ——
        // 它要是完全不通，换一条去问大概率也一样问不到。
        gateway = withContext(Dispatchers.IO) {
            settings.activeProfile?.let { current ->
                runCatching { com.dsh.mobile.core.GatewayChannel.fetch(current) }.getOrDefault(emptyList())
            }.orEmpty().let { channels ->
                com.dsh.mobile.core.GatewayChannel.match(channels, profiles)
            }
        }
        // 只在「当前走国外中转 + 国内直连实测可用」时给一条建议。
        // 这条规则在 ChannelPolicy 里是纯函数，界面只负责把它画出来。
        val current = settings.activeProfile
        suggestion = if (current != null && settings.channelAutoSwitch &&
            ChannelPolicy.isForeignRelay(current) &&
            System.currentTimeMillis() >= settings.channelPromptSilencedUntil
        ) {
            ChannelPolicy.betterDomestic(current, profiles, order, health)
        } else null
        suggestionReason = suggestion?.let { "当前走国外中转，${it.kind.title} 直连实测可用，换过去通常更快" }.orEmpty()
    }

    // 只在卡片进入时测一轮；不做常驻轮询 —— 设置页看一眼就够了，
    // 常驻探测白耗电，还会把诊断日志的环形缓冲冲掉。
    LaunchedEffect(profiles.map { it.id }) { probeAll() }

    /** 拖动中的行：记下标 + 累计位移，松手时换算成移动几格。 */
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("通道自动选路", fontWeight = FontWeight.Medium)
            Text(
                "当前通道完全不可用时会自动换一条能用的路（换路会重新加载页面）；" +
                    "正常使用时不会自己动手，只在你走国外中转、而国内直连可用时提示一次。",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动切换通道", fontSize = 15.sp)
                    Text(
                        "关闭后不再自动切换，也不再提示",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                    Switch(checked = autoSwitch, onCheckedChange = {
                        autoSwitch = it
                        settings.channelAutoSwitch = it
                        RunLog.info("Channel", "auto switch=$it")
                    })
                }
            }

            // 手动暂停还剩多久：用户自己切过之后必须能看见「App 现在不插手」，
            // 否则他会以为自动切换坏了。
            val holdLeftMin = (settings.channelManualHoldUntil - System.currentTimeMillis()) / 60_000
            if (holdLeftMin >= 0 && settings.channelManualHoldUntil > 0) {
                InfoLine(
                    "自动选路已暂停 ${holdLeftMin + 1} 分钟",
                    "你刚手动选了通道；暂停结束后才恢复自动保命",
                    accents.waiting,
                )
            }
            val silenceLeftMin = (settings.channelPromptSilencedUntil - System.currentTimeMillis()) / 60_000
            if (silenceLeftMin >= 0 && settings.channelPromptSilencedUntil > 0) {
                InfoLine(
                    "优化提示静默 ${silenceLeftMin + 1} 分钟",
                    "拒绝一次后 30 分钟内不会再问",
                    MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }

            if (profiles.isEmpty()) {
                Text(
                    "还没有保存的连接，先添加一个再回来调顺序。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            } else {
                Spacer(Modifier.height(2.dp))
                Text("优先级（长按拖动调整）", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "排在前面的通道优先被自动选用。默认 局域网 → IPv6 直连 → 公网/国内中转 → Tailscale。",
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )

                order.forEachIndexed { index, kind ->
                    val isDragging = dragging == index
                    // 这一档已保存的通道。提到 Row 作用域外，因为行内既要用它显示标签，
                    // 也要用它取实测状态。
                    val entries = profiles.filter { it.kind == kind }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .clip(RoundedCornerShape(10.dp))
                            // 只有长按拖动，没有 clickable：这一行是「调顺序」不是「点一下」，
                            // 混进点击手势会让拖动前几十毫秒被吞掉，手感变涩。
                            .pointerInput(order) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = index; dragOffset = 0f },
                                    onDragEnd = {
                                        // 每拖过半行就换一格：比「必须拖满一行」更跟手，
                                        // 也不至于因为手指抖动而误换位。
                                        val shift = (dragOffset / rowHeightPx).toInt()
                                        if (shift != 0) {
                                            val moved = order.toMutableList()
                                            val target = (index + shift).coerceIn(0, moved.lastIndex)
                                            if (target != index) {
                                                val item = moved.removeAt(index)
                                                moved.add(target, item)
                                                order = moved
                                                settings.channelPriority = moved
                                                RunLog.info("Channel", "priority moved ${kind.name} index=$index->$target")
                                            }
                                        }
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        dragOffset += drag.y
                                    },
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 拖动手柄：给出「这里可以按住拖」的可见提示（三点横纹）。
                        Column(
                            Modifier.widthIn(min = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val gripColor =
                                if (isDragging) accents.accent else MiuixTheme.colorScheme.onBackgroundVariant
                            repeat(3) {
                                Box(Modifier.width(14.dp).height(2.dp).background(gripColor))
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("${index + 1}", fontSize = 13.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(kind.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (entries.isEmpty()) "未配置" else entries.joinToString("、") { it.label },
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 这一档的第一条已保存通道的实测结果。同档多条时逐条列在下面「单条状态」区。
                        ChannelStatusDot(entries.firstOrNull()?.let { health[it.id] })
                    }
                }

                // 逐条通道的实测状态：同一种 kind 下有几条路（比如两个不同家宽）时，
                // 只看档位汇总会掩盖「哪一条真的通」，所以展开到每一条。
                val reachableProfiles = profiles.filter { it.kind != ConnectionProfile.Kind.USB }
                if (reachableProfiles.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text("每条通道实测", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    reachableProfiles.forEach { profile ->
                        ChannelHealthRow(
                            profile = profile,
                            health = health[profile.id],
                            // 网关自报的状态只作为**补充说明**展示：它能区分
                            // 「中转服务侧波动（用户什么都不用改）」和「电脑配置问题」。
                            gatewayNote = gateway[profile.id]
                                ?.takeIf { it.category != "ok" }
                                ?.summary,
                            onUse = {
                                // 用户自己点了切换 = 明确的干预 → 自动选路暂停 30 分钟。
                                // 写在这里而不散落到各调用点，才不会漏掉任何一条手动路径。
                                settings.noteManualChannelSwitch()
                                onSwitchConnection(profile.id)
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        probing -> "正在测各通道…"
                        lastProbeAt > 0 -> "上次实测：" + java.text.SimpleDateFormat(
                            "HH:mm:ss", java.util.Locale.getDefault(),
                        ).format(java.util.Date(lastProbeAt))
                        else -> "还没实测"
                    },
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                Spacer(Modifier.width(10.dp))
                Button(onClick = { if (!probing) scope.launch { probeAll() } }) { Text("重新实测") }
            }

            // 优化建议：用户说的「不影响观感、非频繁」，所以这里是一行内联提示，
            // 不是弹窗。弹窗会打断他正在输入的内容。
            val propose = suggestion
            if (propose != null) {
                Spacer(Modifier.height(4.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("检测到更快的通道", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "$suggestionReason。切换会重新加载页面，未发送的内容请先保存。",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                settings.silenceChannelPrompt()
                                suggestion = null
                                onSwitchConnection(propose.id)
                            }) { Text("切到${propose.kind.title}") }
                            Button(onClick = {
                                // 拒绝一次 → 30 分钟内不再弹。落盘，App 杀掉重启也记得。
                                settings.silenceChannelPrompt()
                                RunLog.info("Channel", "suggestion declined by user")
                                suggestion = null
                            }) { Text("暂不（30 分钟内不再问）") }
                        }
                    }
                }
            }

            Text(
                "顶部延迟来自 DSH 入口 HTTP 检测；这里显示的是每条通道各自能不能到达电脑。",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
}

private val ROW_HEIGHT = 56.dp

/** 一种通道的档位汇总状态点。 */
@Composable
private fun ChannelStatusDot(health: ChannelHealth?) {
    val accents = LocalDshAccents.current
    val (color, text) = when {
        health == null -> MiuixTheme.colorScheme.onBackgroundVariant to "未测"
        health.available -> accents.success to (health.elapsedMs.takeIf { it > 0 }?.let { "$it ms" } ?: "可用")
        else -> accents.error to "不可用"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 11.sp, color = color)
    }
}

/** 单条通道的实测行：地址、结论、以及「就用这条」。 */
@Composable
private fun ChannelHealthRow(
    profile: ConnectionProfile,
    health: ChannelHealth?,
    gatewayNote: String? = null,
    onUse: () -> Unit,
) {
    val accents = LocalDshAccents.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        health == null -> MiuixTheme.colorScheme.onBackgroundVariant
                        health.available -> accents.success
                        else -> accents.error
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                // 不可用时把原因写出来（超时 / TLS 不通 / 被服务端拒绝），
                // 而不是只标一个红点让用户猜。
                health?.detail?.takeIf { it.isNotBlank() } ?: profile.kind.title,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 电脑侧的看法与手机侧不一致时，把电脑侧的说法补上：
            // 「中转服务侧波动」= 等它自己好；「配置不可用」= 去查电脑。
            // 用户明确要求过别让他为中转服务侧的锅折腾自己。
            if (gatewayNote != null) {
                Text(
                    "电脑侧：$gatewayNote",
                    fontSize = 11.sp,
                    color = accents.waiting,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onUse) { Text("使用") }
    }
}

@Composable
private fun InfoLine(title: String, subtitle: String, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, fontSize = 12.sp, color = color)
        Text(subtitle, fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
    }
}

/**
 * 优化提示条（非阻塞版）。
 *
 * 与卡片里那条建议同一套规则，但可以在**页面内容顶部**出现：用户说的是
 * 「不影响观感、非频繁」，所以它是连接状态条旁边的一行提示，不是弹窗 ——
 * 看见就看见，没看见也不会错过任何东西，而且永远不会挡住输入框。
 */
@Composable
fun ChannelSuggestionBar(
    target: ConnectionProfile,
    reason: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    deadlineMs: Long = 20_000,
    onTimeout: () -> Unit = onDecline,
) {
    // 2026-11-04 用户明确要求：
    //   「那个通道提醒太大个了，我是想缩小字体和界面，整合到状态栏上面，
    //     需要提醒的时候就切换提醒界面，然后有倒计时，
    //     倒计时结束用户没有作出操作自动默认不变」
    // 所以这里改成一条紧凑状态条：单行、11-13sp、高约 40dp，
    // 右侧带倒计时，走完自动按「保持不变」处理。
    val accents = LocalDshAccents.current

    // 倒计时：每秒减一，到 0 触发 onTimeout（默认等于"不变"）。
    // remember 的 key 带上 target.id，换了建议就重置计时，不会接着上一条走。
    var remaining by remember(deadlineMs, target.id) { mutableStateOf((deadlineMs / 1000).toInt()) }
    LaunchedEffect(deadlineMs, target.id) {
        if (deadlineMs <= 0) return@LaunchedEffect
        remaining = (deadlineMs / 1000).toInt()
        while (remaining > 0) {
            kotlinx.coroutines.delay(1_000)
            remaining -= 1
        }
        onTimeout()
    }

    Card(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accents.accent))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                // 一行说完：通道名 + 理由。超长省略，绝不换行把条撑高。
                Text(
                    "可切到 ${target.kind.title}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    reason,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (deadlineMs > 0) {
                // 让用户知道"不理它就自己消失"，不必被迫当场做决定。
                Text("${remaining}s", fontSize = 11.sp, color = MiuixTheme.colorScheme.onBackgroundVariant)
                Spacer(Modifier.width(6.dp))
            }
            // 用小字点击区而不是实心 Button：实心按钮会把这条撑高。
            Text(
                "切换",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accents.accent,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAccept).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "不用",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDecline).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 保命切换的征询条（带倒计时，非阻塞）。
 *
 * 与 [ChannelSuggestionBar] 的区别在**语义**，不是样式：
 *  - 建议条：有更快的路 → 只提示，从不自动执行，倒计时走完＝保持不变
 *  - 本组件：当前路看着不通了 → 倒计时走完＝**执行切换**，用户可随时点「继续用」否决
 *
 * 用户 2026-11-05 的要求：「要加一个提示，然后还有一个倒计时，倒计时结束之后再自动
 * 去切换，然后用户也可以选择去拒绝继续走 VPN 的通道。就比如说我现在这种情况，
 * 我就是想试试 VPN 的通道到底能不能走得通」。
 *
 * 所以这条必须做到三件事：
 *  1. 明确写出「还剩几秒就切」，让倒计时是有意义的预期，而不是偷袭；
 *  2. 「继续用当前通道」是随时可点的，点了就真的停下来（调用方记手动暂停窗口）；
 *  3. 视觉上仍是紧凑一行 —— 用户之前抱怨过提示条太大、挤动网页。
 */
@Composable
fun ChannelConfirmBar(
    title: String,
    detail: String,
    secondsLeft: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    acceptText: String = "立即切换",
    declineText: String = "继续用当前通道",
) {
    val accents = LocalDshAccents.current
    Card(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 用等待色而不是强调色：这是「有事要你决定」，不是「有个好建议」。
            Box(Modifier.size(6.dp).clip(CircleShape).background(accents.waiting))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(detail, fontSize = 11.sp, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onBackgroundVariant)
            }
            Spacer(Modifier.width(8.dp))
            // 倒计时：让「自己会切走」成为可见的预期。
            Text(
                if (secondsLeft > 0) "${secondsLeft}s" else "…",
                fontSize = 11.sp,
                color = accents.waiting,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                acceptText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accents.accent,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onAccept)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                declineText,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDecline)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 设置页首页的一行入口（供 ShellScreen / SettingsScreen 挂载）。
 *
 * 为什么单独给一个入口组件：接线只需要在这一个地方加一行，
 * 不用去动别的页面的内部结构，减少与另一路改动的冲突面。
 */
@Composable
fun ChannelSettingsEntry(settings: SettingsStore, onClick: () -> Unit) {
    val accents = LocalDshAccents.current
    val current = settings.activeProfile
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp).pressClickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("通道自动选路", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        !settings.channelAutoSwitch -> "已关闭 · 只用手动选择的通道"
                        current == null -> "配对后自动选可用入口；当前还没有连接"
                        else -> "当前 ${current.kind.title} · 挂了自动换一条能用的路"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                )
            }
            Text("›", fontSize = 26.sp, color = accents.accent)
        }
    }
}

/**
 * 自动选路的宿主：启动主循环 + 渲染优化建议条。
 *
 * ## 为什么做成一个 Composable
 *
 * 主循环必须在页面层启动（它要知道「页面在不在前台」「正在不在切」「有没有任务在跑」，
 * 还要能把切换请求交给已有的草稿确认流程）。但这些状态和函数都定义在
 * `DshMobileApp.kt` 里——那个文件这一轮有别的工作在动。
 *
 * 所以把整块逻辑收进这个函数，调用方只需要加**一行**：
 *
 * ```
 * ChannelFailoverHost(
 *     settings = settings,
 *     enabled = configuredOrigin.isNotBlank(),
 *     canProbe = { !switching && !taskBusy },
 *     onSwitch = { target -> navigate { ... } },   // 必须走草稿确认
 * )
 * ```
 *
 * 这样接线不碰任何既有逻辑，也保证了「切之前确认草稿」这条硬约束由**调用方**
 * 用自己的流程实现 —— 本文件绝不直接改地址、绝不直接重载页面。
 *
 * @param enabled 已配对、可以开始巡检。false 时空转（不探测、不打扰）。
 * @param canProbe 现在能不能探测/切换（页面在前台、没在切换、没任务在跑）。
 * @param onSwitch 执行切换。**调用方必须先确认没有未发送草稿**。
 */
@Composable
fun ChannelFailoverHost(
    settings: SettingsStore,
    enabled: Boolean,
    canProbe: () -> Boolean,
    onSwitch: (ConnectionProfile) -> Unit,
    /**
     * 建议的外部接管点（2026-11-04 用户要求把提示整合进状态栏）。
     *
     * 非 null 时：本宿主**不再弹 Popup**，把建议交给调用方渲染
     * （ShellScreen 用它替换状态条第二行，不新增浮层、不挤动网页）。
     * null 时退回 Popup 行为，旧调用点不受影响。
     */
    onSuggest: ((ConnectionProfile, String) -> Unit)? = null,
) {
    var suggestion by remember { mutableStateOf<Pair<ConnectionProfile, String>?>(null) }
    // 保命切换的征询：目标通道 + 理由 + 剩余秒数。
    //
    // 与 suggestion 分开是因为**语义不同**：
    //  - suggestion = 「有更快的路，要不要换」→ 纯建议，绝不自动执行
    //  - confirm    = 「这条路看着不通了，要不要换」→ 带倒计时，到点自动切
    // 用户 2026-11-05：「要加一个提示，然后还有一个倒计时，倒计时结束之后再自动
    // 去切换，然后用户也可以选择去拒绝继续走 VPN 的通道。就比如说我现在这种情况，
    // 我就是想试试 VPN 的通道到底能不能走得通」。
    // 所以拒绝必须是**一等公民**：点了就真的不再打扰，而不是下次探测又弹。
    var confirmRequest by remember { mutableStateOf<Triple<ConnectionProfile, String, Int>?>(null) }
    var countdown by remember { mutableStateOf(0) }
    val propose = suggestion
    val request = confirmRequest
    // 开关关掉时立刻收掉残留的建议与征询：用户明确说了「别管我」。
    // 用 LaunchedEffect 而不是直接赋值：composition 期间写 state 会触发重组风暴。
    LaunchedEffect(enabled) {
        if (!enabled) {
            suggestion = null
            confirmRequest = null
        }
    }
    // 倒计时：每秒减一，到 0 时执行切换。
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        countdown = request.third
        while (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
        // 走完没人点 → 按提示上写的那样执行切换。
        // 这不是擅自行动：提示全程显示剩余秒数，用户有整整 15 秒可以点「继续用」。
        val pending = confirmRequest
        if (pending != null) {
            RunLog.info("Channel", "confirm-switch countdown elapsed; switching now")
            confirmRequest = null
            onSwitch(pending.first)
        }
    }
    // 外部接管：把建议交出去，本宿主不再弹 Popup。
    LaunchedEffect(propose, onSuggest) {
        if (onSuggest != null && propose != null) onSuggest.invoke(propose.first, propose.second)
    }
    // 建议条 / 征询条用 Popup 浮在页面顶部，**完全不参与父布局**。
    //
    // 为什么不能用普通 Composable：这个宿主挂在页面根部，普通 Composable 会挤进
    // 父布局、把网页顶下去。Popup 是独立窗口，既不顶动布局，也不拦截下方触摸。
    // 调用方传了 onSuggest 时改用状态条渲染，那时建议条这里必须**不弹**（否则重复）。
    val ask = request
    if (ask != null) {
        androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter) {
            ChannelConfirmBar(
                title = "当前通道可能不稳",
                detail = ask.second,
                secondsLeft = countdown,
                acceptText = "立即切换",
                declineText = "继续用当前通道",
                onAccept = {
                    RunLog.info("Channel", "confirm-switch accepted by user")
                    val target = ask.first
                    confirmRequest = null
                    onSwitch(target)
                },
                onDecline = {
                    // 用户明确要留在当前通道 → 记进手动暂停窗口，30 分钟内不再打扰。
                    // 这正是「我只想试试 VPN 到底能不能通」所需要的：给他一段安静的时间。
                    val hold = com.dsh.mobile.core.ChannelFailoverEngine.MANUAL_HOLD_MS
                    settings.noteManualChannelSwitch()
                    RunLog.info("Channel", "confirm-switch declined; holding current channel ${hold / 60_000} min")
                    confirmRequest = null
                },
            )
        }
    } else if (propose != null && onSuggest == null) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.TopCenter,
            // 刻意不接 onDismissRequest（不设 focusable）：点别处不静默、也不消失。
            // 用户可能只是点了一下网页，那不代表他拒绝了这条建议。
        ) {
            ChannelSuggestionBar(
                target = propose.first,
                reason = propose.second,
                onAccept = {
                    settings.silenceChannelPrompt()
                    val target = propose.first
                    suggestion = null
                    onSwitch(target)
                },
                onDecline = {
                    settings.silenceChannelPrompt()
                    RunLog.info("Channel", "suggestion declined; silenced ${com.dsh.mobile.core.ChannelFailoverEngine.PROMPT_SILENCE_MS / 60_000} min")
                    suggestion = null
                },
            )
        }
    }

    if (!enabled) return
    // 主循环跟着「是否已配对/开关是否打开」启停；内部自己处理退避与暂停。
    LaunchedEffect(enabled) {
        com.dsh.mobile.core.ChannelFailover.start(
            scope = this,
            settings = settings,
            canProbe = canProbe,
            onSwitch = { target, reason ->
                RunLog.info("Channel", "auto failover to ${target.kind.title}: $reason")
                onSwitch(target)
            },
            onSuggest = { target, reason -> suggestion = target to reason },
            // 保命切换：先问，不直接切。
            onConfirmSwitch = { target, reason, seconds ->
                RunLog.info("Channel", "confirm-switch raised for ${target.kind.title}; asking user")
                confirmRequest = Triple(target, reason, seconds)
            },
        ).join()
    }
}
