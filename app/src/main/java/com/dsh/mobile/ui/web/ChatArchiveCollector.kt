package com.dsh.mobile.ui.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dsh.mobile.bridge.DshBridge
import com.dsh.mobile.core.ChatArchive
import com.dsh.mobile.core.ChatArchiveStore
import com.dsh.mobile.core.ChatRole
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对话本地缓存的采集器（**实验功能，默认关闭**）。
 *
 * 工作方式：页面就绪后每隔 [ChatArchive.CAPTURE_INTERVAL_MS] 调一次网页里已有的
 * `__DSH_OVERLAY_CHAT__.read()`（悬浮助手同一个提取器），把可见正文交给
 * [ChatArchiveStore] 归档。它只**读**页面，不改页面、不发请求、不注入新脚本。
 *
 * 边界（这些是"不做"而不是"还没做"）：
 *  - 开关关闭时整体短路：不注册定时器、不建目录、不写任何文件。
 *  - 只在主界面就绪时采集；后台不跑，避免无谓耗电与把后台状态当历史。
 *  - 离线时不采集（本来也没有新内容），只读已有归档。
 *  - 采集失败一律静默降级：缓存是附加能力，绝不能影响正常连接与页面。
 */
object ChatArchiveCollector {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private var store: ChatArchiveStore? = null
    private var contextRef: Context? = null
    private var bridgeProvider: (() -> DshBridge?) = { null }

    /** 最近一次成功采集的时间；0 表示还没采到过。 */
    @Volatile var lastCaptureAt: Long = 0L
        private set

    /** 最近一次采集到的会话标题，设置页展示用。 */
    @Volatile var lastTitle: String = ""
        private set

    /**
     * 可观测状态：让用户看得见"到底有没有在同步"。
     *
     * 缓存是个后台行为，
     * 没有可见反馈就等于不存在。这里把内部真实计数暴露出来，不做装饰性动画。
     *
     * @param running 采集器是否在跑（开关开启且页面就绪）
     * @param pendingMessages 已收到但还没落盘的消息条数
     * @param savedSessions 本次运行已落盘的会话数
     * @param lastCaptureAt 上次成功落盘时间（0=从未）
     * @param lastTitle 上次落盘的会话标题
     * @param lastError 上次失败原因（空=没失败）
     */
    data class Status(
        val running: Boolean = false,
        val pendingMessages: Int = 0,
        val savedSessions: Int = 0,
        val lastCaptureAt: Long = 0L,
        val lastTitle: String = "",
        val lastError: String = "",
    )

    /** 本次运行已落盘的会话数（跨采集周期累计，用于"已同步 N 个会话"）。 */
    private val savedSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 最近一次失败原因，成功一次就清空。 */
    @Volatile private var lastError: String = ""

    /** 待更新会话数（网页上有、本地还没补全的）。用于界面如实显示"还有几个没更新"。 */
    @Volatile var pendingUpdates: Int = 0
        private set

    /**
     * 启动/重连后检查一次：网页上哪些会话还没缓存、哪些已缓存（打开时会补）。
     *
     * **不主动切换用户的会话** —— 那会打断用户正在做的事。只做检查与计数，
     * 真正的补全发生在这个会话被打开时（事件流 + DOM 快照）。
     * 这样界面上能如实说"还有 N 个待更新"，而不是假装已经全部同步。
     */
    fun checkForUpdates(context: Context) {
        val settings = SettingsStore(context)
        if (!settings.chatArchiveEnabled) {
            pendingUpdates = 0
            return
        }
        loadSessionList(context) { remote ->
            if (remote.isEmpty()) {
                RunLog.info("ChatArchive", "update check skipped: no session list")
                return@loadSessionList
            }
            val settings2 = SettingsStore(context)
            val selected = settings2.chatArchiveSelectedSessions
            // 只统计用户关心的范围（全部 / 勾选的）
            val relevant = if (settings2.chatArchiveSelection == ChatArchive.Selection.SELECTED)
                remote.filter { it.key in selected } else remote
            // 本地归档 id 是 sessionKey 的摘要，与侧栏 row-key 不同源，不能直接比；
            // 用标题近似匹配（同一对话的标题稳定），匹配不上就算"还没缓存"。
            val cachedTitles = list(context).map { it.title }.toSet()
            val never = relevant.count { it.title !in cachedTitles }
            pendingUpdates = never
            RunLog.info(
                "ChatArchive",
                "update check: remote=${remote.size} relevant=${relevant.size} cached=${cachedTitles.size} never=$never",
            )
        }
    }

    /** 读当前可观测状态。设置页轮询它来显示进度。 */
    fun status(): Status = Status(
        running = running.get(),
        pendingMessages = transcripts.values.sumOf { it.size },
        savedSessions = savedSessionIds.size,
        lastCaptureAt = lastCaptureAt,
        lastTitle = lastTitle,
        lastError = lastError,
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            // 断网时不采：既没有新内容，也会让 bridge 走一遍必然失败的路径。
            // 不在这里 stop()：网络恢复后下一轮自动继续，不需要外部再触发一次。
            val hasNetwork = runCatching {
                com.dsh.mobile.core.NetworkConnectivity.state.value.available
            }.getOrDefault(true)
            if (hasNetwork && pageAlive) captureOnce()
            main.postDelayed(this, ChatArchive.CAPTURE_INTERVAL_MS)
        }
    }

    /**
     * 绑定上下文与 bridge 提供者。页面创建时调用一次即可。
     * 是否真正开始采集由 [sync] 按设置决定。
     */
    fun attach(context: Context, bridge: () -> DshBridge?) {
        contextRef = context.applicationContext
        bridgeProvider = bridge
    }

    /**
     * 按当前设置决定启停。设置页切换开关、页面就绪、回到前台时都应调用。
     *
     * @param active 页面是否已就绪（bridge 起来且看到界面）。未就绪时只停不启，
     *   避免在空白页上抓一堆"尚未就绪"文本写进历史。
     */
    fun sync(active: Boolean) {
        val context = contextRef ?: return
        sync(context, active)
    }

    /**
     * 设置页开启开关时的入口：此时页面可能早就加载完、且本进程尚未 attach 过
     * （用户直接进设置开的）。用传入的 context 补上，避免采集器永远起不来。
     */
    fun sync(context: Context, active: Boolean) {
        if (contextRef == null) contextRef = context.applicationContext
        val ctx = contextRef ?: return
        val settings = SettingsStore(ctx)
        if (!settings.chatArchiveEnabled) {
            stop()
            return
        }
        if (store == null) store = storeFor(ctx)
        // 开关刚打开时页面往往早就加载完了，不会再走一次"页面就绪"回调，
        // 所以这里不硬性要求 pageAlive：active 由调用方声明"页面现在可用"。
        // captureOnce 自己还会校验 snapshot.ready 与 context，抓不到就什么都不写。
        if (active) {
            pageAlive = true
            start()
        } else if (pageAlive) start()
    }

    /** 页面是否还在（WebView 存活）。页面销毁时置 false，避免对着死页面空转抓取。 */
    @Volatile private var pageAlive = false

    /** 页面创建/就绪时标记存活；销毁时由 [detachPage] 清掉。 */
    fun markPageAlive() {
        pageAlive = true
    }

    private fun start() {
        if (running.compareAndSet(false, true)) {
            main.removeCallbacks(tick)
            // 页面刚就绪，稍等再抓第一次，避免抓到渲染中的半成品。
            main.postDelayed(tick, 3_000)
            RunLog.info("ChatArchive", "collector started")
        }
    }

    private fun stop() {
        if (running.compareAndSet(true, false)) {
            main.removeCallbacks(tick)
            RunLog.info("ChatArchive", "collector stopped")
        }
    }

    /** 页面离开或销毁时调用。 */
    fun detachPage() {
        pageAlive = false
        stop()
    }

    /** 立即抓一次（"刷新统计"等手动场景）。 */
    fun captureNow() {
        // 手动同步必须真的做点什么并留下痕迹，不能在"还没初始化"时静默返回 ——
        // 那样用户点了按钮毫无反馈，正是"进度不透明"的根源。
        val ctx = contextRef
        if (store == null && ctx != null) store = storeFor(ctx)
        val store = store
        if (store == null) {
            lastError = "尚未初始化：请先连接工作台"
            RunLog.info("ChatArchive", "manual sync skipped: no context yet")
            return
        }
        if (!running.get()) {
            // 采集器没跑（例如页面还没就绪）：先记下原因，再尽力抓一次页面快照。
            lastError = "采集未运行：需要先连接工作台（页面就绪后才开始）"
            captureFromDom(store)
            return
        }
        captureOnce()
        RunLog.info("ChatArchive", "manual sync requested; pending=${transcripts.values.sumOf { it.size }}")
    }

    /**
     * 累积中的消息（按会话标识分组）。
     *
     * 为什么用事件流累积而不是每次从 DOM 重抓：事件带显式角色与时间，能区分
     * "谁说的"，而 DOM 抓到的是一坨同格式文本（真机验证过，分不出来）。
     * 累积只在内存里，落盘仍走 [storeFor] 的加密存储。
     */
    private val transcripts = java.util.concurrent.ConcurrentHashMap<String, MutableList<ChatRole.Message>>()

    /** 诊断用：每种事件类型只记一次日志，避免刷屏（排查"为什么没归档"时很有用）。 */
    private val debugSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 诊断用：累计收到的事件条数。 */
    private val eventSeen = java.util.concurrent.atomic.AtomicInteger()

    /** 当前会话标识（事件里没带会话 id 时用于归属）。 */
    @Volatile private var currentSessionKey: String = ""

    /**
     * 收到一条原始事件。只做**累积**，不落盘（落盘由 [tick] 统一做，
     * 避免每条消息都写一次磁盘）。
     *
     * 任何解析失败都静默忽略：归档是附加能力，不能影响主链路的事件处理。
     */
    fun onEvent(raw: String) {
        // 诊断：只记**类型与长度**，绝不记正文。
        // 早先这里是 `head=${raw.take(160)}`，会把对话正文（用户输入与助手回复）
        // 直接写进 logcat —— 与「日志不含对话正文」的既有隐私边界冲突，已删。
        if (eventSeen.incrementAndGet() <= 2) {
            RunLog.info("ChatArchive", "onEvent#${eventSeen.get()} bytes=${raw.length}")
        }
        // **不受 running 限制**：页面加载时会重放历史事件，而采集器通常在
        // "页面就绪"之后才启动 —— 那段窗口里的事件必须也收下，否则一进会话
        // 就只有 DOM 兜底的旧内容，缓存永远不更新（真机踩到：启动日志晚于事件）。
        runCatching {
            val root = Json.parseToJsonElement(raw).jsonObject
            val value = root["value"] as? JsonObject ?: return@runCatching
            // 真机观察到的 value.type 有 ready / baseline / providers / event 等。
            // **只有 "event" 里面才是会话事件**，其余（ready/baseline/providers/…）
            // 一律忽略 —— 早先把非 event 也当事件解析，结果全部落空，
            // 于是永远只有 DOM 兜底的旧内容（"缓存不更新"的真因之一）。
            val valueType = (value["type"] as? JsonPrimitive)?.contentOrNull
            if (valueType != "event") return@runCatching
            val inner = value["event"] as? JsonObject ?: return@runCatching
            val type = (inner["type"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching
            val data = inner["data"] as? JsonObject ?: return@runCatching
            val at = (inner["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: System.currentTimeMillis()

            // 诊断：每种类型只记一次，确认收到了哪些事件。
            if (debugSeen.add(type)) {
                RunLog.info("ChatArchive", "event type=$type keys=${data.keys.take(10)}")
            }

            // 会话切换：从事件的 sessionId 或页面 context 更新归属。
            val session = (data["sessionId"] as? JsonPrimitive)?.contentOrNull
                ?: (inner["sessionId"] as? JsonPrimitive)?.contentOrNull
            if (!session.isNullOrBlank()) currentSessionKey = session

            val message = ChatRole.fromEvent(type, data, at) ?: return@runCatching
            if (message.text.isBlank()) return@runCatching
            val key = currentSessionKey.ifBlank { "active" }
            // 用户选了"只缓存勾选的会话"时，未勾选的会话一律跳过（连内存都不占）。
            val ctx = contextRef
            if (ctx != null) {
                val settings = SettingsStore(ctx)
                if (!ChatArchive.shouldCapture(
                        settings.chatArchiveSelection,
                        key,
                        settings.chatArchiveSelectedSessions,
                    )
                ) return@runCatching
            }
            val list = transcripts.getOrPut(key) { mutableListOf() }
            synchronized(list) {
                // 去重：同一角色+正文+时间视为重复投递（事件会重放）。
                if (list.none { it.role == message.role && it.text == message.text && it.at == message.at }) {
                    list += message
                    // 单会话上限，避免长时间运行时内存无限增长。
                    while (list.size > MAX_MESSAGES) list.removeAt(0)
                }
            }
        }
    }

    /**
     * 把内存里累积的消息落盘。由 [tick] 每 45 秒调一次（不是每条消息都写磁盘）。
     *
     * 若累积为空（刚启动、老会话的事件流已经过去），退回 DOM 抓取一次作兜底 ——
     * 这样"打开一个已经聊完的会话"也能留下快照， хотя角色只能记未知。
     */
    private fun captureOnce() {
        val store = store ?: return
        val pending = transcripts.entries
            .filter { it.value.isNotEmpty() }
            .map { entry -> entry.key to synchronized(entry.value) { entry.value.toList() } }
        if (pending.isEmpty()) {
            captureFromDom(store)
            return
        }
        var savedAny = false
        var failed: String? = null
        for ((key, messages) in pending) {
            val transcript = ChatRole.Transcript(messages)
            if (transcript.isEmpty()) continue
            val archive = store.save(
                ChatArchive.Capture(
                    sessionKey = key,
                    title = titleFor(key),
                    text = transcript.encode(),
                    capturedAt = System.currentTimeMillis(),
                )
            )
            if (archive != null) {
                savedAny = true
                savedSessionIds += key
                lastTitle = archive.title
            } else {
                failed = "写入失败（可能是存储空间或加密不可用）"
            }
        }
        if (savedAny) {
            lastCaptureAt = System.currentTimeMillis()
            lastError = ""
        } else if (failed != null) {
            // 失败必须可见：静默失败会让用户以为在同步，其实一条都没写进去。
            lastError = failed!!
            RunLog.warn("ChatArchive", "capture failed: $failed")
        }
    }

    /** 兜底：事件流还没收到内容时，从页面现状抓一次（尽量带上角色）。 */
    private fun captureFromDom(store: ChatArchiveStore) {
        val bridge = bridgeProvider() ?: return
        // 优先用带角色的 DOM 抽取：只抓整段 innerText 会全部记成"无角色"，
        // 而对话本来只有「我」和「助手」两个角色。
        val ctx = contextRef
        val currentKey = currentSessionKey.ifBlank { "active" }
        if (ctx != null) {
            captureDomWithRoles(ctx, currentKey) { ok ->
                if (ok) return@captureDomWithRoles
                // 带角色抽取失败才退回整段文本
                legacyDomCapture(bridge, store)
            }
            return
        }
        legacyDomCapture(bridge, store)
    }

    /** 旧兜底：整段 innerText（角色只能记未知）。 */
    private fun legacyDomCapture(bridge: DshBridge, store: ChatArchiveStore) {
        runCatching {
            bridge.overlayChat(null) { snapshot ->
                runCatching {
                    if (snapshot == null || !snapshot.optBoolean("ready", false)) return@overlayChat
                    val contextKey = snapshot.optString("context")
                    if (contextKey.isBlank()) return@overlayChat
                    val cleaned = ChatArchive.cleanChrome(snapshot.optString("text"))
                    if (!ChatArchive.looksLikeConversation(cleaned)) {
                        RunLog.info("ChatArchive", "skipped: page holds only chrome, not conversation")
                        return@overlayChat
                    }
                    val archive = store.save(
                        ChatArchive.Capture(
                            sessionKey = contextKey,
                            title = deriveTitle(snapshot),
                            text = cleaned,
                            capturedAt = System.currentTimeMillis(),
                        )
                    )
                    if (archive != null) {
                        lastCaptureAt = System.currentTimeMillis()
                        lastTitle = archive.title
                        savedSessionIds += contextKey
                        lastError = ""
                    } else {
                        lastError = "写入失败（可能是存储空间或加密不可用）"
                    }
                }
            }
        }.onFailure {
            // DOM 兜底失败不该污染"上次同步失败"状态：后台拉取成功时它只是附加尝试。
            RunLog.warn("ChatArchive", "dom capture skipped ${it.javaClass.simpleName}")
        }
    }

    /**
     * 读取网页侧栏的会话列表（标题 + 时间 + 标识），供「选择要同步的对话」窗口用。
     *
     * 只取标题和时间，不加载正文 —— 用户只需要能认出是哪条对话。
     * 拿不到时返回空列表，由 UI 说明"请先打开左侧会话列表"，不编造。
     */
    fun loadSessionList(context: Context, onResult: (List<ArchiveSession>) -> Unit) {
        val bridge = bridgeProvider()
        val view = bridge?.webViewForArchive()
        if (view == null) {
            onResult(emptyList())
            return
        }
        // **以 session/list 为准**：它返回"所有可见会话"（真机侧栏只渲染 8 条，
        // 而工作区里实际有 160+）。标题也由它自带（projections.values.title），
        // 不再依赖侧栏 DOM —— 见下面 mergeDomTitles 的线程问题说明。
        val listScript = runCatching {
            view.context.assets.open("archive-list.js").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (listScript == null) {
            onResult(emptyList())
            return
        }
        probeWaiter = waiter@{ payload ->
            // 回调一到就摘掉等待者：否则 15 秒超时分支会把同一个结果再投一次，
            // UI 侧会把列表重建两遍（用户看到列表闪一下）。
            probeWaiter = null
            val json = runCatching { JSONObject(payload) }.getOrNull()
            if (json == null || !json.optBoolean("ok")) {
                RunLog.warn("ChatArchive", "session/list failed: ${payload.take(200)}")
                onResult(emptyList())
                return@waiter
            }
            val arr = json.optJSONArray("sessions")
            val list = ArrayList<ArchiveSession>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val sid = item.optString("sessionId")
                    if (sid.isBlank()) continue
                    // session/list 的 sessionId 就是 "session:xxx" 形式或裸 id，
                    // 统一成侧栏同款 key，后续按 session: 前缀处理。
                    val key = normalizeSessionKey(sid)
                    val at = item.optLong("updatedAt", 0L)
                    val rawTitle = item.optString("title").trim()
                    val cwd = item.optString("cwd").trim()
                    // 子智能体会话挂在父会话下面，单独标出来免得用户以为是自己的对话。
                    val group = when {
                        item.optString("origin") == "subagent" -> "子智能体"
                        cwd.isNotBlank() -> workspaceName(cwd)
                        else -> ""
                    }
                    list += ArchiveSession(
                        key = key,
                        // 接口没给标题时（title 为 null，例如还没有 session/title 事件）
                        // 用首条用户消息无法得知，就退回短 id：至少能区分，不显示空白行。
                        title = rawTitle.ifBlank { fallbackTitle(key) },
                        whenText = if (at > 0) timeAgo(at) else "",
                        group = group,
                        index = i,
                    )
                }
            }
            RunLog.info("ChatArchive", "session/list count=${list.size} titled=${list.count { !it.title.startsWith("会话 ") }}")
            // 侧栏 DOM 只用来**补充**分组名与时间（能读到就更好，读不到不影响标题）。
            // 它是可选增强，所以放在主线程发起，且失败绝不回退整张列表。
            mergeDomTitles(view, list, onResult)
        }
        main.postDelayed({
            if (probeWaiter != null) {
                probeWaiter = null
                RunLog.warn("ChatArchive", "session/list timeout")
                onResult(emptyList())
            }
        }, 15_000)
        runCatching {
            view.evaluateJavascript(listScript, null)
            view.evaluateJavascript(
                "window.__DSH_ARCHIVE_LIST__().then(function(r){try{DshNative.onBridgeMessage(" +
                    "JSON.stringify({kind:'archive-probe',payload:r}));}catch(e){}}); 'started'",
                null,
            )
        }.onFailure {
            probeWaiter = null
            onResult(emptyList())
        }
    }

    /**
     * 统一会话标识：网页给的 id 可能是裸 uuid，也可能是 `session:uuid`。
     * 全流程（侧栏 DOM / 勾选 / 拉取 / 归档文件名）都按 `session:` 前缀处理，
     * 这里收敛成唯一形式，避免"同一会话出现两条、勾了却拉不到"。
     */
    fun normalizeSessionKey(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("session:")) t else "session:$t"
    }

    /** 接口没给标题时的兜底：短 id，至少让用户能区分两条不同会话。 */
    private fun fallbackTitle(key: String): String = "会话 " + key.removePrefix("session:").takeLast(8)

    /**
     * 从工作目录取一个人类可读的分组名（末段目录名）。
     *
     * 只取**最后一段**：完整路径可能含用户名等个人信息，界面上没必要展示。
     */
    private fun workspaceName(cwd: String): String {
        val tail = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return tail.ifBlank { "" }.take(40)
    }

    /**
     * 用 DOM 侧栏**补充**标题与时间（纯可选增强，不再是标题的唯一来源）。
     *
     * **必须在主线程调用**：`WebView.evaluateJavascript` 只能在创建 WebView 的线程
     * （主线程）执行。本方法此前由 [onPullProbe] 直接调用 —— 而它是
     * `@JavascriptInterface`，跑在 WebView 的 JavaBridge 线程上，于是系统抛
     * `A WebView method was called on thread 'JavaBridge'`，异常又被 `onFailure`
     * 静默吞掉：真机上 340 条会话**一条标题都没补上**、分组全空。这里 post 回主线程根治。
     */
    private fun mergeDomTitles(
        view: android.webkit.WebView,
        list: List<ArchiveSession>,
        onResult: (List<ArchiveSession>) -> Unit,
    ) {
        val deliver = { merged: List<ArchiveSession> ->
            // 无论 DOM 成功与否都要交付：标题已由 session/list 提供，
            // 这里绝不能再出现"因为增强失败就返回空列表"的连带失败。
            onResult(merged)
        }
        // 用 WebView 自己的 post（等价于主线程，但天然绑定它的线程模型）。
        view.post {
            val source = sessionScript ?: runCatching {
                view.context.assets.open("archive-sessions.js").bufferedReader().use { it.readText() }
            }.getOrNull()?.also { sessionScript = it }
            if (source == null) {
                RunLog.warn("ChatArchive", "dom title merge skipped: archive-sessions.js missing")
                deliver(list)
                return@post
            }
            runCatching {
                view.evaluateJavascript("$source;JSON.stringify(window.__DSH_ARCHIVE_SESSIONS__())") { result ->
                    runCatching {
                        val dom = runCatching {
                            JSONObject(result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\"))
                        }.getOrNull()
                        val domArr = dom?.optJSONArray("sessions")
                        // DOM 里能拿到的 sessionId → (标题, 时间, 分组)
                        val byId = HashMap<String, Triple<String, String, String>>()
                        if (domArr != null) {
                            for (i in 0 until domArr.length()) {
                                val o = domArr.optJSONObject(i) ?: continue
                                val k = o.optString("key")
                                if (k.isBlank()) continue
                                byId[normalizeSessionKey(k)] = Triple(
                                    o.optString("title"), o.optString("when"), o.optString("group")
                                )
                            }
                        }
                        val merged = list.map { s ->
                            val hit = byId[s.key] ?: return@map s
                            s.copy(
                                // DOM 标题只在接口没给时才采用：接口的 title 是
                                // `session/title` 事件的原文，比侧栏渲染文本更干净。
                                title = s.title.takeIf { !it.startsWith("会话 ") } ?: hit.first.ifBlank { s.title },
                                whenText = s.whenText.ifBlank { hit.second },
                                group = s.group.ifBlank { hit.third },
                            )
                        }
                        RunLog.info("ChatArchive", "dom title merge: matched=${merged.count { it.group.isNotBlank() }}/${list.size}")
                        deliver(merged)
                    }.onFailure {
                        // 不再静默：这类失败曾整片吞掉标题，下次排查必须一眼看得见。
                        RunLog.warn("ChatArchive", "dom title merge failed: ${it.javaClass.simpleName}")
                        deliver(list)
                    }
                }
            }.onFailure {
                RunLog.warn("ChatArchive", "dom title merge could not start: ${it.javaClass.simpleName}")
                deliver(list)
            }
        }
    }

    /** 相对时间文案。 */
    private fun timeAgo(at: Long): String {
        val diff = System.currentTimeMillis() - at
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟"
            diff < 86_400_000 -> "${diff / 3_600_000}小时"
            diff < 30L * 86_400_000 -> "${diff / 86_400_000}天"
            else -> "${diff / (30L * 86_400_000)}个月"
        }
    }

    /**
     * 批量同步：逐个打开勾选的会话、等渲染、抓快照，最后切回原会话。
     *
     * 为什么必须这么做：App 只能看到**当前渲染的会话**（事件流不重放历史），
     * 所以"同步勾选的 5 个对话"必须真的把这 5 个逐个打开抓一遍。
     * 早先的 captureNow 只是把内存里当前会话的积压落盘，对其他会话无效，
     * 用户点了"开始同步"却什么都没发生（pending=0）。
     *
     * @param keys 要同步的会话 key（session:xxx）。
     * @param onProgress 进度回调：(已完成数, 总数, 当前会话标题)。
     * @param onDone 全部完成回调：(成功数, 失败数)。
     */
    fun syncSessions(
        context: Context,
        keys: List<String>,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
        onDone: (ok: Int, failed: Int) -> Unit,
    ) {
        val bridge = bridgeProvider()
        val view = bridge?.webViewForArchive()
        if (view == null) {
            onDone(0, keys.size)
            return
        }
        if (store == null) store = storeFor(context)
        val script = openScript ?: runCatching {
            view.context.assets.open("archive-open.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { openScript = it }
        if (script == null) {
            onDone(0, keys.size)
            return
        }
        // 记住原会话，同步完切回去 —— 不能让用户发现自己的对话被切走了。
        view.evaluateJavascript("$script;window.__DSH_ARCHIVE_CURRENT__()") { current ->
            val original = runCatching { JSONObject("{\"k\":$current}").optString("k") }.getOrNull().orEmpty()
            RunLog.info("ChatArchive", "batch sync start count=${keys.size} original=$original")
            batchSync(view, script, keys, 0, 0, 0, original, onProgress, onDone)
        }
    }

    /**
     * 用**原生触摸事件**点击侧栏里的某个会话。
     *
     * 为什么不能用 JS dispatchEvent：真机验证过，模拟的 click/pointerdown 序列
     * React 一律不认（三次点不同会话，抓到的还是同一个 ctx）。React 的合成事件
     * 只接受来自浏览器输入管线的真实事件，所以必须走 dispatchTouchEvent。
     *
     * @return 是否找到了目标行并发出触摸。
     */
    private fun tapSessionRow(view: android.webkit.WebView, key: String): Boolean {
        val script = openScript ?: return false
        // 同步取回该行的屏幕坐标（evaluateJavascript 是异步的，这里用 CountDownLatch 收齐）。
        val latch = java.util.concurrent.CountDownLatch(1)
        var rectJson = ""
        main.post {
            runCatching {
                // 返回**已序列化的字符串**：evaluateJavascript 对字符串会再加一层引号，
                // 直接返回对象更省事（对象本身就是 JSON）。
                view.evaluateJavascript(
                    "$script;window.__DSH_ARCHIVE_RECT__(${org.json.JSONObject.quote(key)})",
                ) { result ->
                    rectJson = result.orEmpty()
                    latch.countDown()
                }
            }.onFailure { latch.countDown() }
        }
        if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) return false
        // evaluateJavascript 返回的是 JS 字面量：对象直接是 JSON，字符串会带引号。
        RunLog.info("ChatArchive", "row rect raw=${rectJson.take(160)}")
        val cleaned = rectJson.trim().let { raw ->
            when {
                raw.isEmpty() || raw == "null" -> return false
                raw.startsWith("\"") -> raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                else -> raw
            }
        }
        val rect = runCatching { JSONObject(cleaned) }.getOrNull() ?: return false
        val x = rect.optDouble("x", -1.0).toFloat()
        val y = rect.optDouble("y", -1.0).toFloat()
        if (x < 0f || y < 0f) return false
        // 在主线程投递真实触摸序列。
        var tapped = false
        val tapLatch = java.util.concurrent.CountDownLatch(1)
        main.post {
            runCatching {
                val now = android.os.SystemClock.uptimeMillis()
                val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
                view.dispatchTouchEvent(down)
                view.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
                tapped = true
            }
            tapLatch.countDown()
        }
        tapLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return tapped
    }

    private var openScript: String? = null

    /** 递归处理下一个会话（每次等页面渲染稳定后再抓）。 */
    private fun batchSync(
        view: android.webkit.WebView,
        script: String,
        keys: List<String>,
        index: Int,
        ok: Int,
        failed: Int,
        original: String,
        onProgress: (Int, Int, String) -> Unit,
        onDone: (Int, Int) -> Unit,
    ) {
        if (index >= keys.size) {
            // 切回原会话，别把用户的界面留在别人的对话里。
            if (original.isNotBlank()) {
                runCatching {
                    view.evaluateJavascript("$script;window.__DSH_ARCHIVE_OPEN__(${org.json.JSONObject.quote(original)})", null)
                }
            }
            RunLog.info("ChatArchive", "batch sync done ok=$ok failed=$failed")
            onDone(ok, failed)
            return
        }
        val key = keys[index]
        // 用原生触摸切换会话（JS 模拟事件 React 不认，真机验证过）。
        val tapped = tapSessionRow(view, key)
        if (!tapped) {
            RunLog.warn("ChatArchive", "batch skip: row not found key=${key.takeLast(16)}")
            main.post { batchSync(view, script, keys, index + 1, ok, failed + 1, original, onProgress, onDone) }
            return
        }
        // 等页面渲染这个会话的内容（切换会话是异步加载的）。
        main.postDelayed({
            captureCurrentSession(view) { saved, ctx ->
                // 关键校验：抓到的会话必须真的是刚才打开的那个。
                // 真机踩到过：JS 模拟点击不生效，两次抓到同一个会话，
                // 却都报"成功"—— 用户看到的同步结果全是假的。
                val expected = key.removePrefix("session:")
                val matched = ctx.contains(expected.take(12)) || expected.contains(
                    ctx.substringAfterLast('|').take(12)
                )
                if (saved && !matched) {
                    RunLog.warn(
                        "ChatArchive",
                        "batch capture mismatch: wanted=${expected.take(12)} got=${ctx.takeLast(24)}",
                    )
                }
                val okCapture = saved && matched
                val nextOk = if (okCapture) ok + 1 else ok
                val nextFailed = if (okCapture) failed else failed + 1
                onProgress(index + 1, keys.size, lastTitle)
                batchSync(view, script, keys, index + 1, nextOk, nextFailed, original, onProgress, onDone)
            }
        }, SESSION_SETTLE_MS)
    }

    /** 抓当前会话的 DOM 快照并落盘；回调 (是否成功, 实际抓到的会话标识)。 */
    private fun captureCurrentSession(view: android.webkit.WebView, done: (Boolean, String) -> Unit) {
        val store = store ?: run {
            RunLog.warn("ChatArchive", "batch capture: no store")
            return done(false, "")
        }
        val bridge = bridgeProvider() ?: run {
            RunLog.warn("ChatArchive", "batch capture: no bridge")
            return done(false, "")
        }
        // 回调可能因为页面卡住而不触发：兜一个超时，避免整批同步永远停住。
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        var capturedCtx = ""
        fun finish(ok: Boolean, why: String) {
            if (settled.compareAndSet(false, true)) {
                RunLog.info("ChatArchive", "batch capture result=$ok reason=$why")
                done(ok, capturedCtx)
            }
        }
        main.postDelayed({ finish(false, "timeout") }, CAPTURE_TIMEOUT_MS)
        runCatching {
            bridge.overlayChat(null) { snapshot ->
                runCatching {
                    if (snapshot == null) return@overlayChat finish(false, "null-snapshot")
                    if (!snapshot.optBoolean("ready", false)) return@overlayChat finish(false, "not-ready")
                    val contextKey = snapshot.optString("context")
                    if (contextKey.isBlank()) return@overlayChat finish(false, "no-context")
                    capturedCtx = contextKey
                    val cleaned = ChatArchive.cleanChrome(snapshot.optString("text"))
                    if (!ChatArchive.looksLikeConversation(cleaned)) return@overlayChat finish(false, "chrome-only")
                    val archive = store.save(
                        ChatArchive.Capture(
                            sessionKey = contextKey,
                            title = deriveTitle(snapshot),
                            text = cleaned,
                            capturedAt = System.currentTimeMillis(),
                        )
                    )
                    if (archive != null) {
                        lastCaptureAt = System.currentTimeMillis()
                        lastTitle = archive.title
                        savedSessionIds += contextKey
                        lastError = ""
                        finish(true, "saved")
                    } else {
                        lastError = "写入失败（可能是存储空间或加密不可用）"
                        finish(false, "save-failed")
                    }
                }
            }
        }.onFailure { finish(false, "exception-${it.javaClass.simpleName}") }
    }

    /** 单会话抓取超时：页面切不动时不要卡死整批。 */
    private const val CAPTURE_TIMEOUT_MS = 6_000L

    /** 切换会话后等页面渲染的时间：太短会抓到上一个会话，太长会拖慢批量。 */
    private const val SESSION_SETTLE_MS = 2_200L

    /**
     * 探测后台拉取接口是否可用（不切换 UI）。
     *
     * 这是新的同步方案前提：在页面上下文里 fetch 会话历史，
     * 而不是"点开会话抓 DOM"（那会让用户看到界面自己跳）。
     * 探不通就必须如实告诉用户，不能假装同步成功。
     */
    fun probePullEndpoint(context: Context, sessionId: String, onResult: (String) -> Unit) {
        val view = bridgeProvider()?.webViewForArchive()
        if (view == null) {
            onResult("no-webview")
            return
        }
        val script = pullScript ?: runCatching {
            view.context.assets.open("archive-pull.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { pullScript = it }
        if (script == null) {
            onResult("no-script")
            return
        }
        // evaluateJavascript 不会等待 Promise：异步结果必须通过**回调**送回原生。
        // 用法：JS 里探测完调用 window.DshNative.onBridgeMessage({kind:'archive-probe',...})。
        val probe = "$script;window.__DSH_ARCHIVE_PULL__(" +
            "${org.json.JSONObject.quote(sessionId)}).then(function(r){" +
            "try{DshNative.onBridgeMessage(JSON.stringify({kind:'archive-probe',payload:JSON.stringify(" +
            "{ok:r.ok,endpoint:r.endpoint||'',bytes:r.bytes||0,reason:r.reason||'',detail:(r.detail||'').slice(0,600)," +
            "head:(r.body||'').slice(0,400)})}));}" +
            "catch(e){}}); 'started'"
        // 超时保护：接口不通时不要让用户无限等。
        probeWaiter = onResult
        main.postDelayed({
            if (probeWaiter === onResult) {
                probeWaiter = null
                onResult("{\"ok\":false,\"reason\":\"timeout\"}")
            }
        }, 12_000)
        runCatching {
            view.evaluateJavascript(probe) { started ->
                RunLog.info("ChatArchive", "pull probe issued=$started")
            }
        }.onFailure {
            probeWaiter = null
            onResult("error:${it.javaClass.simpleName}")
        }
    }

    /**
     * 记录页面自己实际调用过的 /api 接口（找到"完整消息"接口的最可靠方式）。
     *
     * 为什么要这一步：projections 只给摘要（turnOutline 被截断）。完整消息一定
     * 有对应接口，页面打开对话时必然调用过它 —— 与其猜路径，不如把它记下来。
     * 只记录 URL（去掉 query），不读响应体，不干预请求。
     */
    fun installApiSpy(view: android.webkit.WebView) {
        val spy = spyScript ?: runCatching {
            view.context.assets.open("archive-spy.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { spyScript = it } ?: return
        runCatching { view.evaluateJavascript(spy, null) }
    }

    /** 读出页面命中过的接口清单，写进日志供分析。 */
    fun dumpApiHits(view: android.webkit.WebView) {
        runCatching {
            view.evaluateJavascript(
                "(function(){var s=window.__DSH_ARCHIVE_SPY__;return s?JSON.stringify(s.hits()):'{}';})()",
            ) { result ->
                RunLog.info("ChatArchive", "api hits=${result?.take(2000)}")
            }
        }
    }

    private var spyScript: String? = null

    /** 后台拉取脚本（archive-pull.js）的缓存正文。 */
    private var pullScript: String? = null

    /** 探测结果的等待者（同一时间只有一个探测）。 */
    @Volatile private var probeWaiter: ((String) -> Unit)? = null

    /**
     * 拉取专用的等待者（与 probeWaiter 分开）。
     *
     * 之前两者共用一个字段，导致「探测刚结束就发起拉取」时互相抢走回调，
     * 拉取永远收不到结果（真机现象：pull start 之后再无任何日志）。
     */
    @Volatile private var pullWaiter: ((String) -> Unit)? = null

    /** 从当前页面 DOM 抽取**带角色**的对话（替代只抓整段 innerText 的旧兜底）。 */
    fun captureDomWithRoles(context: Context, sessionKey: String, onDone: (Boolean) -> Unit) {
        val view = bridgeProvider()?.webViewForArchive()
        if (view == null) {
            onDone(false)
            return
        }
        val script = domScript ?: runCatching {
            view.context.assets.open("archive-dom.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { domScript = it }
        if (script == null) {
            onDone(false)
            return
        }
        val store = store ?: storeFor(context).also { store = it }
        runCatching {
            view.evaluateJavascript("$script;JSON.stringify(window.__DSH_ARCHIVE_DOM__())") { result ->
                val parsed = runCatching {
                    org.json.JSONObject(result.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\"))
                }.getOrNull()
                val arr = parsed?.optJSONArray("messages")
                if (arr == null || arr.length() == 0) {
                    onDone(false)
                    return@evaluateJavascript
                }
                val messages = ArrayList<ChatRole.Message>()
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i) ?: continue
                    val role = when (m.optString("role")) {
                        "user" -> ChatRole.Role.User
                        "assistant" -> ChatRole.Role.Assistant
                        else -> ChatRole.Role.Unknown
                    }
                    val text = m.optString("text").trim()
                    if (text.isBlank()) continue
                    messages += ChatRole.Message(role, text, 0L)
                }
                if (messages.isEmpty()) {
                    onDone(false)
                    return@evaluateJavascript
                }
                val archive = store.save(
                    ChatArchive.Capture(
                        sessionKey = sessionKey,
                        title = lastTitle.ifBlank { "未命名会话" },
                        text = ChatRole.Transcript(messages).encode(),
                        capturedAt = System.currentTimeMillis(),
                    )
                )
                val known = messages.count { it.role != ChatRole.Role.Unknown }
                RunLog.info(
                    "ChatArchive",
                    "dom roles: ${messages.size} messages, $known with role",
                )
                onDone(archive != null)
            }
        }.onFailure { onDone(false) }
    }

    private var domScript: String? = null

    /** 由 DshBridge 转交的探测结果（来自页面内 Promise 的异步回调）。 */
    fun onPullProbe(payload: String) {
        // 探测与拉取各自独立的 waiter：谁在等就交给谁，绝不互相抢。
        val pull = pullWaiter
        val probe = probeWaiter
        if (pull != null) {
            pullWaiter = null
            pull.invoke(payload)
            return
        }
        if (probe != null) {
            probeWaiter = null
            probe.invoke(payload)
            return
        }
        RunLog.warn("ChatArchive", "pull result arrived with no waiter: ${payload.take(120)}")
    }

    /**
     * 精准探测 `session/page`（DSH 源码里写明是"按消息对齐的历史页"接口）。
     *
     * 为什么要单独探：它混在回退链里时，失败原因被日志长度截断，
     * 看不清是 404、方法名不对还是参数不对。这里把每次尝试逐条写日志。
     */
    fun probeSessionPage(sessionId: String, onDone: (String) -> Unit) {
        val view = bridgeProvider()?.webViewForArchive()
        if (view == null) {
            onDone("no-webview")
            return
        }
        val script = runCatching {
            view.context.assets.open("archive-probe-page.js").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (script == null) {
            onDone("no-script")
            return
        }
        probeWaiter = waiter@{ payload ->
            // payload 是字符串数组：逐条写日志，避免整体被截断。
            val arr = runCatching { org.json.JSONArray(payload) }.getOrNull()
            if (arr == null) {
                RunLog.info("ChatArchive", "page probe raw=${payload.take(800)}")
                onDone(payload.take(400))
                return@waiter
            }
            for (i in 0 until arr.length()) {
                RunLog.info("ChatArchive", "page probe[${i + 1}] ${arr.optString(i).take(300)}")
            }
            onDone("tried=${arr.length()}")
        }
        runCatching {
            view.evaluateJavascript(script, null)
            view.evaluateJavascript(
                "window.__DSH_ARCHIVE_PROBE_PAGE__(${org.json.JSONObject.quote(sessionId)})" +
                    ".then(function(list){try{DshNative.onBridgeMessage(JSON.stringify(" +
                    "{kind:'archive-probe',payload:list}));}catch(e){}}); 'started'",
                null,
            )
        }.onFailure {
            probeWaiter = null
            onDone("inject-failed")
        }
        main.postDelayed({
            if (probeWaiter != null) {
                probeWaiter = null
                onDone("timeout")
            }
        }, 25_000)
    }

    /** 最近一次拉到的原始 JSON（诊断用：确认真实字段结构后才知道正文在哪）。 */
    @Volatile var lastPullBody: String = ""
        private set

    /**
     * 后台拉取并解析一个会话的内容（**不切换 UI**）。
     *
     * 走页面内的 fetch（同源 + 已登录 Cookie），请求在后台完成，
     * 用户界面完全不动 —— 这是用户明确要求的"无感"。
     *
     * @param onResult (是否成功, 说明)
     */
    fun pullSession(context: Context, sessionId: String, onResult: (Boolean, String) -> Unit) {
        val view = bridgeProvider()?.webViewForArchive()
        if (view == null) {
            RunLog.warn("ChatArchive", "pull: no webview")
            onResult(false, "没有可用页面（需先连接工作台）")
            return
        }
        val script = pullScript ?: runCatching {
            view.context.assets.open("archive-pull.js").bufferedReader().use { it.readText() }
        }.getOrNull()?.also { pullScript = it }
        if (script == null) {
            RunLog.warn("ChatArchive", "pull: script missing")
            onResult(false, "脚本缺失")
            return
        }
        RunLog.info("ChatArchive", "pull start session=${sessionId.take(12)}")
        // 顺便 dump 一次页面实际调用过的接口，用于确认"完整消息"接口是否存在。
        dumpApiHits(view)
        val store = store ?: storeFor(context).also { store = it }
        pullWaiter = waiter@{ payload ->
            val json = runCatching { JSONObject(payload) }.getOrNull()
            if (json == null || !json.optBoolean("ok")) {
                val detail = json?.optString("detail").orEmpty()
                // 逐条打印每次尝试的原因（整体会被日志长度截断看不清）
                for ((i, part) in detail.split("|||").withIndex()) {
                    RunLog.warn("ChatArchive", "pull try[${i + 1}] ${part.trim().take(400)}")
                }
                onResult(false, detail.ifBlank { "拉取失败" }.take(160))
                return@waiter
            }
            // 页面内已把 6.9MB 的原始记录瘦身成 [{role,text,seq}]（bridge 单次传输有上限）。
            val messagesJson = json.optJSONArray("messages")
            val parsed = ArrayList<ChatRole.Message>()
            if (messagesJson != null) {
                for (i in 0 until messagesJson.length()) {
                    val m = messagesJson.optJSONObject(i) ?: continue
                    val role = when (m.optString("role")) {
                        "user" -> ChatRole.Role.User
                        "assistant" -> ChatRole.Role.Assistant
                        "tool" -> ChatRole.Role.Tool
                        else -> ChatRole.Role.Unknown
                    }
                    val text = m.optString("text")
                    if (text.isBlank()) continue
                    parsed += ChatRole.Message(role, text, m.optLong("seq", 0L))
                }
            }
            val messages: List<ChatRole.Message> = parsed
            if (messages.isEmpty()) {
                RunLog.warn(
                    "ChatArchive",
                    "pull parse empty endpoint=${json.optString("endpoint")} bytes=${json.optInt("bytes")}",
                )
                onResult(false, "解析不到完整对话（${json.optInt("bytes")} 字节）")
                return@waiter
            }
            val title = json.optString("title").ifBlank {
                sessionTitles[sessionId]
                    ?: sessionTitles.entries.firstOrNull { it.key.contains(sessionId.takeLast(12)) }?.value
                    ?: "会话 ${sessionId.takeLast(8)}"
            }
            val transcript = ChatRole.Transcript(messages)
            val archive = store.save(
                ChatArchive.Capture(
                    sessionKey = sessionId,
                    title = title,
                    text = transcript.encode(),
                    capturedAt = System.currentTimeMillis(),
                    // 原始记录体积：给界面区分「原始 9 MB / 纯对话几百 KB」用。
                    rawBytes = json.optLong("bytes", 0L),
                )
            )
            if (archive != null) {
                lastCaptureAt = System.currentTimeMillis()
                lastTitle = archive.title
                savedSessionIds += sessionId
                lastError = ""
                // 完整性自检：expected 是原始记录里应有的纯文本消息数，
                // 少了就说明抽取漏了某类事件，必须如实写日志（不能假装完整）。
                val expected = json.optInt("expected", 0)
                if (expected > 0 && messages.size < expected) {
                    RunLog.warn(
                        "ChatArchive",
                        "incomplete: got=${messages.size} expected=$expected title=$title",
                    )
                }
                RunLog.info(
                    "ChatArchive",
                    "pulled ${messages.size}/$expected messages title=$title pages=${json.optInt("pages")}",
                )
                onResult(true, "${messages.size} 条消息")
            } else {
                onResult(false, "写入失败")
            }
        }
        main.postDelayed({
            if (pullWaiter != null) {
                pullWaiter = null
                onResult(false, "超时")
            }
        }, 60_000)
        val call = "try{" +
            "$script;window.__DSH_ARCHIVE_PULL_FULL__(" +
            "${org.json.JSONObject.quote(sessionId)})" +
            ".then(function(r){try{DshNative.onBridgeMessage(JSON.stringify({kind:'archive-probe'," +
            "payload:{ok:r.ok,endpoint:r.endpoint||'',method:r.method||'',bytes:r.bytes||0," +
            "reason:r.reason||'',detail:(r.detail||'').slice(0,2500)," +
            // 页面内已抽成 [{role,text,seq}]（原始 6.9MB 超过 bridge 上限），
            // 优先传 messages；老路径的 body 仅作兜底。
            "messages:r.messages||null,count:r.count||0,expected:r.expected||0,title:r.title||''," +
            "pages:r.pages||0," +
            "head:(r.body||'').slice(0,200000)}}));}" +
            "catch(e){try{DshNative.onBridgeMessage(JSON.stringify({kind:'archive-probe'," +
            "payload:{ok:false,detail:'js-error:'+(e&&e.message)}}));}catch(_){}}})" +
            ".catch(function(e){try{DshNative.onBridgeMessage(JSON.stringify({kind:'archive-probe'," +
            "payload:{ok:false,detail:'promise-reject:'+(e&&e.message)}}));}catch(_){}});" +
            "}catch(outer){try{DshNative.onBridgeMessage(JSON.stringify({kind:'archive-probe'," +
            "payload:{ok:false,detail:'outer:'+(outer&&outer.message)}}));}catch(_){}} 'started'"
        // 注入拉取脚本（幂等），再把环境状态回报给日志。
        // 上一次失败教训：把 inject 与 return 拼在一条语句里，返回值被吞成 null。
        runCatching {
            view.evaluateJavascript(script, null)
        }
        runCatching {
            view.evaluateJavascript(
                "(function(){return JSON.stringify({hasNative:typeof DshNative!=='undefined'," +
                    "hasPull:typeof window.__DSH_ARCHIVE_PULL__!=='undefined'});})()",
            ) { diag ->
                RunLog.info("ChatArchive", "pull env=$diag")
                runCatching { view.evaluateJavascript(call, null) }
                    .onFailure {
                        probeWaiter = null
                        onResult(false, "注入失败")
                    }
            }
        }.onFailure {
            probeWaiter = null
            onResult(false, "注入失败")
        }
    }

    /** 侧栏里的一条会话（不含正文）。 */
    data class ArchiveSession(
        val key: String,
        val title: String,
        /** 网页上显示的时间片段，可能为空（不编造）。 */
        val whenText: String,
        /** 所属工作区名（读不到为空）。不同工作区在 UI 里分组展示。 */
        val group: String,
        /** 侧栏顺序，0 通常是最新。 */
        val index: Int,
    )

    private var sessionScript: String? = null

    /** 会话标题：优先页面标题，其次首条用户消息的首行。 */
    private fun titleFor(key: String): String {
        val fromPage = sessionTitles[key]
        if (!fromPage.isNullOrBlank()) return fromPage
        val firstUser = transcripts[key]
            ?.firstOrNull { it.role == ChatRole.Role.User }
            ?.text?.lineSequence()?.firstOrNull()?.trim()
        return firstUser?.take(60)?.ifBlank { "未命名会话" } ?: "未命名会话"
    }

    /**
     * 本次运行中见过的会话（设置页「选择要缓存的对话」用）。
     *
     * 只列**本次连接后**出现过的会话 —— App 拿不到历史会话列表（那是网页的事），
     * 所以不假装有全量清单，也不让用户对着空列表勾选。
     */
    fun knownSessions(): List<Pair<String, String>> =
        transcripts.entries
            .filter { it.value.isNotEmpty() }
            .map { it.key to titleFor(it.key) }
            .sortedBy { it.second }

    private val sessionTitles = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 单会话内存上限：防止长时间运行时无限增长。 */
    private const val MAX_MESSAGES = 400

    private fun deriveTitle(snapshot: JSONObject): String {
        for (key in listOf("title", "sessionTitle", "name")) {
            val value = snapshot.optString(key)
            if (value.isNotBlank()) return value.take(80)
        }
        return guessTitleFromPage() ?: "未命名会话"
    }

    /** 从页面 title 取会话名；拿不到返回 null（由上层兜底）。 */
    private fun guessTitleFromPage(): String? {
        val bridge = bridgeProvider() ?: return null
        val view = bridge.webViewForArchive() ?: return null
        return runCatching { view.title?.trim()?.takeIf { it.isNotEmpty() }?.take(80) }.getOrNull()
    }

    /**
     * 构造与采集同一配置的 store（同目录、同配额、同加密器），保证能解开已有密文。
     *
     * 两个不能改的选择：
     *  - **filesDir 而不是 cacheDir**：cacheDir 会被系统在空间紧张时回收，
     *    用户开了功能却发现历史"自己没了"不可接受。
     *  - **加密器固定注入**：正文含工具输出，密钥失效时宁可不缓存，也不降级写明文。
     */
    private fun storeFor(context: Context): ChatArchiveStore = ChatArchiveStore(
        directory = File(context.filesDir, "chat-archive"),
        quota = { SettingsStore(context).chatArchiveQuota.bytes },
        cipher = ChatArchiveCipher(),
        log = { RunLog.info("ChatArchive", it) },
    )

    /** 缓存占用（设置页展示）。目录不存在时返回 0。 */
    fun usageBytes(context: Context): Long = runCatching {
        File(context.filesDir, "chat-archive").listFiles()?.sumOf { it.length() } ?: 0L
    }.getOrDefault(0L)

    /** 列出归档（离线页用）。只需元数据，不触碰正文与密钥。 */
    fun list(context: Context): List<ChatArchive.Archive> = runCatching {
        storeFor(context).list()
    }.getOrDefault(emptyList())

    /**
     * 已缓存会话的「网页侧原始记录」体积合计。
     *
     * 用途只有一个：在界面上把「原始 9 MB」与「本机只占几百 KB」分开显示，
     * 让用户看懂差异（工具调用的参数与返回按隐私边界不落盘）。
     * 取不到原始体积的旧归档按 0 计，不外推。
     */
    fun rawBytesTotal(context: Context): Long = runCatching {
        list(context).sumOf { it.rawBytes }
    }.getOrDefault(0L)

    /** 读正文（离线页用）。密文由 store 内部解密，密钥失效时返回 null。 */
    fun text(context: Context, id: String): String? = runCatching {
        storeFor(context).text(id)
    }.getOrNull()

    /** 清空缓存（设置页「清除」）。 */
    fun clear(context: Context) = runCatching {
        storeFor(context).clear()
        lastCaptureAt = 0L
        lastTitle = ""
        RunLog.info("ChatArchive", "archive cleared; connections and drafts retained")
    }.getOrNull()
}
