package com.dsh.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网直连自动发现。
 *
 * 为什么需要（2026-11-04 用户反馈）：
 *   「我现在不就是连着电脑的热点吗？为什么这两个界面没有显示？
 *     应该做成自动发现的呀。」
 *
 * 现况：项目里原本**没有任何局域网扫描**，Wi-Fi 直连只能手动输入
 * `http://192.168.x.x:端口` 或扫码。用户连着电脑热点时，明明一台可达的
 * DSH 就在同网段，界面却写「尚未配置」—— 这是真实的功能缺口。
 *
 * 做法（不引入依赖、不申请额外权限）：
 *   1. 从 NetworkInterface 取本机所有私有 IPv4 地址与掩码，算出同网段范围；
 *   2. 对每个候选地址并发探一个常见端口区间（DSH 端口每次启动随机，
 *      所以只能扫端口；这是唯一可靠的发现方式）；
 *   3. 命中判据：TCP 能连上 **且** HTTP 响应里能看到 DSH 特征，
 *      避免把路由器、打印机之类的开放端口误判成 DSH；
 *   4. 返回按「响应快」排序的结果。
 *
 * 边界与克制：
 *   - 只扫**私有网段**（10/8、172.16/12、192.168/16），不碰公网；
 *   - 并发上限固定，不把手机网络打满（这也是它只适合局域网的另一个理由）；
 *   - 单次扫描总能超时退出，不会卡住界面；
 *   - 结果只是**候选**，最终由调用方拿地址去正常配对，扫描本身不改任何状态。
 *
 * @module dsh-mobile/core/LanDiscovery
 */

/** 扫描到的一个候选入口。 */
data class DiscoveredLan(
    /** 规范化 origin，例如 `http://192.168.43.12:65264`。 */
    val origin: String,
    /** 建连耗时（毫秒），用于排序：同一个 DSH 可能被多个端口命中。 */
    val connectMs: Long,
)

object LanDiscovery {
    /**
     * 要探的端口。
     *
     * 2026-11-04 关键修正：**不能只扫 DSH 的随机端口**。
     * 实测：DSH 自己只监听 `127.0.0.1:<随机端口>`（这是设计约束，不能改），
     * 所以手机在局域网上**根本连不到 DSH 本体**。局域网可达的是
     * `tools/remote-access` 的网关（固定 18772），它再由本机转发到 DSH。
     *
     * 因此这里把网关端口排在最前：那是真正能连通的入口。
     * 余下的是 DSH 历史可能用过的端口（万一用户直接暴露过），
     * 以及少量常见 Web 端口兜底。
     */
    private val FALLBACK_PORTS = intArrayOf(
        // 1) remote-access 网关：局域网直连的唯一正确入口。
        18772,
        // 2) DSH 常见端口（历史值，仅作兜底）。
        65264, 63622, 53622, 50596, 64999,
        // 3) 少量常见 Web 端口。
        3000, 5000, 8000, 8080, 8443, 9000,
    )

    /** 单次扫描的最长耗时：超时就把已有结果交出去，不无限等。 */
    private const val SCAN_BUDGET_MS = 8_000L

    /** 单个连接的建连超时：局域网内到达应在 200ms 内，1s 已经非常宽松。 */
    private const val CONNECT_TIMEOUT_MS = 600

    /** 并发上限：太高会让低端机型发热、也容易把路由器 ARP 表打满。 */
    private const val MAX_CONCURRENCY = 48

    /**
     * 扫描同网段内可能的 DSH 入口。
     *
     * @param extraPorts 额外要试的端口（调用方传历史端口，命中率最高）。
     * @param onProgress 可选进度回调（已探测数 / 总数），用于在界面上显示进度。
     * @returns 按建连耗时升序的候选列表；空表示本次没发现（不代表没有）。
     */
    suspend fun scan(
        extraPorts: List<Int> = emptyList(),
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DiscoveredLan> = withContext(Dispatchers.IO) {
        val local = localIPv4Networks()
        // 诊断：把"本机私有网段"与"要扫的目标数"落进日志。
        // 扫描失败时仅凭界面上的「没找到」无法区分是"网段没识别出来"
        // 还是"端口不对"还是"对端不可达"，日志能一次说清。
        RunLog.info("LanDiscovery", "local networks=${local.map { it.first.hostAddress + "/" + it.second }}")
        if (local.isEmpty()) {
            RunLog.warn("LanDiscovery", "no private IPv4 interface; cannot scan")
            return@withContext emptyList()
        }

        // 候选主机：只取同网段 /24 内的地址。热点通常是 /24，
        // 更大网段（/16）扫完要 6 万次，手机扛不住也没必要。
        val hosts: LinkedHashSet<String> = LinkedHashSet()
        val networks: List<Pair<Inet4Address, Int>> = local
        for (entry in networks) {
            val address: Inet4Address = entry.first
            val prefix: Int = entry.second
            val bytes = address.address
            val bits: Int = if (prefix in 1..32) prefix else 24
            // 只处理 /16 及以上；更宽的网段按 /24 处理（够用且可控）。
            val effective: Int = bits.coerceAtLeast(24)
            val mask: Int = if (effective >= 32) 0 else (-1 shl (32 - effective))
            val network: Int = bytes.foldIndexed(0) { index, acc, value ->
                acc or ((value.toInt() and 0xFF) shl (24 - index * 8))
            } and mask
            val broadcast: Int = network or mask.inv()
            // 跳过网络号与广播地址；主机数限制在 /24 内。
            val first: Int = (broadcast - 254).coerceAtLeast(network + 1)
            for (candidate in first until broadcast) {
                hosts.add(ipv4ToString(candidate))
            }
        }
        if (hosts.isEmpty()) return@withContext emptyList()

        // 显式类型：extraPorts 是 List<Int>、FALLBACK_PORTS 是 IntArray，
        // 直接相加会推出 List<Serializable>，导致后面的 port 变成 Serializable。
        val ports: List<Int> = (extraPorts.toList() + FALLBACK_PORTS.toList())
            .filter { it in 1..65535 }
            .distinct()
        val total = hosts.size * ports.size
        val scanned = AtomicInteger(0)
        val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS

        val hits = coroutineScope {
            val hostList: List<String> = hosts.toList()
            hostList.map { hostName ->
                async {
                    var found: DiscoveredLan? = null
                    for (port in ports) {
                        if (System.currentTimeMillis() > deadline) break
                        val ms: Long = probe(hostName, port)
                        val done = scanned.incrementAndGet()
                        if (done % 64 == 0) onProgress(done, total)
                        if (ms >= 0L) {
                            found = DiscoveredLan("http://$hostName:$port", ms)
                            // 同一主机命中一个端口就够了：DSH 只监听一个端口。
                            break
                        }
                    }
                    found
                }
            }.awaitAll().filterNotNull()
        }
        onProgress(total, total)
        // 诊断：扫了多少目标、命中几个。命中为 0 时这条日志能说明
        // "确实扫了 N 个目标"，而不是扫描根本没启动。
        RunLog.info("LanDiscovery", "scanned=$total ports=${ports.size} hits=${hits.size}")
        // 建连越快越可能是同一局域网内直连，排前面。
        hits.sortedBy { it.connectMs }
    }

    /**
     * 探一个 host:port 是不是活的 DSH。
     * @returns 建连耗时（毫秒）；不可达返回 -1。
     */
    private fun probe(host: String, port: Int): Long {
        val started = System.currentTimeMillis()
        var result: Long = -1L
        try {
            val socket = Socket()
            socket.use {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = CONNECT_TIMEOUT_MS
                // 关键：requests 要用**局域网 IP 作为 Host**，不能写 127.0.0.1。
                // remote-access 网关会校验 Host 头来决定是否当成本机直连，
                // 用回环 Host 从别的机器发过来会被它按远程处理（可能 403）。
                val request = "HEAD /api/pair/status HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                val buffer = ByteArray(4096)
                val read = socket.getInputStream().read(buffer)
                if (read > 0) {
                    val text = String(buffer, 0, read, Charsets.ISO_8859_1)
                    // 判据：必须是 HTTP 响应，且不是常见的路由器/打印机欢迎页。
                    // 这里**不要求 200** —— 网关对未配对请求会回 401/403，
                    // 那同样证明"这台机器上跑着 DSH 网关"，比只认 200 更可靠。
                    val isHttp = text.startsWith("HTTP/")
                    val isRouter = LOOKS_LIKE_ROUTER.containsMatchIn(text)
                    if (isHttp && !isRouter) {
                        result = System.currentTimeMillis() - started
                    }
                }
            }
        } catch (_: Exception) {
            result = -1L
        }
        return result
    }

    /** 明显不是 DSH 的响应特征（避免把常见设备误报成 DSH）。 */
    private val LOOKS_LIKE_ROUTER = Regex(
        "Server:\\s*(lighttpd|micro_httpd|Router|BoA|GoAhead|mini_httpd)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 枚举本机的私有 IPv4 地址与掩码前缀。
     *
     * 只保留私有网段：公网地址上扫邻居既没意义也有滥用嫌疑。
     */
    private fun localIPv4Networks(): List<Pair<Inet4Address, Int>> {
        val result = ArrayList<Pair<Inet4Address, Int>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        for (nif in interfaces) {
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            for (address in runCatching { nif.inetAddresses }.getOrNull() ?: continue) {
                if (address !is Inet4Address) continue
                val bytes = address.address
                val private = (bytes[0].toInt() and 0xFF) == 10 ||
                    ((bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xFF) in 16..31) ||
                    ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168)
                if (!private) continue
                val prefix = runCatching { nif.interfaceAddresses }
                    .getOrNull()
                    ?.firstOrNull { it.address == address }
                    ?.networkPrefixLength
                    ?.toInt() ?: 24
                result += address to prefix
            }
        }
        return result
    }

    private fun ipv4ToString(value: Int): String =
        "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
}
