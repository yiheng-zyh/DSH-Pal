/**
 * 连接通道总览 —— 「用户能不能看见自己的通道」这一层的数据源。
 *
 * 为什么需要这个模块：
 * 通道状态原先散在三处，且没有一处是"给用户看的"：
 *   1. 隧道通道（国内 frp / Cloudflare）在 remote-web-ui 的配置里，只有本插件读得懂；
 *   2. 直连通道（IPv6 / 局域网）在 `%LOCALAPPDATA%\DSHPal\remote-access\` 的
 *      config.json（开关）与 state.json（网关实测的监听结果）里，只有命令行工具读得懂；
 *   3. 组网通道（Tailscale / WireGuard）只在手机 App 里，PC 侧根本不知道。
 * 用户（包括作者本人）因此只会得出一个结论："好像没配"。本模块把前两处
 * 汇总成一份清单，交给同一个设置页区块渲染。
 *
 * 设计约束：
 *  - **读不到不等于失败**：任何一份文件缺失/损坏都降级成"未配置"，绝不让整块面板消失
 *    （用户看不到面板时，问题就变成"插件坏了"，而他真正需要知道的是"哪条路没开"）。
 *  - **不改任何状态**：这里只读。开关动作走 index.js 的写端点，且必须过 fence。
 *  - **纯函数优先**：通道清单的拼装是纯函数，便于单测；IO 只在 collect* 两个入口。
 *
 * @module dsh-pal-channel/overview
 */
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
// 必须与网关共用同一套地址判据（含 Teredo/WSL/Docker 等接口黑名单），
// 否则面板会算出「3 个公网地址」而网关只绑 2 个，用户会以为有地址没生效。
import { advertisableAddresses } from '../../remote-access/direct.mjs';

/** 网关每 5 秒写一次 state.json；超过这个时长就认为它没在跑。 */
const GATEWAY_STALE_MS = 20000;

/**
 * remote-access 工具的数据目录。
 * @param env - 便于测试注入；默认 process.env
 * @returns 绝对路径；非 Windows 或没有 LOCALAPPDATA 时 null
 */
export function remoteAccessDirectory(env = process.env) {
  if (typeof env.DSHPAL_REMOTE_ACCESS_DIR === 'string' && env.DSHPAL_REMOTE_ACCESS_DIR) {
    return env.DSHPAL_REMOTE_ACCESS_DIR;
  }
  if (process.platform !== 'win32' || !env.LOCALAPPDATA) return null;
  return path.join(env.LOCALAPPDATA, 'DSHPal', 'remote-access');
}

/** 读 JSON；不存在或损坏一律 undefined —— 一个坏文件不能拖垮整块总览。 */
async function readJson(file) {
  if (!file) return undefined;
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'));
  } catch {
    return undefined;
  }
}

/**
 * 本机可作直连入口的全局 IPv6。
 * 直接复用网关的 `advertisableAddresses()`（direct.mjs:109）——同源判据，
 * 面板说"有几个地址"和网关"绑了几个监听"永远一致。
 */
export function globalIpv6Addresses(interfaces = os.networkInterfaces()) {
  const { ipv6 } = advertisableAddresses(interfaces);
  return (ipv6 ?? []).map((item) => ({ address: item.address, interface: item.iface }));
}

/** 全局 IPv6 里有没有"看起来是公网"的（即不属于上面被排除的几类）。 */
export function hasPublicIpv6(addresses) {
  return addresses.length > 0;
}

/**
 * 读网关的直连快照。
 * @returns {{
 *   installed:boolean, directory:string|null,
 *   config:object|null, state:object|null,
 *   gateway:{running:boolean, updatedAt:string|null, targetAvailable:boolean|null,
 *            targetPort:number|null, provider:string|null, publicOrigin:string|null},
 *   direct:{enabled:boolean, ipv6:boolean, lan:boolean},
 *   entries:Array<{kind:string,address:string,port:number,origin:string,listening:boolean}>,
 *   trustedHosts:string[],
 * }}
 */
export async function collectDirect({ directory = remoteAccessDirectory(), now = Date.now() } = {}) {
  const empty = {
    installed: false,
    directory: directory ?? null,
    config: null,
    state: null,
    gateway: {
      running: false, updatedAt: null, targetAvailable: null, targetPort: null,
      provider: null, publicOrigin: null,
    },
    direct: { enabled: false, ipv6: false, lan: false },
    entries: [],
    trustedHosts: [],
  };
  if (!directory) return empty;

  const [config, state] = await Promise.all([
    readJson(path.join(directory, 'config.json')),
    readJson(path.join(directory, 'state.json')),
  ]);
  if (!config && !state) return empty;

  const updated = typeof state?.updatedAt === 'string' ? Date.parse(state.updatedAt) : NaN;
  const running = Number.isFinite(updated) && now - updated < GATEWAY_STALE_MS;
  const direct = {
    enabled: config?.direct?.enabled === true,
    ipv6: config?.direct?.ipv6 === true,
    lan: config?.direct?.lan === true,
  };
  const entries = (Array.isArray(state?.direct) ? state.direct : [])
    .filter((item) => item && typeof item === 'object')
    .map((item) => ({
      kind: String(item.kind ?? ''),
      address: String(item.address ?? ''),
      port: Number(item.port) || 0,
      origin: String(item.origin ?? ''),
      listening: item.listening === true,
    }));

  return {
    installed: true,
    directory,
    config,
    state,
    gateway: {
      running,
      updatedAt: typeof state?.updatedAt === 'string' ? state.updatedAt : null,
      targetAvailable: typeof state?.targetAvailable === 'boolean' ? state.targetAvailable : null,
      targetPort: Number.isInteger(state?.targetPort) ? state.targetPort : null,
      provider: typeof config?.provider === 'string' ? config.provider : null,
      publicOrigin: typeof config?.publicOrigin === 'string' ? config.publicOrigin : null,
    },
    direct,
    entries,
    trustedHosts: Array.isArray(state?.trustedHosts) ? state.trustedHosts.map(String) : [],
  };
}

/**
 * 一条直连通道（IPv6 / 局域网）的展示状态。
 *
 * 三个状态要分开说，因为它们对应三种完全不同的用户动作：
 *   - configured=false → 去开这个开关（configure-direct.mjs --ipv6 或面板按钮）
 *   - enabled=true 但 listening=0 → 开了但没绑上（网卡没地址 / 端口被占），看 detail
 *   - listening>0    → 真的在听，可以扫码配对
 */
export function directChannel({ kind, label, direct, entries, addresses }) {
  const enabled = direct?.enabled === true && direct?.[kind] === true;
  const live = (entries ?? []).filter((item) => item.kind === kind && item.listening === true);
  const origin = live[0]?.origin ?? null;
  let phase = 'off';
  let detail = '未开启';
  if (enabled && live.length > 0) {
    phase = 'ready';
    detail = `已监听 ${live.length} 个地址`;
  } else if (enabled && live.length === 0) {
    phase = 'pending';
    detail = kind === 'ipv6'
      ? '已开启但本机没有可用的全局 IPv6（或网关未运行）'
      : '已开启但没有可用的局域网地址（或网关未运行）';
  } else if (kind === 'ipv6' && (addresses ?? []).length === 0) {
    detail = '本机没有公网 IPv6，这条路当前不可用';
  } else if (kind === 'lan') {
    detail = '未开启（同一 Wi-Fi 下最快，不耗隧道额度）';
  }
  return {
    id: kind,
    name: label,
    group: 'direct',
    configured: enabled,
    enabled,
    phase,
    listening: live.length,
    origin,
    addresses: live.map((item) => item.address),
    detail,
  };
}

/**
 * 拼通道清单（纯函数）。
 * @param tunnel - 隧道侧：{channel, autoTunnel, publicBaseUrl, relay, channels[]}
 *   （来自 channel-service.js 的 readStatus，形状保持不变）
 * @param directSnapshot - collectDirect 的返回值
 * @param addresses - globalIpv6Addresses 的返回值
 */
export function buildChannelOverview({ tunnel, directSnapshot, addresses = [] }) {
  const channels = [];

  // ---- 隧道通道（公网）------------------------------------------------------
  const tunnelChannel = tunnel?.channel ?? null;
  const tunnelCatalog = Array.isArray(tunnel?.channels) ? tunnel.channels : [];
  for (const item of tunnelCatalog) {
    const isCurrent = tunnelChannel === item.id;
    channels.push({
      id: item.id,
      name: item.name,
      group: 'public',
      configured: item.available === true,
      enabled: isCurrent,
      phase: isCurrent ? 'current' : item.available === true ? 'available' : 'unconfigured',
      origin: item.origin ?? null,
      detail: isCurrent
        ? '当前正在走这条'
        : item.available === true
          ? '已配置，可切换'
          : '未配置',
    });
  }

  // ---- 直连通道 -------------------------------------------------------------
  const direct = directSnapshot?.direct ?? { enabled: false, ipv6: false, lan: false };
  const entries = directSnapshot?.entries ?? [];
  channels.push(directChannel({ kind: 'ipv6', label: 'IPv6 直连', direct, entries, addresses }));
  channels.push(directChannel({ kind: 'lan', label: 'Wi-Fi 直连', direct, entries, addresses }));

  // ---- 组网通道（PC 侧不可见）-----------------------------------------------
  // Tailscale / WireGuard 由手机 App 内置，电脑侧没有任何可靠信号能证明它"现在通"：
  // 隧道接口在、但手机没连是常态。如实标注由手机端管理，不编造可用性。
  channels.push({
    id: 'mesh',
    name: '组网通道（Tailscale / WireGuard）',
    group: 'mesh',
    configured: null,
    enabled: null,
    phase: 'app-managed',
    origin: null,
    detail: '由手机 App 管理，电脑侧无法确认当前是否已连接',
  });

  return {
    channels,
    checks: {
      ipv6: {
        count: addresses.length,
        addresses: addresses.map((item) => item.address),
        public: hasPublicIpv6(addresses),
      },
      gateway: directSnapshot?.gateway ?? { running: false },
      direct: direct,
      installed: directSnapshot?.installed === true,
    },
  };
}