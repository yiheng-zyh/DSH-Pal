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
    var overlayPanelWidth: Int
        get() = prefs.getInt("overlay_panel_width",280).coerceIn(180,360)
        set(value) { prefs.edit {putInt("overlay_panel_width",value.coerceIn(180,360))} }
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
        val updated = ConnectionProfiles.upsert(profiles, if (old != null) profile.copy(label = label.ifBlank { old.label }, lastUsedAt = old.lastUsedAt) else profile)
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
    var autoConnectOnLaunch: Boolean
        get() = prefs.getBoolean("auto_connect_launch", true)
        set(value) = prefs.edit { putBoolean("auto_connect_launch", value) }

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
