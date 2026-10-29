package com.dsh.mobile.core

import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ConnectionProfile(
    val id: String,
    val label: String,
    val kind: Kind,
    val baseUrl: String,
    val lastUsedAt: Long = 0,
    val meshProvider: MeshProvider? = null,
) {
    enum class MeshProvider { TAILSCALE, WIREGUARD, EXTERNAL }
    enum class Kind(val title: String, val publicEntry:Boolean=false) {
        TUNNEL("Cloudflare Tunnel",true), RELAY("Relay 固定域名中继",true), CUSTOM("其他 HTTPS / frp",true),
        LAN("Wi-Fi 直连"), MESH("Tailscale / WireGuard"), USB("USB 数据线")
    }
    companion object {
        fun create(label: String, kind: Kind, url: String): ConnectionProfile {
            val origin = ConnectionAddress.origin(url) ?: error("地址格式不正确")
            // Known QR hosts determine the real route, regardless of the public
            // form used to scan them. Unknown custom hosts keep the user's choice.
            val effectiveKind = if(kind.publicEntry) ConnectionAddress.recognizedRemoteKind(origin) ?: kind else kind
            require(ConnectionAddress.allowed(origin, effectiveKind)) { "地址与连接方式不匹配" }
            val id = MessageDigest.getInstance("SHA-256").digest(origin.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
            val displayLabel=label.trim().take(60).let {
                if(effectiveKind==Kind.MESH && URI(origin).host.endsWith(".ts.net") && it in setOf("Cloudflare / Relay",Kind.TUNNEL.title)) "Tailscale"
                else if(it=="Cloudflare / Relay") effectiveKind.title
                else if(effectiveKind!=kind && it==kind.title) effectiveKind.title else it
            }
            return ConnectionProfile(id, displayLabel.ifBlank { effectiveKind.title }, effectiveKind, origin)
        }
    }
}

object ConnectionAddress {
    /** Only known service hostnames are evidence; DNS/CDN placement is not. */
    fun recognizedRemoteKind(raw:String):ConnectionProfile.Kind? {
        val host=runCatching {URI(raw).host?.lowercase()}.getOrNull() ?: return null
        return when {
            host.endsWith(".trycloudflare.com") -> ConnectionProfile.Kind.TUNNEL
            host.endsWith(".dsh-market.com") -> ConnectionProfile.Kind.RELAY
            host.endsWith(".ts.net") -> ConnectionProfile.Kind.MESH
            else -> null
        }
    }
    fun origin(raw: String): String? = runCatching {
        val u = URI(raw.trim())
        require(u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null)
        require(u.port == -1 || u.port in 1..65535)
        val scheme = u.scheme.lowercase()
        val port = u.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        "$scheme://${u.host.lowercase()}${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()
    private fun ipv4(host: String): List<Int>? {
        val p = host.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.any { c -> !c.isDigit() } || (it.length > 1 && it.startsWith('0')) }) return null
        return p.map { it.toIntOrNull() ?: return null }.takeIf { it.all { n -> n in 0..255 } }
    }
    fun isPrivateIpv4(host: String): Boolean {
        val p = ipv4(host) ?: return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
    fun isCgnatOrPrivate(host: String): Boolean {
        val p = ipv4(host)
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        return isPrivateIpv4(host) || (p != null && p[0] == 100 && p[1] in 64..127) ||
            (h.contains(':') && (h.startsWith("fc") || h.startsWith("fd")))
    }
    fun isLocalish(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        val p = ipv4(h)
        return h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1" || h == "::" ||
            h.startsWith("::ffff:") || h.startsWith("fe80:") || isCgnatOrPrivate(h) ||
            (p != null && (p[0] == 0 || p[0] == 127 || p[0] >= 224 || (p[0] == 169 && p[1] == 254))) ||
            (p == null && h.matches(Regex("(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\.(?:0[xX][0-9a-fA-F]+|[0-9]+))*")))
    }
    fun allowed(url: String, kind: ConnectionProfile.Kind): Boolean = runCatching {
        val u = URI(origin(url) ?: return false)
        when (kind) {
            ConnectionProfile.Kind.USB -> DevelopmentTransport.isUsbOrigin(url)
            ConnectionProfile.Kind.LAN -> isPrivateIpv4(u.host)
            ConnectionProfile.Kind.MESH -> isCgnatOrPrivate(u.host) || (!isLocalish(u.host) && u.host.any { it.isLetter() })
            ConnectionProfile.Kind.TUNNEL, ConnectionProfile.Kind.RELAY, ConnectionProfile.Kind.CUSTOM -> !isLocalish(u.host)
        }
    }.getOrDefault(false)
    fun infer(url: String): ConnectionProfile.Kind? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        return when {
            DevelopmentTransport.isUsbOrigin(url) -> ConnectionProfile.Kind.USB
            recognizedRemoteKind(url)!=null -> recognizedRemoteKind(url)
            isPrivateIpv4(host) -> ConnectionProfile.Kind.LAN
            isCgnatOrPrivate(host) -> ConnectionProfile.Kind.MESH
            !isLocalish(host) -> ConnectionProfile.Kind.CUSTOM
            else -> null
        }
    }
    fun localInput(raw: String): String? {
        val url = raw.trim().let { if ("://" in it) it else "http://$it" }
        return origin(url)?.takeIf { allowed(it, ConnectionProfile.Kind.LAN) }
    }
}

/** Pure, testable persistence format. One-shot grants are deliberately not profile fields. */
object ConnectionProfiles {
    fun encode(profiles: List<ConnectionProfile>): String = JSONArray().apply {
        profiles.forEach { p -> put(JSONObject().put("routeVersion",2).put("id", p.id).put("label", p.label).put("kind", p.kind.name).put("baseUrl", p.baseUrl).put("lastUsedAt", p.lastUsedAt).apply {p.meshProvider?.let {put("meshProvider",it.name)}}) }
    }.toString()
    fun decode(json: String): List<ConnectionProfile> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            val url=o.getString("baseUrl")
            val storedKind=ConnectionProfile.Kind.valueOf(o.getString("kind"))
            // Legacy TUNNEL meant every public route. Keep identity and cookies;
            // unknown legacy hosts become custom, never a guessed Named Tunnel.
            val kind=if(storedKind==ConnectionProfile.Kind.TUNNEL && o.optInt("routeVersion",0)<2)
                ConnectionAddress.recognizedRemoteKind(url) ?: ConnectionProfile.Kind.CUSTOM else storedKind
            ConnectionProfile.create(o.optString("label"),kind,url)
                .copy(lastUsedAt = o.optLong("lastUsedAt"),meshProvider=runCatching {ConnectionProfile.MeshProvider.valueOf(o.optString("meshProvider"))}.getOrNull())
        }.getOrNull() }.distinctBy { it.id }
    }
    fun migrate(legacy: String, usb: Boolean): List<ConnectionProfile> = if (legacy.isBlank()) emptyList() else runCatching {
        // A legacy public connection retains its origin/cookies; USB has its own namespace.
        val kind = ConnectionAddress.infer(legacy) ?: error("Unknown route")
        require(kind != ConnectionProfile.Kind.USB || usb)
        listOf(ConnectionProfile.create("默认连接", kind, legacy))
    }.getOrDefault(emptyList())
    fun upsert(all: List<ConnectionProfile>, profile: ConnectionProfile): List<ConnectionProfile> =
        all.filterNot { it.id == profile.id } + profile
    fun select(all: List<ConnectionProfile>, id: String): ConnectionProfile? = all.firstOrNull { it.id == id }
    fun restore(all: List<ConnectionProfile>, id: String?, legacy: String): ConnectionProfile? =
        select(all, id.orEmpty()) ?: all.firstOrNull { it.baseUrl == ConnectionAddress.origin(legacy) }
}
