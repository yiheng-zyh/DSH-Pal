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
 * 「怎么办」—— 通道异常时给用户可执行的下一步。
 *
 * 排序原则：先给**他自己能做的**（手机自测），再给**要动路由器的**，
 * 最后才是"换通道"。不写"请检查防火墙"这种废话：要说清在哪、点什么、
 * 成功长什么样 —— 用户不是网络工程师。
 */
export function helpForChannel(channel, { mesh = null } = {}) {
  if (!channel) return null;
  if (['ready', 'current', 'available'].includes(channel.phase)) return null;

  if (channel.id === 'ipv6' || channel.id === 'lan') {
    const target = channel.origin
      ? `${channel.origin}/api/pair/status`
      : channel.id === 'ipv6'
        ? 'http://[电脑的IPv6地址]:18772/api/pair/status'
        : 'http://电脑局域网IP:18772/api/pair/status';
    if (channel.phase === 'pending') {
      return {
        title: '开了但没绑上？',
        steps: [
          '等 5 秒再刷新：网关每 5 秒热重载一次直连配置。',
          '看面板底部「网关」是否显示运行中；没运行就让电脑上的 remote-access 网关先跑起来。',
          channel.id === 'ipv6'
            ? '看面板底部「本机公网 IPv6」是否大于 0；为 0 说明当前网络没有公网 IPv6，这条路本来就不通。'
            : '在 Windows 上跑 `ipconfig`，确认本机有 192.168.x / 10.x 这类局域网地址。',
        ],
      };
    }
    // IPv6 与局域网是两条不同的路，排查点完全不同，不能共用一套文案。
    if (channel.id === 'ipv6') {
      return {
        title: '本机开着，但外面可能连不进来 —— 按这个顺序查',
        steps: [
          `先用手机自测：关掉 Wi-Fi、改用蜂窝数据，浏览器打开 ${target}。看到 JSON 就是通的（回电脑点一次配对，面板会转绿）。`,
          '打不开最常见的原因是路由器没放行：登录路由器后台 → 找「IPv6 防火墙 / 端口放行 / 通信规则」→ 允许这台电脑该端口的 TCP 入站。各家菜单名不同：小米叫「IPv6 防火墙」，华为在「IPv6」里，OpenWrt 在「防火墙 → 通信规则」。',
          '路由器已放行仍不通 → 校园网 / 企业网 / 部分运营商禁止入站，这条直连走不通，改用国内隧道或 Tailscale。',
          'Windows 防火墙：工具已做只读检查；若显示 no-node-allow-rule，需要手工放行该端口的 TCP 入站（IPv4 与 IPv6 都要）。',
        ],
      };
    }
    return {
      title: 'Wi-Fi 直连不通？按这个顺序查',
      steps: [
        `先确认手机和电脑连的是**同一个 Wi-Fi**，然后用手机浏览器打开 ${target}。能看到 JSON 就是通的。`,
        '打不开最常见的是「AP 隔离 / 无线隔离」：很多路由器默认开启，开着时同一 Wi-Fi 的设备互相看不见。登录路由器后台关掉它（有的叫「AP 隔离」「客户端隔离」「无线隔离」）。',
        'Windows 防火墙：工具已做只读检查；若显示 no-node-allow-rule，需要放行该端口的 TCP 入站。注意网络位置要是「专用网络」，被认成「公用网络」时入站规则不生效。',
        '换过网络后电脑的局域网 IP 会变：用面板上显示的地址重新生成二维码，不要用旧的。',
      ],
    };
  }

  if (channel.id === 'mesh') {
    const ts = mesh?.tailscale;
    const wg = mesh?.wireguard;
    const steps = [];
    if (!ts?.installed) steps.push('没装 Tailscale：去 tailscale.com 下载安装，登录后这台电脑就是组网节点。');
    else if (!ts?.running) steps.push('Tailscale 装了但没登录/没连上：打开托盘图标登录，状态变成 Running 就正常了。');
    if (!wg?.installed) steps.push('没装 WireGuard：手机 App 的「内置 WireGuard」是客户端，电脑这边需要服务端，只装客户端不够。');
    else if (!wg?.serving) steps.push('WireGuard 装了但没有运行中的隧道：需要部署服务端并把手机的对端配置导进去（可用 wg-easy 这类面板部署）。');
    if (steps.length === 0) return null;
    return { title: '组网通道没准备好？', steps };
  }

  return {
    title: '这条公网通道还没配',
    steps:
      channel.id === 'domestic'
        ? [
            '按 docs/connections.md 配国内 frp：注册 Sakura → 建隧道 → 保存访问密钥 → 生成 remote-access 配置。',
            '配好后回到这里刷新，状态会变成「可切换」。',
          ]
        : [
            'Cloudflare 快速隧道由远控插件自带，不用额外配置；切过去后用插件面板的二维码。',
            '切之前先确认手机已能用任一条通道连上，否则切过去可能找不到入口。',
          ],
  };
}

/**
 * 「配置说明」—— 每条通道**从零装到能用**的步骤。
 *
 * 为什么和「怎么办」分开：
 *   - 配置说明是**正向流程**（我还没装 / 我要从头配），正常状态下也要能看；
 *   - 「怎么办」是**异常排障**（配了但连不上）。
 * 两件事混在一起，刚上手的用户会先看到一堆报错排查，反而更懵。
 *
 * 面板里只放精简步骤；长文和图示在 docs/connections.md。
 */
export function installForChannel(channel) {
  const docUrl = 'https://github.com/yiheng-zyh/DSH-Pal/blob/main/docs/connections.md';
  switch (channel?.id) {
    case 'domestic':
      return {
        title: '国内 frp：从注册到能用',
        steps: [
          '注册并实名：打开 natfrp.com 注册账号，完成实名认证（免费用户也要实名）。',
          '创建隧道：管理面板 → 隧道 → 新建，记下「节点 ID」和「隧道 ID」。',
          '保存访问密钥：在项目目录双击 tools/remote-access/save-sakura-token.cmd，粘贴管理面板里的访问密钥。',
          '生成配置：node tools/remote-access/setup-sakura.mjs <隧道ID>，然后 node tools/remote-access/configure.mjs',
          '启动网关：node tools/remote-access/run.mjs（长期用就再跑 install-autostart.ps1 设成开机自启）。',
          '回到本页刷新，这条应变成「当前使用」；再用 node tools/remote-access/show-pair.mjs 出二维码给手机扫。',
        ],
        docUrl,
      };
    case 'cloudflare':
      return {
        title: 'Cloudflare 隧道：不用另外装',
        steps: [
          '这条由远控插件（dsh-remote-web-ui）自带，不需要单独安装任何东西。',
          '在上面切到 Cloudflare 后，用插件面板的二维码扫码即可。',
          'Quick 模式的地址每次重启都会变，变了就要重新配对；要固定地址需要用你自己域名的 Named 模式。',
        ],
        docUrl,
      };
    case 'ipv6':
      return {
        title: 'IPv6 直连：两端都要有 IPv6',
        steps: [
          '先确认两端都有公网 IPv6：电脑跑 curl -6 https://api6.ipify.org 有地址返回；手机在浏览器打开 test-ipv6.com 看 IPv6 一栏。',
          '电脑开启直连入口：node tools/remote-access/configure-direct.mjs --ipv6（默认是关闭的，要显式开）。',
          '路由器放行该端口的 TCP 入站（IPv6 不需要端口映射，但很多路由器默认开 IPv6 防火墙）。',
          '生成二维码：node tools/remote-access/show-pair.mjs --via ipv6，手机 App 选「IPv6 直连」扫码。',
          '不用时关掉：node tools/remote-access/configure-direct.mjs --off（公网地址等于多一个暴露面）。',
        ],
        docUrl,
      };
    case 'lan':
      return {
        title: 'Wi-Fi 直连：同一局域网最快',
        steps: [
          '让手机和电脑连同一个 Wi-Fi/路由器。',
          '电脑开启局域网入口：node tools/remote-access/configure-direct.mjs --lan',
          '生成二维码：node tools/remote-access/show-pair.mjs --via lan（多网卡时加 --address <手机所在网段的电脑IP>）。',
          '手机 App 选「Wi-Fi 直连」扫码；出门后这条不可用，属正常。',
        ],
        docUrl,
      };
    case 'mesh':
      return {
        title: '组网通道：电脑准备 + 手机登录',
        steps: [
          'Tailscale（推荐）：电脑去 tailscale.com 下载安装并登录；手机 App → 连接管理 → 内置通道 → Tailscale → 登录同一账号。',
          'WireGuard：手机 App 里的是客户端，电脑这边还要有服务端。可用 wg-easy 之类部署，再把手机的对端配置导进 App。',
          '两边的账号/配置必须是同一套；只装客户端、没登录，等于没有通道。',
        ],
        docUrl,
      };
    default:
      return null;
  }
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
      /**
       * 国内 frp 的免费流量需要**每天签到领取**。不签到就没有可用额度，
       * 表现是「隧道突然连不上」，用户很容易以为是软件坏了。
       *
       * 文案刻意不写死「每天 2GB」「一个月有效」这类数字：SakuraFrp 官方公开文档
       * 里没有写明签到流量的额度与有效期（查过文档站 sitemap 全量与账户/付款/基础
       * 相关页面），实际数值只在其用户中心的签到页动态显示。写死会误导用户，
       * 所以统一表述为「以官方签到页显示为准」。
       */
      notice:
        item.id === 'domestic'
          ? {
              text: '每天签到领流量',
              url: 'https://www.natfrp.com/user/',
              title:
                'SakuraFrp 的免费流量需要每天签到领取，不签到可能没有可用额度（隧道会突然连不上）。' +
                '每次签到给多少、能用多久，以官方签到页显示的当前额度与有效期为准。点这里打开官方签到页。',
            }
          : null,
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
    // 每条通道都带一个「怎么办」（异常时）和一个「配置说明」（从零装到能用）。
    channels: channels.map((channel) => ({
      ...channel,
      help: helpForChannel(channel, { mesh: meshSnapshot }),
      install: installForChannel(channel),
    })),
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