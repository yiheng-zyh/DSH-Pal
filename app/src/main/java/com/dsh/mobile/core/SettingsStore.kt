package com.dsh.mobile.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * User-facing settings, persisted in a private [SharedPreferences] file.
 *
 * Two different things are stored here on purpose, because they have different
 * lifetimes:
 *
 *  - [pairingUrl] — the *one-shot* link minted by the remote-web-ui plugin. It
 *    carries a token that expires within minutes, so it is only good for the very
 *    next load. Keeping such a link as "the address" is what made the app reopen
 *    onto an expired token and land on a black screen.
 *  - [baseUrl] — the *origin* the pairing landed on. This is what the WebView
 *    loads on every subsequent start; from then on the plugin's own cookie does
 *    the authenticating.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val prefix = if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_" else ""
    fun observePredictiveBack(onChange:(Boolean)->Unit):()->Unit {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key->if(key=="predictive_back")onChange(predictiveBackEnabled) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    var predictiveBackEnabled: Boolean
        get() = prefs.getBoolean("predictive_back", false)
        set(value) { prefs.edit { putBoolean("predictive_back", value) } }

    fun observeOverlayPreferences(onChanged: () -> Unit): () -> Unit {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { _,key -> if(key?.startsWith("overlay_")==true) onChanged() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun setOverlayPosition(x: Float, y: Float) {
        prefs.edit { putFloat("overlay_x",x.coerceIn(0f,1f));putFloat("overlay_y",y.coerceIn(0f,1f)) }
    }
    var overlayPeekOnEdge: Boolean
        get() = prefs.getBoolean("overlay_peek_on_edge", true)
        set(value) { prefs.edit { putBoolean("overlay_peek_on_edge",value);if(!value)putBoolean("overlay_docked",false) } }
    var overlayDocked: Boolean
        get() = overlayPeekOnEdge && prefs.getBoolean("overlay_docked", false)
        set(value) { prefs.edit { putBoolean("overlay_docked",value) } }
    fun setOverlayDock(x: Float, y: Float, docked: Boolean) {
        prefs.edit { putFloat("overlay_x",x.coerceIn(0f,1f));putFloat("overlay_y",y.coerceIn(0f,1f));putBoolean("overlay_docked",docked) }
    }
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(value) { prefs.edit { putBoolean("overlay_enabled", value) } }
    var overlayGlassStyle:Int
        get() = prefs.getInt("overlay_glass_style",0).coerceIn(0,2)
        set(value) {prefs.edit {putInt("overlay_glass_style",value.coerceIn(0,2))}}
    var overlayPanelWidth: Int
        get() = prefs.getInt("overlay_panel_width",280).coerceIn(180,360)
        set(value) { prefs.edit {putInt("overlay_panel_width",value.coerceIn(180,360))} }
    var overlayPanelHeight: Int
        get() = prefs.getInt("overlay_panel_height", 0).coerceIn(0, 640)
        set(value) { prefs.edit { putInt("overlay_panel_height", value.coerceIn(0, 640)) } }
    var overlayFontSize: Int
        get() = prefs.getInt("overlay_font_size",12).coerceIn(10,16)
        set(value) { prefs.edit {putInt("overlay_font_size",value.coerceIn(10,16))} }
    var overlayPanelTransparency: Int
        get() = prefs.getInt("overlay_panel_transparency",8).coerceIn(0,80)
        set(value) { prefs.edit {putInt("overlay_panel_transparency",value.coerceIn(0,80))} }
    var overlaySize: Int
        get() = prefs.getInt("overlay_size", 88).coerceIn(64, 120)
        set(value) { prefs.edit { putInt("overlay_size", value.coerceIn(64, 120)) } }
    var overlayDetail: Int
        get() = prefs.getInt("overlay_detail", 1).coerceIn(0, 2)
        set(value) { prefs.edit { putInt("overlay_detail", value.coerceIn(0, 2)) } }
    var overlayShowTitle: Boolean
        get() = prefs.getBoolean("overlay_show_title", false)
        set(value) { prefs.edit { putBoolean("overlay_show_title", value) } }
    var overlayExpanded: Boolean
        get() = prefs.getBoolean("overlay_expanded", true)
        set(value) { prefs.edit { putBoolean("overlay_expanded", value) } }
    var overlayX: Float
        get() = prefs.getFloat("overlay_x", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        set(value) { prefs.edit { putFloat("overlay_x", value.coerceIn(0f, 1f)) } }
    var overlayY: Float
        get() = prefs.getFloat("overlay_y", .35f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .35f
        set(value) { prefs.edit { putFloat("overlay_y", value.coerceIn(0f, 1f)) } }

    var enhancedInteractions: Boolean
        get() = prefs.getBoolean("enhanced_interactions", true)
        set(value) { prefs.edit { putBoolean("enhanced_interactions", value) } }

    var usbStaticPreview:Boolean
        get()=com.dsh.mobile.BuildConfig.USB_DEVELOPMENT && prefs.getBoolean("dev_usb_static_preview",false)
        set(value){if(com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)prefs.edit {putBoolean("dev_usb_static_preview",value)}}
    private val profilesKey get() = "${prefix}connection_profiles"
    private val activeKey get() = "${prefix}active_profile_id"
    private val baseKey get() = "${prefix}base_url"
    private fun migrateProfiles() {
        if (prefs.contains(profilesKey)) return
        val migrated = ConnectionProfiles.migrate(prefs.getString(baseKey, "").orEmpty(), com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)
        prefs.edit { putString(profilesKey, ConnectionProfiles.encode(migrated)); putString(activeKey, migrated.firstOrNull()?.id) }
        RunLog.info("Connection", "profiles migrated count=${migrated.size}; legacy credentials retained")
    }
    var profiles: List<ConnectionProfile>
        get() { migrateProfiles(); return runCatching { ConnectionProfiles.decode(prefs.getString(profilesKey, "[]")!!) }.getOrElse {
            RunLog.warn("Connection", "profile data unreadable; retained legacy address")
            ConnectionProfiles.migrate(prefs.getString(baseKey, "").orEmpty(), com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)
        } }
        private set(value) { prefs.edit { putString(profilesKey, ConnectionProfiles.encode(value)) } }
    var activeProfileId: String?
        get() { migrateProfiles(); return prefs.getString(activeKey, null) }
        private set(value) { prefs.edit { putString(activeKey, value) } }
    val activeProfile get(): ConnectionProfile? {
        val all = profiles
        return ConnectionProfiles.restore(all, activeProfileId, prefs.getString(baseKey, "").orEmpty())
    }
    val currentBaseUrl get() = activeProfile?.baseUrl ?: prefs.getString(baseKey, "").orEmpty()
    fun saveConnection(url: String, label: String = "", kind: ConnectionProfile.Kind? = null, meshProvider:ConnectionProfile.MeshProvider?=null): ConnectionProfile {
        val remembered=profiles.firstOrNull {it.baseUrl==ConnectionAddress.origin(url)}
        val candidate = ConnectionProfile.create(label, kind ?: remembered?.kind ?: ConnectionAddress.infer(url) ?: error("不支持的本地地址"), url)
        val profile=candidate.copy(meshProvider=meshProvider ?: profiles.firstOrNull {it.id==candidate.id}?.meshProvider)
        val old = profiles.firstOrNull { it.id == profile.id }
        require(profile.kind != ConnectionProfile.Kind.USB || com.dsh.mobile.BuildConfig.USB_DEVELOPMENT)
        val upserted = ConnectionProfiles.upsert(profiles, if (old != null) profile.copy(label = label.ifBlank { old.label }, lastUsedAt = old.lastUsedAt) else profile)
        // 保存时顺手把同一 baseUrl 的旧条目归并掉（用户反馈"历史记录好乱"）。
        // upsert 只按 id 去重，而 id 是 origin 的哈希 —— 理论上不会重，
        // 但旧版本写过的数据、或 label 不同而地址相同的条目仍可能残留。
        // 这里按 baseUrl 取 lastUsedAt 最大的一条，幂等且不会误删不同地址。
        val updated = upserted
            .groupBy { it.baseUrl }
            .map { (_, group) -> group.maxByOrNull { it.lastUsedAt } ?: group.first() }
        // Selection, list and restart target form one durable transaction.
        prefs.edit(commit = true) {
            putString(profilesKey, ConnectionProfiles.encode(updated))
            putString("${prefix}pairing_url", LaunchRouting.apply(ConnectionTarget(profile.baseUrl, ""), url).pairingUrl)
            putString(activeKey, profile.id); putString(baseKey, profile.baseUrl)
        }
        RunLog.info("Connection", "profile saved kind=${profile.kind}; id=${profile.id.take(8)}")
        return activeProfile!!
    }
    fun activateProfile(id: String): Boolean {
        val profile = ConnectionProfiles.select(profiles, id) ?: return false
        if (profile.kind == ConnectionProfile.Kind.USB && !com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) return false
        val changed = activeProfileId != id
        prefs.edit(commit = true) { if (changed) remove("${prefix}pairing_url"); putString(activeKey, id); putString(baseKey, profile.baseUrl) }
        RunLog.info("Connection", "profile selected kind=${profile.kind}; id=${id.take(8)}")
        return true
    }

    /**
     * 自动去重：同一 origin 只保留最近使用的那一条。
     *
     * 为什么需要（2026-11-04 用户反馈「历史记录留这么多，看着好乱」）：
     * `ConnectionProfiles.upsert` 是按 id（origin 的 SHA-256）去重的，正常路径
     * 不会重复。但用户实际看到同一个 frp 出现多条，说明存在 id 之外的重复来源
     * —— 例如旧版本写入的条目、或 label 不同但 origin 相同的记录。
     * 与其逐个追查历史成因，不如在读取时做一次幂等归并：
     * 相同 baseUrl 只留 lastUsedAt 最大的那条，且**若当前连接被归并掉，
     * 把 activeProfileId 迁到保留的那条**，避免出现「当前连接指向已不存在的 id」。
     *
     * 幂等、失败安全：没有重复就什么都不写。
     * @returns 实际删掉的条目数（0 表示本来就干净）。
     */
    fun dedupeProfiles(): Int {
        val all = profiles
        if (all.size < 2) return 0
        val keep = LinkedHashMap<String, ConnectionProfile>()
        for (profile in all) {
            val existing = keep[profile.baseUrl]
            // 同一个地址：留 lastUsedAt 更大的；时间相同则留先出现的（稳定）。
            if (existing == null || profile.lastUsedAt > existing.lastUsedAt) {
                keep[profile.baseUrl] = profile
            }
        }
        if (keep.size == all.size) return 0
        val survivors = keep.values.toList()
        val removed = all - survivors.toSet()
        // 当前连接被合并掉时，把 activeProfileId 迁到同地址的幸存者。
        val activeId = activeProfileId
        val active = activeId?.let { id -> all.firstOrNull { it.id == id } }
        val rebind = active != null && removed.any { it.id == active.id }
        prefs.edit(commit = true) {
            putString(profilesKey, ConnectionProfiles.encode(survivors))
            if (rebind) {
                val replacement = survivors.firstOrNull { it.baseUrl == active.baseUrl }
                if (replacement != null) {
                    putString(activeKey, replacement.id)
                    putString(baseKey, replacement.baseUrl)
                }
            }
        }
        RunLog.info("Connection", "profiles deduped removed=${removed.size}; left=${survivors.size}; rebound=$rebind")
        return removed.size
    }

    /**
     * 删除一条已保存的连接。
     *
     * 为什么需要它（2026-11-04 用户反馈）：
     * 切换通道/换地址会不断积累历史记录（同一个池子里的 Cloudflare 临时域名
     * 每次都不同），列表越堆越乱，而界面上**连个删除入口都没有**。
     *
     * 规则：
     *  - 删的是当前连接时，自动回退到最近使用过的另一条；一条都不剩就清空，
     *    让用户回到"首次连接"状态（不会留一个指向已删地址的 activeProfileId）。
     *  - 同时清掉该地址的配对记录，否则换回同一地址时会带着旧凭据。
     *  - 返回值告诉调用方是否改变了当前连接，界面据此决定要不要重载页面。
     */
    fun deleteProfile(id: String): Boolean {
        val all = profiles
        val target = all.firstOrNull { it.id == id } ?: return false
        val remaining = all.filterNot { it.id == id }
        val wasActive = activeProfileId == id
        // 删掉的若正是当前连接，回退到最近用过的另一条（没有就留空）。
        val fallback = remaining.maxByOrNull { it.lastUsedAt }
        prefs.edit(commit = true) {
            putString(profilesKey, ConnectionProfiles.encode(remaining))
            if (wasActive) {
                remove("${prefix}pairing_url")
                if (fallback != null) {
                    putString(activeKey, fallback.id)
                    putString(baseKey, fallback.baseUrl)
                } else {
                    remove(activeKey)
                    remove(baseKey)
                }
            }
        }
        RunLog.info(
            "Connection",
            "profile deleted kind=${target.kind}; id=${id.take(8)}; wasActive=$wasActive; left=${remaining.size}",
        )
        return wasActive
    }
    fun markConnectionUsed() {
        val p = activeProfile ?: return
        profiles = ConnectionProfiles.upsert(profiles, p.copy(lastUsedAt = System.currentTimeMillis()))
    }

    /**
     * The origin to load, e.g. `https://<your-host>` or `http://192.0.2.10:3080`.
     *
     * Always an origin (scheme + host [+ port]), never a one-shot pairing link.
     * Empty means "not configured yet".
     */
    var baseUrl: String
        get() = currentBaseUrl
        set(value) {
            if (value.isBlank()) { activeProfileId = null; prefs.edit { putString(baseKey, "") }; return }
            if (ConnectionAddress.origin(value) != activeProfile?.baseUrl) saveConnection(value)
        }

    /**
     * A pending pairing link that has not been consumed yet.
     *
     * Loaded exactly once, then cleared by [consumePairingUrl], so an expired token
     * can never be replayed on a later launch.
     */
    var pairingUrl: String
        get() = prefs.getString(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_pairing_url" else KEY_PAIRING_URL, "") ?: ""
        set(value) = prefs.edit { putString(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_pairing_url" else KEY_PAIRING_URL, value.trim()) }

    /** Read and clear the pending pairing link. */
    fun consumePairingUrl(): String {
        val url = pairingUrl
        if (url.isNotEmpty()) pairingUrl = ""
        return url
    }

    /**
     * A pairing token kept for pairing *other* origins of the same DSH.
     *
     * Why this has to outlive [pairingUrl]: the plugin's device cookie is
     * host-scoped, so pairing https://relay… does nothing for
     * https://host.ts.net. Its token, however, is origin-blind —
     * `PairingService.accept(token)` looks only at the token table and never at
     * the request host, so the same token can mint a session for whichever host
     * serves the accept page. That is the whole reason the VPN route can pair
     * without a second scan.
     *
     * Storing the token is not a new exposure: it already sat in this same
     * SharedPreferences file as part of the pending URL, and it expires with the
     * plugin's own window (~2h). What changes is that a successful pairing no
     * longer throws away the only thing that lets another origin authenticate.
     * Never logged.
     */
    var pairingToken: String
        get() = prefs.getString(KEY_PAIRING_TOKEN, "").orEmpty()
        set(value) = prefs.edit {
            if (value.isBlank()) remove(KEY_PAIRING_TOKEN) else putString(KEY_PAIRING_TOKEN, value.trim())
        }

    /** Remember the token from a pairing link so a later origin can reuse it. */
    fun rememberPairingToken(pairingUrl: String) {
        MeshPairing.token(pairingUrl)?.let { pairingToken = it }
    }

    /**
     * Add a route to the saved list *without* selecting it.
     *
     * Switching used to be the only path that saved a route, so a route could
     * only exist after it had already worked. For a first-time VPN connection that
     * is circular: the route is unusable until paired, and it was never listed
     * until used, so the list showed "尚未配置" and there was nothing to switch back
     * to — the reported "登录 CF 之后就不知道该怎么切回 VPN 了". A route the user has
     * chosen is a real option even if this attempt failed; keep it, and leave the
     * active selection untouched so a failure cannot silently change where the
     * app is pointed.
     * @returns the stored profile, or null when the address is unusable.
     */
    fun rememberConnection(url: String, label: String, kind: ConnectionProfile.Kind, provider: ConnectionProfile.MeshProvider? = null): ConnectionProfile? =
        runCatching {
            val origin = ConnectionAddress.origin(url) ?: return@runCatching null
            val existing = profiles.firstOrNull { it.baseUrl == origin }
            val profile = ConnectionProfile.create(label, kind, url)
                .copy(
                    meshProvider = provider ?: existing?.meshProvider,
                    // Keep whatever history exists; a route being recorded is not
                    // evidence that it has ever worked.
                    lastUsedAt = existing?.lastUsedAt ?: 0,
                )
            // Only the profile list changes here. Active selection, base URL and
            // pairing link stay untouched: remembering an option must never move
            // the app off a working connection.
            val updated = ConnectionProfiles.upsert(profiles, existing?.let { profile.copy(label = if (label.isBlank()) it.label else label) } ?: profile)
            prefs.edit { putString(profilesKey, ConnectionProfiles.encode(updated)) }
            RunLog.info("Connection", "route remembered kind=${profile.kind}; id=${profile.id.take(8)}")
            profile
        }.getOrNull()

    /** A full /pair-accept link for [origin] built from the remembered token. */
    fun pairingUrlFor(origin: String): String? {
        val token = pairingToken
        if (token.isBlank()) return null
        val base = ConnectionAddress.origin(origin) ?: return null
        return "$base/pair-accept?pair=" + java.net.URLEncoder.encode(token, "UTF-8")
    }

    /** Observe live controls across SettingsStore instances. Caller must unsubscribe. */
    fun observeRuntimePreferences(onChanged: () -> Unit): () -> Unit {
        val watched = setOf(KEY_ISLAND, KEY_NOTIFICATION, KEY_BACKGROUND_LIVE, KEY_HAPTICS, KEY_RETRACT)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in watched) onChanged() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var autoReconnect: Boolean
        get() = prefs.getBoolean("auto_reconnect", true)
        set(value) = prefs.edit { putBoolean("auto_reconnect", value) }

    /**
     * 通道自动选路总开关。**默认开启**。
     *
     * 为什么默认开：用户的原话是「保命优先」—— 当前通道挂了要能自己活过来，
     * 这是配对之后就该有的基本能力，没有理由让用户先去设置里找开关。
     * 关掉时自动切换和优化提示**一起**停掉，因为关这个开关的人要的是「别管我」，
     * 而不是「别切但我还想起有提示」。
     */
    var channelAutoSwitch: Boolean
        get() = prefs.getBoolean("channel_auto_switch", true)
        set(value) = prefs.edit { putBoolean("channel_auto_switch", value) }

    /**
     * 通道优先级（有序）。默认 LAN → IPv6 → 国内/公网中转 → Tailscale。
     *
     * 存成逗号分隔的 `Kind.name`：既有顺序又保持纯文本可读，坏数据也能被
     * [ChannelPolicy.parseOrder] 容错成全序，不会因为用户手改配置把选路搞死。
     */
    var channelPriority: List<ConnectionProfile.Kind>
        get() = ChannelPolicy.parseOrder(prefs.getString("channel_priority", "").orEmpty())
        set(value) = prefs.edit { putString("channel_priority", ChannelPolicy.encodeOrder(value)) }

    /**
     * 优化提示静默截止时间（epoch millis，0 = 没静默）。
     *
     * 落盘而不是只放内存：用户拒绝一次之后如果 App 被系统杀掉重启，
     * 立刻又弹同一个提示，观感上就是「它根本记不住我说了不」。
     */
    var channelPromptSilencedUntil: Long
        get() = prefs.getLong("channel_prompt_silenced_until", 0L)
        set(value) = prefs.edit { putLong("channel_prompt_silenced_until", value) }

    /**
     * 手动干预后暂停自动选路的截止时间（epoch millis，0 = 没暂停）。
     *
     * 与 [channelPromptSilencedUntil] 分开存，因为两者触发条件不同：
     * 前者是「用户自己选了通道」，后者是「用户拒绝了提示」。合成一个字段会出现
     * 「拒绝了提示 → 连保命切换都被停掉」，那是两个不同意的严重程度。
     */
    var channelManualHoldUntil: Long
        get() = prefs.getLong("channel_manual_hold_until", 0L)
        set(value) = prefs.edit { putLong("channel_manual_hold_until", value) }

    /**
     * 用户刚手动切过通道 → 自动选路暂停 30 分钟。
     *
     * 为什么连保命切换也要暂停：用户刚做完一个明确的选择，如果 App 立刻把他拽回去，
     * 他只会觉得这软件不听他的。30 分钟足够他做完手上的事，也足够发现选错了。
     */
    fun noteManualChannelSwitch(now: Long = System.currentTimeMillis()) {
        channelManualHoldUntil = now + ChannelFailoverEngine.MANUAL_HOLD_MS
        RunLog.info("Channel", "manual switch; auto selection paused for ${ChannelFailoverEngine.MANUAL_HOLD_MS / 60_000} min")
    }

    /** 用户拒绝了优化提示 → 本次运行 30 分钟内不再弹。 */
    fun silenceChannelPrompt(now: Long = System.currentTimeMillis()) {
        channelPromptSilencedUntil = now + ChannelFailoverEngine.PROMPT_SILENCE_MS
    }
    var autoConnectOnLaunch: Boolean
        get() = prefs.getBoolean("auto_connect_launch", true)
        set(value) = prefs.edit { putBoolean("auto_connect_launch", value) }

    /**
     * 登录 Tailscale 之后自动完成配对，不再要求扫码。**默认开启**。
     *
     * 用户 2026-11-04 的要求：「手机这边已经登录上账号之后，下一步不就是应该直接
     * 连接了吗，而不是还要有其他操作。你给这一步做成隐性自动化自动发现的」。
     *
     * 实现上不是放宽鉴权：手机向电脑要一个一次性配对令牌，电脑端
     * （pc-plugin/autopair.mjs）会校验来源 IP 确实属于本 tailnet 的自有设备才放行，
     * 然后 App 打开 `/pair-accept?pair=…` —— 与扫码落到的是同一个端点、同一套校验，
     * 只是免掉了「人拿手机对着屏幕扫一下」这一步。
     *
     * 保留这个开关是给「tailnet 里还有别人的设备」这种场景一条明确退路：
     * 关掉即回到原来的扫码流程，其余行为不变。
     */
    var autoPairOnLogin: Boolean
        get() = prefs.getBoolean("auto_pair_on_login", true)
        set(value) = prefs.edit { putBoolean("auto_pair_on_login", value) }

    /**
     * 实验功能：把对话正文缓存到手机，断网也能翻历史。**默认关闭**。
     *
     * 为什么默认关闭且单独标注：它把对话正文（含工具输出）落盘到手机，与
     * 「任务通知只显示公开步骤、不含命令正文」的既有边界不同，属于用户必须
     * 显式开启的实验能力。关闭时不影响任何既有行为（采集、落盘全部短路）。
     */
    var chatArchiveEnabled: Boolean
        get() = prefs.getBoolean("chat_archive_enabled", false)
        set(value) = prefs.edit { putBoolean("chat_archive_enabled", value) }

    /** 对话缓存容量档位（枚举名持久化）。默认 1 GB。 */
    var chatArchiveQuota: ChatArchive.Quota
        get() = ChatArchive.Quota.ofName(prefs.getString("chat_archive_quota", null))
        set(value) = prefs.edit { putString("chat_archive_quota", value.name) }

    /** 缓存哪些会话：AUTO=全部（默认），SELECTED=只缓存勾选的。 */
    var chatArchiveSelection: ChatArchive.Selection
        get() = ChatArchive.Selection.ofName(prefs.getString("chat_archive_selection", null))
        set(value) = prefs.edit { putString("chat_archive_selection", value.name) }

    /** SELECTED 模式下被勾选的会话标识（换行分隔，避免 JSON 开销）。 */
    var chatArchiveSelectedSessions: Set<String>
        get() = prefs.getString("chat_archive_selected", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(value) = prefs.edit { putString("chat_archive_selected", value.joinToString("\n")) }

    /**
     * 用户上次确认同步时，所用会话的标题快照（`key\t标题` 每行一条）。
     *
     * 为什么要单独存：会话标识是网页给的 row-key，用户看不出它对应哪条对话。
     * 下次打开「选择要同步的对话」时用这些标题**先把上次的选择显示出来**，
     * 用户马上能认出"上次同步的是这几条"，而不用重新翻列表猜。
     */
    var chatArchiveSelectedTitles: Map<String, String>
        get() = prefs.getString("chat_archive_selected_titles", "").orEmpty()
            .lineSequence().filter { it.contains('\t') }
            .associate { it.substringBefore('\t') to it.substringAfter('\t') }
        set(value) = prefs.edit {
            putString("chat_archive_selected_titles", value.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }

    /** 上次确认同步的时间（epoch millis）。0 表示从未选过。 */
    var chatArchiveLastPickAt: Long
        get() = prefs.getLong("chat_archive_last_pick_at", 0L)
        set(value) = prefs.edit { putLong("chat_archive_last_pick_at", value) }

    /**
     * Whether the island should appear while a turn runs.
     *
     * Off by default. This app cannot render a real island: Xiaomi's focus
     * notification protocol is closed (its FocusPlugin rejects third-party
     * signatures with `onAuthFailed`, see DECISIONS 2026-10-26), so the only route
     * available is the MediaStyle music channel. That shows up as a media card
     * with playback-shaped controls rather than as a true island, which looks wrong
     * to anyone who did not ask for it. Off-by-default keeps the honest surface
     * until the user opts in, and turning it on is still a real, working feature.
     */
    var islandEnabled: Boolean
        get() = prefs.getBoolean(KEY_ISLAND, false)
        set(value) = prefs.edit { putBoolean(KEY_ISLAND, value) }

    /** Whether a persistent notification tracks the current task. */
    var notificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION, value) }

    /** Whether the foreground service keeps the socket alive in the background. */
    var backgroundLive: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_LIVE, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKGROUND_LIVE, value) }

    /** Vibrate when an approval is requested (the one notification that must not be missed). */
    var hapticsOnApproval: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit { putBoolean(KEY_HAPTICS, value) }

    /** Retract the island immediately on turn end instead of showing a short completion state. */
    var islandRetractImmediately: Boolean
        get() = prefs.getBoolean(KEY_RETRACT, false)
        set(value) = prefs.edit { putBoolean(KEY_RETRACT, value) }

    /** Whether the user has finished onboarding (first-run pairing screen). */
    var onboarded: Boolean
        get() = prefs.getBoolean(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_onboarded" else KEY_ONBOARDED, false)
        set(value) = prefs.edit { putBoolean(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_onboarded" else KEY_ONBOARDED, value) }

    /** Force the desktop layout even on a phone (escape hatch for odd viewports). */
    var webLayoutMode: WebLayoutMode
        get() = runCatching { WebLayoutMode.valueOf(prefs.getString("web_layout_mode", null) ?: if(forceDesktopLayout) "DESKTOP" else "AUTO") }.getOrDefault(WebLayoutMode.AUTO)
        set(value) = prefs.edit { putString("web_layout_mode", value.name) }

    var forceDesktopLayout: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DESKTOP, false)
        set(value) = prefs.edit { putBoolean(KEY_FORCE_DESKTOP, value) }

    var localAppearance: LocalAppearance
        get() = LocalAppearance.fromId(prefs.getString("local_appearance", "dsh"))
        set(value) { prefs.edit { putString("local_appearance", value.id) } }

    /** Keep the last applied custom palette when temporarily choosing a preset. */
    var customAppearance: LocalAppearance
        get() = LocalAppearance.fromId(prefs.getString("custom_appearance", "custom:210:35:94"))
            .takeIf { it.isCustom } ?: LocalAppearance.custom(210,35,94)
        set(value) { if(value.isCustom) prefs.edit { putString("custom_appearance", value.id) } }

    /** Clear everything pairing-related so the app returns to onboarding. */
    fun unpair() {
        prefs.edit {
            remove(profilesKey)
            remove(activeKey)
            remove(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_base_url" else KEY_BASE_URL)
            remove(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_pairing_url" else KEY_PAIRING_URL)
            putBoolean(if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) "usb_onboarded" else KEY_ONBOARDED, false)
        }
    }

    private companion object {
        const val PREFS = "dsh_mobile_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_PAIRING_URL = "pairing_url"
        /** Reusable token for pairing other origins of the same DSH; see pairingToken. */
        const val KEY_PAIRING_TOKEN = "pairing_token_reusable"
        const val KEY_ISLAND = "island_enabled"
        const val KEY_NOTIFICATION = "notification_enabled"
        const val KEY_BACKGROUND_LIVE = "background_live"
        const val KEY_HAPTICS = "haptics_on_approval"
        const val KEY_RETRACT = "retract_immediately"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_FORCE_DESKTOP = "force_desktop"
    }
}
