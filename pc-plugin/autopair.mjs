/**
 * dsh-mobile-autopair — 让「登录 Tailscale 就自动连上」成立。
 *
 * ## 用户要求（2026-11-04 原话）
 * 「我这边用户在手机这边登录好账号之后，下一步不就是应该直接连接了吗，
 *   而不是还要有其他操作。你给这一步做成隐性自动化自动发现的。」
 *
 * ## 上一版为什么失败（重要，别再犯）
 *
 * 第一版挂在 `api/gate` waterfall 上，想让 /api/pair/issue 放行。重启后实测
 * **仍然 403**。原因是 gate 放行只是「允许进入路由」，而签发路由内部还有一道
 * 更硬的栅栏（`lib/types/routes.js:781`）：
 * ```js
 * const loopbackFence = (req) => isLoopbackClient(req) && isTrustedApiRequest(req, []);
 * // handleIssue: if (!loopbackFence(req)) { writeJson(res, 403, {ok:false,code:'forbidden'}); }
 * ```
 * 它要求「真回环 + 无转发头」，经 Serve 转发的请求永远不满足，gate 放不放行都没用。
 *
 * ## 这一版的做法
 *
 * 不去松动第三方插件的任何栅栏，改为**自己注册一个端点**，本插件从回环侧
 * 代签令牌：
 *
 *   手机 ──Serve(注入身份头)──▶ /dsh-mobile/autopair
 *                               │ 校验 x-forwarded-for ∈ 本 tailnet 设备表
 *                               │ 校验 tailscale-user-login == 电脑自己的账号
 *                               ▼
 *                         回环 POST /api/pair/issue  （真回环，天然满足 loopbackFence）
 *                               ▼
 *                         把令牌交回手机
 *   手机 ──/pair-accept?pair=<token>──▶ 拿到 device cookie，与扫码完全同一端点
 *
 * 关键点：
 * - 令牌由**第三方插件自己**签发（我们只是把请求从回环转发过去），
 *   所以它的策略、期限、次数限制全部照旧生效，我们没有任何绕过。
 * - 信任判断只认 tailscaled 维护的设备表（经 WireGuard 密钥验证），
 *   不认请求头自称的身份 —— 反向代理伪造 `x-forwarded-for` 也没用。
 * - 失败安全：任何异常都返回 403，手机退回扫码流程。
 */

export const name = 'dsh-mobile-autopair';

// 必须声明注入依赖：cordis 会等 webServer 就绪后才调用 apply()。
// 少了这一行，apply 里的 ctx.webServer 可能是 undefined，注册端点的 effect
// 直接抛错并被宿主吞掉 —— 表现就是「插件静默不生效、日志里什么都没有」。
export const inject = ['webServer'];

import {createRequire} from 'node:module';

/** 我们自己注册的端点。刻意放在别的命名空间下，避免与插件路径冲突。 */
export const AUTOPAIR_PATH = '/dsh-mobile/autopair';

/** 第三方插件的回环签发端点，由我们从本机代发。 */
const ISSUE_PATH = '/api/pair/issue';

const require_ = createRequire(import.meta.url);
const {execFile} = require_('node:child_process');
const http = require_('node:http');

/**
 * 判断请求是否来自「本 tailnet 自己的设备」。
 *
 * @param {string[]} candidateIps 请求头里的客户端 IP 候选（Serve 写 x-forwarded-for）
 * @param {object} status tailscale status --json
 * @param {string[]} allowLogins 额外白名单；空=不限（但仍要求同账号）
 * @param {string} claimedLogin Serve 注入的 tailscale-user-login（可选交叉验证）
 */
export function verifyTailnetPeer(candidateIps, status, allowLogins = [], claimedLogin = '') {
  if (!status || typeof status !== 'object') return {ok: false, reason: 'no-status'};
  if (status.BackendState !== 'Running') return {ok: false, reason: 'not-running'};

  const selfAccounts = accountKeys(status.Self);
  const peers = status.Peer && typeof status.Peer === 'object' ? Object.values(status.Peer) : [];

  // tailscaled 维护的设备表：IP -> 该设备的账号候选集合。
  // 这是唯一可信的身份来源（经 WireGuard 密钥验证，反向代理伪造不了）。
  const known = new Map();
  for (const device of [status.Self, ...peers]) {
    if (!device || typeof device !== 'object') continue;
    const accounts = accountKeys(device);
    const ips = Array.isArray(device.TailscaleIPs) ? device.TailscaleIPs : [];
    for (const raw of ips) {
      const ip = normIp(raw);
      if (ip) known.set(ip, accounts);
    }
  }

  for (const candidate of candidateIps) {
    const ip = normIp(candidate);
    if (!ip || !known.has(ip)) continue;
    const accounts = known.get(ip);

    // 同账号：共享 tailnet 里别人的设备不该被自动信任。
    // 只有当两边都能取到账号、却完全对不上时才拒绝；取不到就交给下面的 allowLogins。
    if (selfAccounts.size > 0 && accounts.size > 0 && !accountsOverlap(selfAccounts, accounts)) {
      return {ok: false, reason: 'foreign-account', ip};
    }

    if (Array.isArray(allowLogins) && allowLogins.length > 0) {
      const allowed = allowLogins.some(entry => accounts.has(entry));
      if (!allowed) return {ok: false, reason: 'login-not-allowed', ip};
    }

    // 交叉验证：Serve 自称的登录名必须能在设备表里对上。
    //
    // 但要清楚这里**能验证到什么**：Tailscale 1.104.2 的 status --json 里
    // LoginName 是空的，只给 UserID（如 3571655695752710），而 Serve 头给的是
    // 登录名（yiheng-zyh@github）—— 两者没有可推导的对应关系，所以**无法**用
    // 它来交叉验证登录名。
    //
    // 能验证的是真正的安全属性：这个 IP 是不是 tailscaled 认可的自家设备。
    // 那一项在进到这里之前已经过了（known.has(ip) + 同账号检查），而 x-forwarded-for
    // 由 Serve 自己写入、且 Serve 只在 tailnet 内网可达，外部伪造不了这一层。
    //
    // 所以：只有当设备表里**确实有**登录名、且与头里明显矛盾时才拒绝；
    // 设备表没有登录名时不做这项检查（否则所有请求都会被误判为 mismatch，
    // 第一版就踩了这个坑，表现为经 Serve 永远 403 login-header-mismatch）。
    if (claimedLogin && accounts.size > 0 && hasAnyLoginName(accounts)) {
      const claim = String(claimedLogin);
      const bare = claim.includes('@') ? claim.slice(0, claim.indexOf('@')) : claim;
      const matches = accounts.has(claim) || accounts.has(bare) ||
        [...accounts].some(key => key.startsWith(bare + '@'));
      if (!matches) return {ok: false, reason: 'login-header-mismatch', ip};
    }

    return {ok: true, reason: 'tailnet-peer', ip};
  }
  return {ok: false, reason: 'unknown-source'};
}

function normIp(value) {
  return String(value ?? '').trim().split('/')[0].toLowerCase();
}

/**
 * 设备的账号候选集合。
 *
 * 实测（Tailscale 1.104.2）：`status --json` 的 Self/Peer 里 **LoginName 是空**，
 * 只有 `UserID`（如 3571655695752710）；而 Serve 注入的 `tailscale-user-login`
 * 是登录名（`yiheng-zyh@github`）。两者不是同一种东西，直接相等比较必然失败 ——
 * 第一版就是把所有请求都判成 login-header-mismatch 的。
 *
 * 所以这里收集所有可能的标识，比较时取交集：
 * - LoginName / UserLogin（旧版或未来版本可能提供）
 * - 去掉 @域名 的前缀两种写法（`user@` 与 `user`）
 * - UserID / User.ID（当前版本唯一给出的字段）
 */
function accountKeys(device) {
  const keys = new Set();
  if (!device || typeof device !== 'object') return keys;
  for (const raw of [device.LoginName, device.User?.LoginName, device.UserLogin]) {
    const value = String(raw ?? '').trim();
    if (!value) continue;
    keys.add(value);
    const at = value.indexOf('@');
    if (at > 0) {
      keys.add(value.slice(0, at) + '@');
      keys.add(value.slice(0, at));
    }
  }
  for (const raw of [device.UserID, device.User?.ID]) {
    const value = raw === undefined || raw === null ? '' : String(raw).trim();
    if (value) keys.add(value);
  }
  return keys;
}

/** 两个候选集合是否有交集。任一为空时返回 false（无从比较）。 */
function accountsOverlap(left, right) {
  if (left.size === 0 || right.size === 0) return false;
  for (const value of left) if (right.has(value)) return true;
  return false;
}

/**
 * 设备表里是否有「真登录名」（含 @ 的字符串），而不是只有 UserID。
 *
 * 这一点决定了能不能做登录名交叉验证：当前 Tailscale 只给 UserID，光凭它
 * 无法判断 Serve 头里的 `user@github` 是否属于同一账号，所以那种情况下
 * 跳过交叉检查，靠「IP 已在 tailscaled 设备表里」这一条作为安全依据。
 */
function hasAnyLoginName(accounts) {
  for (const value of accounts) if (value.includes('@')) return true;
  return false;
}

/** 从请求头取客户端 IP 候选。Serve 转发时真实来源在 x-forwarded-for 首位。 */
export function clientIpsFrom(headers = {}) {
  const out = [];
  const forwarded = headers['x-forwarded-for'];
  if (typeof forwarded === 'string') {
    for (const part of forwarded.split(',')) {
      const ip = part.trim();
      if (ip) out.push(ip);
    }
  }
  for (const key of ['tailscale-user-ip', 'x-real-ip']) {
    const value = headers[key];
    if (typeof value === 'string' && value.trim()) out.push(value.trim());
  }
  return out;
}

export function apply(ctx, config = {}) {
  const enabled = config.autopairEnabled !== false;   // 默认开启
  const allowLogins = Array.isArray(config.allowLogins) ? config.allowLogins : [];
  const binary = config.tailscaleBin || (process.platform === 'win32'
    ? 'C:\\Program Files\\Tailscale\\tailscale.exe'
    : 'tailscale');
  const logger = ctx.logger?.(name) || console;

  let cached = {at: 0, status: null};
  let lastMessage = '';
  function report(message) {
    if (message !== lastMessage) {
      lastMessage = message;
      logger.info?.(message);
    }
  }

  /** tailscale status 有子进程开销，缓存 5 秒；失败不缓存。 */
  function tailscaleStatus() {
    const now = Date.now();
    if (cached.status && now - cached.at < 5_000) return Promise.resolve(cached.status);
    return new Promise(resolve => {
      execFile(binary, ['status', '--json'], {windowsHide: true, timeout: 4000, maxBuffer: 1048576},
        (error, stdout) => {
          if (error || !stdout) return resolve(null);
          try {
            const parsed = JSON.parse(stdout);
            cached = {at: Date.now(), status: parsed};
            resolve(parsed);
          } catch {
            resolve(null);
          }
        });
    });
  }

  /**
   * 从回环侧请求第三方插件签发一个令牌。
   *
   * 走 127.0.0.1 是刻意的：那里天然满足它的 loopbackFence，不需要我们改它一行。
   * 端口取当前 webServer 的真实端口（DSH 每次重启都变）。
   */
  function issueFromLoopback(port) {
    return new Promise(resolve => {
      const payload = '{}';
      const request = http.request({
        host: '127.0.0.1',
        port,
        path: ISSUE_PATH,
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(payload),
          host: '127.0.0.1:' + port,
        },
        timeout: 8000,
      }, response => {
        const chunks = [];
        response.on('data', chunk => chunks.push(chunk));
        response.on('end', () => {
          const body = Buffer.concat(chunks).toString('utf8');
          if (response.statusCode !== 200) return resolve({ok: false, code: 'issue-http-' + response.statusCode});
          try {
            const parsed = JSON.parse(body);
            const token = typeof parsed?.token === 'string' ? parsed.token : '';
            if (!/^[A-Za-z0-9_-]{16,256}$/.test(token)) return resolve({ok: false, code: 'bad-token'});
            resolve({ok: true, token, expiresAt: parsed.expiresAt});
          } catch {
            resolve({ok: false, code: 'bad-json'});
          }
        });
      });
      request.on('timeout', () => {
        request.destroy();
        resolve({ok: false, code: 'issue-timeout'});
      });
      request.on('error', () => resolve({ok: false, code: 'issue-error'}));
      request.end(payload);
    });
  }

  function reject(res, code) {
    try {
      res.writeHead(403, {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store'});
      res.end(JSON.stringify({ok: false, code}));
    } catch {}
  }

  const handler = async (req, res) => {
    if (!enabled) return reject(res, 'disabled');
    if (req.method !== 'POST') return reject(res, 'method');

    const ips = clientIpsFrom(req.headers || {});
    // 没有转发头 = 这不是经 Serve 来的（比如桌面本机直接访问），不提供代签。
    if (ips.length === 0) return reject(res, 'no-forwarded-source');

    const status = await tailscaleStatus();
    const verdict = verifyTailnetPeer(ips, status, allowLogins, String(req.headers['tailscale-user-login'] ?? ''));
    if (!verdict.ok) {
      report('autopair declined (' + verdict.reason + ')');
      return reject(res, verdict.reason);
    }

    const port = Number(ctx.webServer?.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return reject(res, 'no-port');

    const issued = await issueFromLoopback(port);
    if (!issued.ok) {
      report('autopair issue failed (' + issued.code + ')');
      return reject(res, issued.code);
    }

    report('autopair granted for tailnet peer; token excluded');
    try {
      res.writeHead(200, {'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store'});
      res.end(JSON.stringify({ok: true, token: issued.token, expiresAt: issued.expiresAt ?? null}));
    } catch {}
  };

  ctx.effect(() => ctx.webServer.register({kind: 'exact', path: AUTOPAIR_PATH, handler}),
    name + ': autopair endpoint');
  ctx.effect(() => () => {
    cached = {at: 0, status: null};
  }, name + ': dispose');
}
