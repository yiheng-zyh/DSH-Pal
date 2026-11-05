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
        // 命名原则：**按它在用户那里的实际地位叫，不按技术分类叫**。
        //
        // CUSTOM 原来叫「其他 HTTPS / frp」。用户 2026-11-05 的原话：
        // 「那个名字感觉是替补品的感觉……听起来感觉就是个替补，但实际他才是主力」。
        // 他说得对：frp 固定域名正是他日常真正在用的通道，而「其他」这个词把
        // 主入口说成了兜底选项。TUNNEL / RELAY 是特定服务商的名字，不该让它俩
        // 天然占住「正式频道」的语感。
        //
        // 改成「自建固定域名」：讲清它是什么（自己的域名、自己的转发），
        // 又不带任何「剩下的/其它」的贬义。副标题在帮助页里继续提 frp/HTTPS 的细节。
        TUNNEL("Cloudflare Tunnel",true), RELAY("Relay 固定域名中继",true), CUSTOM("自建固定域名",true),
        LAN("Wi-Fi 直连"), IPV6("IPv6 直连"), MESH("Tailscale / WireGuard"), USB("USB 数据线")
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
        val u = URI(bracketIpv6(raw.trim()))
        require(u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrBlank() && u.userInfo == null)
        require(u.port == -1 || u.port in 1..65535)
        val scheme = u.scheme.lowercase()
        val port = u.port.takeUnless { it == -1 || (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
        "$scheme://${u.host.lowercase()}${port?.let { ":$it" }.orEmpty()}"
    }.getOrNull()
    /**
     * 裸 IPv6 authority 补方括号。`http://2408:8000::1/x` 用 java.net.URI 解析时
     * getHost() 返回 null（它把整串当成端口歧义的 authority），而用户手输 IPv6
     * 时几乎不会主动加方括号。这里只在 authority 含 >=2 个冒号（即不可能是
     * host:port）且不含 userInfo 时补括号，其余输入原样交给 URI。
     *
     * 固有歧义：`http://2408:8000::1:8080` 无法区分「地址末组 8080」与「端口 8080」。
     * 补括号后若末段超过 4 位十六进制（如 :18772），URI 会判定非法而整体失败 ——
     * 这是**安全失败**：宁可拒绝也不猜一个可能错误的地址。正确写法是 `[addr]:port`，
     * UI 错误文案已明确要求方括号。
     */
    internal fun bracketIpv6(raw: String): String {
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd < 0) return raw
        val rest = raw.substring(schemeEnd + 3)
        if (rest.startsWith("[")) return raw
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (cut < 0) rest else rest.substring(0, cut)
        // 带 userInfo（user:pw@host）的交给 URI 正常拒绝，不在这里补括号。
        if ('@' in authority || authority.count { it == ':' } < 2) return raw
        return raw.substring(0, schemeEnd + 3) + "[$authority]" + if (cut < 0) "" else rest.substring(cut)
    }
    private fun ipv4(host: String): List<Int>? {
        val p = host.split('.')
        if (p.size != 4 || p.any { it.isEmpty() || it.any { c -> !c.isDigit() } || (it.length > 1 && it.startsWith('0')) }) return null
        return p.map { it.toIntOrNull() ?: return null }.takeIf { it.all { n -> n in 0..255 } }
    }
    /** 解析 IPv6 字面量为 8 个 16 位组；非法写法返回 null。支持 `::` 压缩、内嵌 IPv4、zone id。 */
    private fun ipv6Groups(host: String): List<Int>? {
        val body = host.removePrefix("[").removeSuffix("]").lowercase()
            .let { if ('%' in it) it.substringBefore('%') else it }   // 去 zone id（URL 里是 %25eth0）
        if (body.isEmpty() || !body.contains(':')) return null
        val halves = body.split("::")
        if (halves.size > 2) return null
        fun expand(part: String, allowEmbeddedIpv4: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val items = part.split(':')
            val out = ArrayList<Int>(items.size)
            for ((index, item) in items.withIndex()) {
                if (item.isEmpty()) return null
                val isLast = index == items.lastIndex
                if (item.contains('.')) {
                    // 内嵌 IPv4 只能出现在末尾（::ffff:127.0.0.1），且仅在有 `::` 时才可能落到非末位
                    if (!allowEmbeddedIpv4 || !isLast) return null
                    val v4 = ipv4(item) ?: return null
                    out += (v4[0] shl 8) or v4[1]; out += (v4[2] shl 8) or v4[3]
                } else {
                    if (item.length > 4 || item.any { c -> !(c.isDigit() || c in 'a'..'f') }) return null
                    out += item.toInt(16)
                }
            }
            return out
        }
        return when (halves.size) {
            // 无 `::`：必须填满 8 组；末尾内嵌 IPv4（如 0:0:0:0:0:ffff:127.0.0.1）占 2 组。
            1 -> expand(halves[0], true)?.takeIf { it.size == 8 }
            else -> {
                val head = expand(halves[0], true) ?: return null
                val tail = expand(halves[1], true) ?: return null
                if (head.size + tail.size > 7) return null   // `::` 至少代表一组全零
                head + List(8 - head.size - tail.size) { 0 } + tail
            }
        }
    }
    fun isIpv6(host: String): Boolean = ipv6Groups(host) != null
    fun isPrivateIpv4(host: String): Boolean {
        val p = ipv4(host) ?: return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
    /**
     * 全局单播 IPv6：语法合法，且不属于任何「别的设备到不了」的类别
     * （回环 ::1、未指定 ::、链路本地 fe80::/10、ULA fc00::/7 含 Tailscale 的 fd7a:、
     * 文档 2001:db8::/32、IPv4 映射 ::ffff:、组播 ff00::/8）。
     *
     * 这类地址是「IPv6 直连」通道的判据：手机不需要任何隧道就能路由到电脑，
     * 也不受 IPv4 NAT 影响（PC 的 IPv4 常在运营商 NAT 后，IPv6 才是公网可达的）。
     */
    fun isGlobalIpv6(host: String): Boolean {
        val groups = ipv6Groups(host) ?: return false
        val first = groups[0]
        // ::ffff:a.b.c.d 映射形式：前五组全零、第六组 ffff，其后是 IPv4，不是可路由的 IPv6 目标。
        val isMappedIpv4 = groups.subList(0, 5).all { it == 0 } && groups[5] == 0xffff
        return when {
            groups.all { it == 0 } -> false                       // :: 未指定地址
            groups.dropLast(1).all { it == 0 } && groups.last() == 1 -> false   // ::1 回环
            isMappedIpv4 -> false                                 // ::ffff:127.0.0.1 等映射地址
            (first shr 6) == 0b1111111010 -> false                // fe80::/10 链路本地（前 10 位）
            (first shr 9) == 0b1111110 -> false                   // fc00::/7 ULA（含 Tailscale 的 fd7a:）
            (first shr 8) == 0xff -> false                        // ff00::/8 组播
            first == 0x2001 && groups[1] == 0x0db8 -> false       // 2001:db8::/32 文档示例
            else -> true
        }
    }
    fun isCgnatOrPrivate(host: String): Boolean {
        val p = ipv4(host)
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        return isPrivateIpv4(host) || (p != null && p[0] == 100 && p[1] in 64..127) ||
            (h.contains(':') && (h.startsWith("fc") || h.startsWith("fd")))
    }
    fun isLocalish(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        // IPv6 字面量统一交给 isGlobalIpv6 判定：非全局单播（回环/链路本地/ULA/映射/
        // 文档/组播）都是「别的设备到不了」。旧实现只匹配 fe80:/fc/fd 字符串前缀，
        // 会漏掉 feb0::（fe80::/10 的另一半）与 2001:db8::。
        if (isIpv6(h)) return !isGlobalIpv6(h)
        val p = ipv4(h)
        return h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
            isCgnatOrPrivate(h) ||
            (p != null && (p[0] == 0 || p[0] == 127 || p[0] >= 224 || (p[0] == 169 && p[1] == 254))) ||
            (p == null && h.matches(Regex("(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\.(?:0[xX][0-9a-fA-F]+|[0-9]+))*")))
    }
    /**
     * 手机不可能经回环到达电脑，这类地址只可能是 adb 调试残留，保存后必须清掉。
     * 与 isLocalish 的区别：isLocalish 还包含局域网私有地址（那是合法连接），
     * 这里只判回环/未指定（127.0.0.0/8、::1、::、localhost、*.localhost），
     * 供「已保存入口是否失效」使用。IPv6 字面量带方括号，先剥再判。
     */
    fun isLoopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        val p = ipv4(h)
        return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h == "::" ||
            (p != null && p[0] == 127)
    }
    fun allowed(url: String, kind: ConnectionProfile.Kind): Boolean = runCatching {
        val u = URI(origin(url) ?: return false)
        when (kind) {
            ConnectionProfile.Kind.USB -> DevelopmentTransport.isUsbOrigin(url)
            ConnectionProfile.Kind.LAN -> isPrivateIpv4(u.host)
            ConnectionProfile.Kind.IPV6 -> isGlobalIpv6(u.host)
            ConnectionProfile.Kind.MESH -> isCgnatOrPrivate(u.host) || (!isLocalish(u.host) && u.host.any { it.isLetter() })
            ConnectionProfile.Kind.TUNNEL, ConnectionProfile.Kind.RELAY, ConnectionProfile.Kind.CUSTOM -> !isLocalish(u.host)
        }
    }.getOrDefault(false)
    /** 全局 IPv6 字面量的专属通道；其它 host 返回 null。 */
    fun publicIpv6Kind(url: String): ConnectionProfile.Kind? =
        runCatching { URI(origin(url) ?: return null).host }.getOrNull()
            ?.takeIf { isGlobalIpv6(it) }?.let { ConnectionProfile.Kind.IPV6 }
    fun infer(url: String): ConnectionProfile.Kind? {
        val host = runCatching { URI(bracketIpv6(url)).host }.getOrNull() ?: return null
        return when {
            DevelopmentTransport.isUsbOrigin(url) -> ConnectionProfile.Kind.USB
            recognizedRemoteKind(url)!=null -> recognizedRemoteKind(url)
            isPrivateIpv4(host) -> ConnectionProfile.Kind.LAN
            // ULA（Tailscale 的 fd7a: 等）先归组网，再判全局 IPv6，顺序不能换。
            isCgnatOrPrivate(host) -> ConnectionProfile.Kind.MESH
            isGlobalIpv6(host) -> ConnectionProfile.Kind.IPV6
            !isLocalish(host) -> ConnectionProfile.Kind.CUSTOM
            else -> null
        }
    }
    /**
     * 手输直连地址（无 scheme 时补 http://）：返回规范化 origin 与它实际属于的类型。
     * 只接受局域网私有 IPv4 与全局 IPv6 —— 这两类都是「不需要隧道的直连」，
     * 也是唯一能让用户裸填 IP 就用的场景。域名一律返回 null（应走扫码或公网通道）。
     */
    fun directInput(raw: String): Pair<String, ConnectionProfile.Kind>? {
        val normalized = origin(raw.trim().let { if ("://" in it) it else "http://$it" }) ?: return null
        val host = runCatching { URI(normalized).host }.getOrNull() ?: return null
        val kind = when {
            isPrivateIpv4(host) -> ConnectionProfile.Kind.LAN
            isGlobalIpv6(host) -> ConnectionProfile.Kind.IPV6
            else -> return null
        }
        return normalized to kind
    }
    fun localInput(raw: String): String? = directInput(raw)?.first
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
