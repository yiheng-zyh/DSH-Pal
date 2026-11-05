/**
 * 连接通道总览 —— 「用户能不能看见自己的通道」这一层的数据源。
 *
 * 为什么需要这个模块：
 * 通道状态原先散在三处，且没有一处是"给用户看的"：
 *   1. 隧道通道（国内 frp / Cloudflare）在 remote-web-ui 的配置里，只有本插件读得懂；
 *   2. 直连通道（IPv6 / 局域网）在 `%LOCALAPPDATA%\DSHPal\remote-access\` 的
 *      config.json（开关）与 state.json（网关实测的监听结果）里，只有命令行工具读得懂；
 *   3. 组网通道（Tailscale / WireGuard）原先只写了"手机端管理" —— 但用户真正要
 *      知道的是「**电脑这边准备好了没有**」，这个电脑侧完全查得到（装没装、登录没、
 *      有没有隧道服务），不该让用户去手机上猜。
 * 用户（包括作者本人）因此只会得出一个结论："好像没配"。本模块把三处
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
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
// 必须与网关共用同一套地址判据（含 Teredo/WSL/Docker 等接口黑名单），
// 否则面板会算出「3 个公网地址」而网关只绑 2 个，用户会以为有地址没生效。
import { advertisableAddresses, isGlobalIPv6, isPrivateIPv4 } from '../../remote-access/direct.mjs';

const execute = promisify(execFile);

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

/** Tailscale 客户端的常见安装位置（Windows）。 */
const TAILSCALE_CANDIDATES = [
  'C:\\Program Files\\Tailscale\\tailscale.exe',
  'C:\\Program Files (x86)\\Tailscale\\tailscale.exe',
];

/** WireGuard 客户端的常见安装位置（Windows）。 */
const WIREGUARD_CANDIDATES = [
  'C:\\Program Files\\WireGuard\\wireguard.exe',
  'C:\\Program Files (x86)\\WireGuard\\wireguard.exe',
];

async function fileExists(file) {
  try {
    await fs.access(file);
    return true;
  } catch {
    return false;
  }
}

/**
 * 探测电脑侧的 Tailscale 准备情况 —— 用户要的是「登录账号 + 通道正不正常」。
 *
 * 用 `tailscale status --json` 而不是 `tailscale ip`：后者只给地址，
 * 分不清「没装」「装了没登录」「登录了但没连上」这三种完全不同的状态。
 */
export async function probeTailscale({
  executeImpl = execute,
  candidates = TAILSCALE_CANDIDATES,
  timeoutMs = 4000,
} = {}) {
  let exe = null;
  for (const candidate of candidates) {
    if (await fileExists(candidate)) {
      exe = candidate;
      break;
    }
  }
  if (!exe) {
    // 也可能装在别处但进了 PATH。
    try {
      const { stdout } = await executeImpl('tailscale', ['version'], {
        timeout: timeoutMs,
        windowsHide: true,
      });
      if (String(stdout ?? '').trim()) exe = 'tailscale';
    } catch {
      // 没装。
    }
  }
  if (!exe) {
    return { installed: false, running: false, detail: '电脑上没有安装 Tailscale' };
  }
  try {
    const { stdout } = await executeImpl(exe, ['status', '--json'], {
      timeout: timeoutMs,
      windowsHide: true,
      maxBuffer: 4 * 1024 * 1024,
    });
    const status = JSON.parse(stdout);
    const backendState = typeof status?.BackendState === 'string' ? status.BackendState : null;
    const running = backendState === 'Running';
    const online = status?.Self?.Online === true;
    const ips = Array.isArray(status?.Self?.TailscaleIPs) ? status.Self.TailscaleIPs.map(String) : [];
    const hostname = typeof status?.Self?.HostName === 'string' ? status.Self.HostName : null;
    // 登录账号：优先取 User 表里的 LoginName，退而取 tailnet 显示名。
    const users = status?.User && typeof status.User === 'object' ? Object.values(status.User) : [];
    const loginName = typeof users[0]?.LoginName === 'string' ? users[0].LoginName : null;
    const tailnetCap = status?.CapMap?.['https://tailscale.com/cap/tailnet-display-name'];
    const tailnet = Array.isArray(tailnetCap) && tailnetCap.length ? String(tailnetCap[0]) : null;
    const account = loginName || tailnet || null;
    return {
      installed: true,
      running,
      online,
      backendState,
      hostname,
      account,
      tailnet,
      ips,
      version: typeof status?.Version === 'string' ? status.Version : null,
      detail: running
        ? `账号 ${account ?? '未知'} · 在线${hostname ? `（${hostname}）` : ''}${ips[0] ? ` ${ips[0]}` : ''}`
        : `账号 ${account ?? '未登录'} · 未连上（状态 ${backendState ?? '未知'}）`,
    };
  } catch (error) {
    return {
      installed: true,
      running: false,
      detail: `Tailscale 已安装，但读不到运行状态（${String(error?.message ?? error).slice(0, 80)}）`,
    };
  }
}

/**
 * 探测电脑侧的 WireGuard 准备情况。
 *
 * 手机 App 的「内置 WireGuard」是**客户端**；它要连的是电脑上跑着的服务端。
 * 所以「电脑准备好了」= 有运行中的 `WireGuardTunnel$<名字>` 服务，光装客户端不算。
 * WireGuard 没有"登录账号"概念（靠公钥对），这里不编造账号字段。
 */
export async function probeWireGuard({
  executeImpl = execute,
  candidates = WIREGUARD_CANDIDATES,
  timeoutMs = 4000,
} = {}) {
  let installed = false;
  for (const candidate of candidates) {
    if (await fileExists(candidate)) {
      installed = true;
      break;
    }
  }
  if (!installed) {
    try {
      await executeImpl('wireguard', ['/help'], { timeout: timeoutMs, windowsHide: true });
      installed = true;
    } catch {
      // 没装。
    }
  }
  if (!installed) {
    return { installed: false, serving: false, tunnels: [], detail: '电脑上没有安装 WireGuard' };
  }
  let tunnels = [];
  try {
    const { stdout } = await executeImpl('sc.exe', ['query', 'type=', 'service', 'state=', 'all'], {
      timeout: timeoutMs,
      windowsHide: true,
      maxBuffer: 4 * 1024 * 1024,
    });
    tunnels = String(stdout ?? '')
      .split(/\r?\n/)
      .map((line) => line.match(/WireGuardTunnel\$([^\s]+)/)?.[1])
      .filter(Boolean);
  } catch {
    // 查不到服务列表就当没有隧道，下面如实说明。
  }
  const serving = tunnels.length > 0;
  return {
    installed: true,
    serving,
    tunnels,
    detail: serving
      ? `WireGuard 隧道运行中（${tunnels.join('、')}）`
      : '已安装 WireGuard，但电脑上没有运行中的隧道 —— 手机连不进来',
  };
}

/** 汇总电脑侧的组网通道准备情况（只查电脑，不查手机）。 */
export async function collectMesh({ executeImpl = execute } = {}) {
  const [tailscale, wireguard] = await Promise.all([
    probeTailscale({ executeImpl }),
    probeWireGuard({ executeImpl }),
  ]);
  return { tailscale, wireguard };
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
    .map((item) => {
      const kind = String(item.kind ?? '');
      const address = String(item.address ?? '');
      /**
       * 二次校验地址：state.json 可能是旧版本写的、也可能被手工改过。
       * `fe80::/fc00::/fd00::` 这类不是公网可达地址，绝不能当链接给用户
       * （用户明确提过：fe80 开头的地址显然不是公网，不能整成链接打进面板）。
       */
      const valid =
        kind === 'ipv6' ? isGlobalIPv6(address) : kind === 'lan' ? isPrivateIPv4(address) : false;
      return {
        kind,
        address,
        port: Number(item.port) || 0,
        origin: String(item.origin ?? ''),
        listening: item.listening === true,
        valid,
        // 网关记录的「外部来访」证据：只有非本机地址的请求才算，见 gateway.mjs。
        externalHits: Number(item.externalHits) || 0,
        lastExternalAt: typeof item.lastExternalAt === 'string' ? item.lastExternalAt : null,
      };
    });

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
 * 外部可达证据的有效期：超过这么久没有新的外部请求，就重新算「未验证」。
 * 半年前的证据不能当成"现在还能连"——网络会变，地址会变。
 */
const EXTERNAL_EVIDENCE_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * 一条直连通道（IPv6 / 局域网）的展示状态。
 *
 * 四种状态对应四种完全不同的用户动作：
 *   - off      → 去开这个开关
 *   - pending  → 开了但没绑上（网卡没地址 / 网关没跑），看 detail
 *   - unknown  → **本机绑上了，但还没证明外面能连进来**（黄色，不是绿色）
 *   - ready    → 本机绑上 + 确实收到过外部请求（绿色）
 *
 * 为什么 unknown 不能给绿色：用户在校园网/蜂窝网络下，入站常被运营商或校园边界
 * 阻断。这时候本机能绑监听、地址也是公网地址，但外面根本连不进来。早先只看
 * "已监听"就给绿色，用户会以为"能用"，实际连不上 —— 这是误导，必须修掉。
 */
export function directChannel({ kind, label, direct, entries, addresses, now = Date.now() }) {
  const enabled = direct?.enabled === true && direct?.[kind] === true;
  const candidates = (entries ?? []).filter((item) => item.kind === kind);
  // 地址不合法的条目一律不当链接：fe80::/fc00::/fd00:: 这类别的设备到不了。
  const rejected = candidates.filter((item) => item.valid === false);
  const live = candidates.filter((item) => item.listening === true && item.valid !== false);
  const origin = live[0]?.origin ?? null;

  const evidence =
    live
      .map((item) => item.lastExternalAt)
      .filter((value) => typeof value === 'string' && Number.isFinite(Date.parse(value)))
      .sort()
      .pop() ?? null;
  const evidenceFresh = evidence !== null && now - Date.parse(evidence) < EXTERNAL_EVIDENCE_MS;

  let phase = 'off';
  let detail = '未开启';
  if (enabled && live.length > 0) {
    if (evidenceFresh) {
      phase = 'ready';
      detail = `已监听 ${live.length} 个地址 · 外部已验证可连（${new Date(evidence).toLocaleDateString('zh-CN')} 收到过外部请求）`;
    } else {
      phase = 'unknown';
      detail = `已监听 ${live.length} 个地址 · 外部可达性未验证（手机还没通过这条入口连过；校园网/蜂窝网络常阻断入站）`;
    }
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
  if (rejected.length > 0) {
    detail += `；已忽略 ${rejected.length} 个非公网地址（${rejected
      .map((item) => item.address)
      .slice(0, 2)
      .join('、')}），链路本地/内网地址不能直连`;
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
    external: evidenceFresh ? 'verified' : 'unknown',
    lastExternalAt: evidence,
    detail,
  };
}

/**
 * 组网通道的展示状态。
 *
 * 用户要在这条通道上看到两件事，缺一不可：
 *   1. **登录的是哪个账号**（Tailscale 的 LoginName / tailnet）；
 *   2. **通道正不正常**（在跑且在线 = 正常；装了没登录 / 没隧道 = 没准备好）。
 * 手机是否已经连上，电脑侧确实看不到，所以这里只说电脑侧的事实，不编造连通性。
 */
export function meshChannel(mesh) {
  const ts = mesh?.tailscale ?? null;
  const wg = mesh?.wireguard ?? null;
  const tsReady = ts?.running === true;
  const wgReady = wg?.serving === true;

  if (tsReady || wgReady) {
    const parts = [];
    if (tsReady) parts.push(`Tailscale 正常（账号 ${ts?.account ?? '未知'}${ts?.online ? ' · 在线' : ''}）`);
    if (wgReady) parts.push('WireGuard 隧道运行中');
    return {
      id: 'mesh',
      name: '组网通道（Tailscale / WireGuard）',
      group: 'mesh',
      configured: true,
      enabled: true,
      phase: tsReady && ts?.online !== false ? 'ready' : 'pending',
      origin: tsReady ? (ts?.ips?.[0] ?? null) : null,
      account: ts?.account ?? null,
      detail: `电脑已就绪：${parts.join('；')}。手机用同一账号登录即可连上。`,
    };
  }

  if (ts?.installed === true || wg?.installed === true) {
    const parts = [ts?.detail, wg?.detail].filter((item) => typeof item === 'string' && item);
    return {
      id: 'mesh',
      name: '组网通道（Tailscale / WireGuard）',
      group: 'mesh',
      configured: false,
      enabled: false,
      phase: 'pending',
      origin: null,
      account: ts?.account ?? null,
      detail: parts.join('；') || '电脑已安装组网客户端，但尚未就绪',
    };
  }

  return {
    id: 'mesh',
    name: '组网通道（Tailscale / WireGuard）',
    group: 'mesh',
    configured: false,
    enabled: false,
    phase: 'off',
    origin: null,
    account: null,
    detail: '电脑上没有安装 Tailscale 或 WireGuard，手机无法通过组网通道连进来',
  };
}

/**
 * 拼通道清单（纯函数）。
 * @param tunnel - 隧道侧：{channel, autoTunnel, publicBaseUrl, relay, channels[]}
 *   （来自 channel-service.js 的 readStatus，形状保持不变）
 * @param directSnapshot - collectDirect 的返回值
 * @param addresses - globalIpv6Addresses 的返回值
 * @param meshSnapshot - collectMesh 的返回值（电脑侧组网准备情况）
 */
export function buildChannelOverview({ tunnel, directSnapshot, addresses = [], meshSnapshot = null }) {
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

  // ---- 组网通道（电脑侧准备情况）---------------------------------------------
  // 原先写死「手机端管理，电脑侧无法确认」—— 那句话是错的：电脑侧明明能查到
  // 装没装、登录哪个账号、隧道在不在跑。现在如实展示电脑侧的准备情况。
  channels.push(meshChannel(meshSnapshot));

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
      mesh: meshSnapshot ?? null,
    },
  };
}