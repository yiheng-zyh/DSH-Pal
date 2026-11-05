import net from 'node:net';
import os from 'node:os';

/**
 * 直连入口的地址判定与枚举（纯逻辑，可单测）。
 *
 * 背景：DSH Pal 的国内 IPv4 通道靠 frpc 隧道，因为家用宽带的 IPv4 通常在运营商
 * NAT 后面进不来。但 IPv6 地址一般是公网可路由的 —— 手机和电脑都拿到 IPv6 时，
 * 手机可以直连电脑，不需要任何隧道（实测同运营商 4-30ms、0% 丢包）。
 *
 * 这里的判据必须和 App 侧 core/ConnectionProfile.kt 的 isGlobalIpv6 /
 * isPrivateIpv4 保持一致，否则会出现「电脑生成了二维码、手机拒绝保存」的死锁。
 */

/** 剥掉 IPv6 的方括号与 zone id（`%eth0` / URL 编码的 `%25eth0`）。 */
export function bareAddress(value) {
  let text = String(value ?? '').trim().toLowerCase();
  if (text.startsWith('[') && text.endsWith(']')) text = text.slice(1, -1);
  const percent = text.indexOf('%');
  if (percent >= 0) text = text.slice(0, percent);
  return text;
}

/** 是不是合法的 IPv6 字面量（net.isIPv6 认完整写法，不认方括号）。 */
export function isIPv6Literal(value) {
  return net.isIPv6(bareAddress(value));
}

/** 是不是合法的 IPv4 字面量。 */
export function isIPv4Literal(value) {
  return net.isIPv4(String(value ?? '').trim());
}

/**
 * 全局单播 IPv6：语法合法，且不属于任何「别的设备到不了」的类别。
 * 与 App 侧 ConnectionAddress.isGlobalIpv6 同一套规则：
 *   :: / ::1        未指定与回环
 *   ::ffff:a.b.c.d  IPv4 映射
 *   fe80::/10       链路本地（fe80-febf）
 *   fc00::/7        ULA，含 Tailscale 的 fd7a:
 *   ff00::/8        组播
 *   2001:db8::/32   文档示例
 */
export function isGlobalIPv6(value) {
  const text = bareAddress(value);
  if (!net.isIPv6(text)) return false;
  if (text === '::' || text === '::1') return false;
  if (text.startsWith('::ffff:')) return false;
  if (text.startsWith('fe8') || text.startsWith('fe9') || text.startsWith('fea') || text.startsWith('feb')) return false;
  if (text.startsWith('fc') || text.startsWith('fd')) return false;
  if (text.startsWith('ff')) return false;
  if (/^2001:0?d?b8:/i.test(text)) return false;
  return true;
}

/** RFC1918 私有 IPv4，与 App 侧 isPrivateIpv4 同规则。 */
export function isPrivateIPv4(value) {
  const text = String(value ?? '').trim();
  if (!net.isIPv4(text)) return false;
  const [a, b] = text.split('.').map(Number);
  return a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31);
}

/**
 * URL authority 的写法：IPv6 必须加方括号，IPv4/域名原样。
 * 插件的 trustedHosts 匹配走 `new URL('http://'+entry)`，IPv6 不加方括号会让
 * URL 构造直接抛异常、匹配永远失败（已实测确认）；而 IPv4 加了方括号反而会被
 * 当成非法 IPv6，App 侧会拒绝保存 —— 两边都必须按地址族分别处理。
 */
export function authorityOf(address, port) {
  const bare = bareAddress(address);
  const host = bare.includes(':') ? `[${bare}]` : bare;
  return `${host}:${String(port)}`;
}

/** 直连入口用的 origin（http 明文：IPv6 字面量拿不到公共 TLS 证书，App 也不信任自签证书）。 */
export function directOriginOf(address, port) {
  return `http://${authorityOf(address, port)}`;
}

/**
 * 不该向手机广告的接口。两类：
 *
 * ① 虚拟化 / 容器网卡（WSL、Docker、Hyper-V、VMware、VirtualBox）—— 它们的私有
 *    地址（172.20.0.1、192.168.65.x）在宿主机内部，手机永远路由不到。地址类别
 *    过滤挡不住它们，因为那些地址本身就是合法私有 IPv4。
 * ② 隧道伪接口（Tailscale / WireGuard / ZeroTier / Teredo / 6to4 / IP-HTTPS）——
 *    它们属于组网通道，不是直连；ULA 与 CGNAT 地址也会被类别过滤挡掉，这里只是
 *    双保险。
 *
 * 刻意**不**排除：Wi-Fi Direct 虚拟适配器（Windows 上叫「本地连接* N」，承载 PC
 * 自己开的热点，手机连 PC 热点时正是靠它，实测 192.168.1.10 可达）与任何物理网卡。
 * 宁可多广告一个候选让用户/二维码选，也不要猜错把可用接口滤掉 —— PC 无法知道手机
 * 在哪个网段。
 */
const UNADVERTISABLE_INTERFACE = /(vEthernet|Hyper-V|WSL|Docker|veth|br-|virbr|VMware|VirtualBox|vboxnet|VMnet|Tailscale|WireGuard|wg\d|ZeroTier|zt\d|Teredo|6to4|IP-HTTPS|Loopback|Loopback Pseudo)/i;

/**
 * 本机可以向配对设备广告的地址。
 *
 * IPv6 只留全局单播（隧道用的 ULA 归 Tailscale 通道，链路本地别的设备到不了）；
 * IPv4 只留私有地址（公网 IPv4 通常在运营商 NAT 后，直连没意义，走隧道才对）。
 * 同一接口可能因隐私扩展出现多个 IPv6（PC 实测同时有 Random 与 Link 两个），全部返回，
 * 由调用方决定取哪个 —— 不能只取第一个，否则可能选中已失效的那个。
 *
 * @param interfaces - os.networkInterfaces() 的形状，可注入以便单测。
 * @returns {{ipv6: Array<{iface:string,address:string}>, ipv4: Array<{iface:string,address:string}>}}
 */
export function advertisableAddresses(interfaces = os.networkInterfaces()) {
  const ipv6 = [];
  const ipv4 = [];
  const seen = new Set();
  for (const [name, entries] of Object.entries(interfaces ?? {})) {
    if (UNADVERTISABLE_INTERFACE.test(name)) continue;
    for (const entry of entries ?? []) {
      if (entry.internal) continue;
      const address = bareAddress(entry.address);
      // 链路本地 IPv4（169.254.x，APIPA）与带 zone id 的 IPv6 都无法被手机路由到。
      if (String(entry.address).includes('%')) continue;
      const key = `${name}|${address}`;
      if (seen.has(key)) continue;
      seen.add(key);
      if (entry.family === 'IPv6' && isGlobalIPv6(address)) ipv6.push({iface: name, address});
      else if (entry.family === 'IPv4' && isPrivateIPv4(address)) ipv4.push({iface: name, address});
    }
  }
  return {ipv6, ipv4};
}

/**
 * 直连入口的期望集合。纯函数：给地址与端口，算出应该监听什么、对外说什么。
 *
 * 设计要点（对应用户决策「默认关闭、需显式开启」）：
 *  - enableIPv6 / enableLAN 都为 false 时返回空集合，网关行为与改动前完全一致；
 *  - 请求了 IPv6 但本机没有全局 IPv6 时，返回 reason 而不是抛错 —— 换网络后
 *    地址消失是常态，不能让网关因此起不来（loopback 监听必须永远保住）。
 *
 * @param {{enableIPv6?:boolean, enableLAN?:boolean, port:number, ipv6?:Array<{iface:string,address:string}>, ipv4?:Array<{iface:string,address:string}>}} input
 */
export function planDirectEntries(input) {
  const port = Number(input?.port);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error('port must be an integer in 1024..65535');
  const entries = [];
  const reasons = [];
  if (input?.enableIPv6 === true) {
    const candidates = input.ipv6 ?? [];
    if (candidates.length === 0) reasons.push('ipv6-unavailable');
    else for (const candidate of candidates) entries.push({
      kind: 'ipv6', iface: candidate.iface, address: bareAddress(candidate.address), port,
      origin: directOriginOf(candidate.address, port),
      // 插件 trustedHosts 与网关 Host 白名单都用带方括号的 authority。
      trustedHost: authorityOf(candidate.address, port),
    });
  }
  if (input?.enableLAN === true) {
    const candidates = input.ipv4 ?? [];
    if (candidates.length === 0) reasons.push('lan-unavailable');
    else for (const candidate of candidates) entries.push({
      kind: 'lan', iface: candidate.iface, address: String(candidate.address), port,
      origin: directOriginOf(candidate.address, port),
      trustedHost: authorityOf(candidate.address, port),
    });
  }
  return {entries, reasons};
}

/**
 * 校验一条配置里的直连入口。只接受「IP 字面量 + http」这一种形态：
 * 域名形态的入口属于隧道（publicOrigin），有它自己的严格校验与证书要求。
 * 允许 http 是因为 IPv6/LAN 字面量无法申请公共证书，而 App 已声明
 * usesCleartextTraffic，且不信任自签证书 —— 走 https 只会让用户看到证书错误页。
 */
export function directEntry(value, name = 'directEntry') {
  if (!value || typeof value !== 'object') throw Error(`${name} must be an object`);
  const address = bareAddress(value.address);
  const port = Number(value.port);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw Error(`${name}.port must be an integer in 1024..65535`);
  const kind = value.kind;
  if (kind === 'ipv6') {
    if (!isGlobalIPv6(address)) throw Error(`${name}: ${address} is not a routable global IPv6 address`);
  } else if (kind === 'lan') {
    if (!isPrivateIPv4(address)) throw Error(`${name}: ${address} is not a private LAN IPv4 address`);
  } else {
    throw Error(`${name}.kind must be ipv6 or lan`);
  }
  return {kind, address, port, origin: directOriginOf(address, port), trustedHost: authorityOf(address, port)};
}
