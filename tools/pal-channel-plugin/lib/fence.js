/**
 * 「谁在调用」判定 —— 本机桌面直连 ⇄ 远程配对设备。
 *
 * 为什么需要这一层（安全边界，不是洁癖）：
 * `POST /api/pal-channel/switch` 会改写 DSH 配置，改变 App 二维码指向的公网入口。
 * 一旦被配对手机调到，就等于「手机能改电脑的对外入口」—— 那不是我们要给的权限。
 *
 * 难点：`@linxin666/dsh-remote-web-ui` 的 `/remote` 通道会把远程请求**重发到
 * 127.0.0.1**，并附上进程内的浏览器认证凭据。于是 Host 围栏、浏览器认证、
 * 甚至 `peer.principal.kind === 'owner'` 全部照样通过 —— 在内层处理器眼里，
 * 远程请求与本机桌面请求无法靠 socket / Host / 身份区分。
 *
 * 但它转发时有**固定且可依赖**的副作用（源码位置：该插件的
 * `REQUEST_HEADER_DENYLIST` / `REQUEST_HEADER_DENY_PREFIXES` / `forwardedRequestHeaders`）：
 *
 *   1. 所有 `sec-` 前缀的头被丢弃，_只_人工补一条 `sec-fetch-site: same-origin`。
 *      ⇒ 转发进来的请求**不可能带 `sec-fetch-mode`**。
 *        而 Chromium / Android WebView 对 127.0.0.1、localhost 这类
 *        potentially-trustworthy 源上的 fetch **一定**带 Fetch Metadata。
 *   2. `origin`、`cookie`、`x-forwarded-*`、`cf-connecting-ip`、`forwarded`、`x-real-ip`
 *      都在 denylist 里。
 *      ⇒ 转发进来的 POST **不可能带 Origin**（同源 POST fetch 一定带）；
 *        任何真中转（frp / Cloudflare / nginx 直接打进来）会留下转发头。
 *
 * 因此判据只用请求头，不依赖 socket：
 *   GET  → 带 sec-fetch-mode，且无转发头
 *   POST → 以上 + sec-fetch-site=same-origin + Origin 与本请求 Host 同源
 *
 * 残余风险（如实写明，不粉饰）：本判据依赖上游 `/remote` 的头处理策略。
 * 若将来上游不再剥离 `sec-*` / `origin`，这层会被削弱。所以写路径上还有三道
 * 独立保险：通道名白名单、写入后从磁盘读回校验、失败回滚。
 *
 * @module dsh-pal-channel/fence
 */

/** 127/8 的 IPv4 回环字面量。 */
function isIPv4Loopback(value) {
  const parts = value.split('.');
  return parts.length === 4 && parts[0] === '127'
    && parts.every((part) => /^\d{1,3}$/.test(part) && Number(part) <= 255);
}

/** URL hostname 是否为本机权威（localhost / [::1] / 127/8）。 */
export function isLoopbackHostname(hostname) {
  if (hostname === 'localhost' || hostname === '[::1]') return true;
  return isIPv4Loopback(hostname);
}

/** 取 Host 并解析 hostname；解析失败返回 undefined。 */
export function hostnameOf(headers) {
  const host = headers?.host;
  if (typeof host !== 'string' || host === '') return undefined;
  try {
    return new URL(`http://${host}`).hostname;
  } catch {
    return undefined;
  }
}

/** 是否带「中间有代理」的痕迹（任何一跳转发都会留下其中之一）。 */
export function hasForwardingMarks(headers) {
  if (headers === undefined || headers === null) return false;
  for (const name of ['cf-connecting-ip', 'x-forwarded-for', 'x-forwarded-host', 'x-forwarded-proto', 'x-real-ip', 'forwarded']) {
    if (headers[name] !== undefined) return true;
  }
  return false;
}

/** Origin 是否与本请求的 Host 同源（按 Origin 自己的 http/https 方案比较，默认端口归一）。 */
export function isSameAuthorityOrigin(origin, host) {
  let originUrl;
  try {
    originUrl = new URL(origin);
  } catch {
    return false;
  }
  const scheme = originUrl.protocol === 'https:' ? 'https:' : originUrl.protocol === 'http:' ? 'http:' : undefined;
  if (scheme === undefined) return false;
  try {
    return originUrl.host === new URL(`${scheme}//${host}`).host;
  } catch {
    return false;
  }
}

/**
 * 只读接口的准入。
 * 为什么 GET 不额外要求 Origin：同源 GET fetch 浏览器**不带** Origin，
 * 要求了会把正常的桌面页面读状态一起挡掉。而 `/remote` 重发恰好把
 * `sec-fetch-mode` 剥掉了，只看这一条就能把远程请求剔除。
 * @param headers - 小写化的请求头对象。
 */
export function isLocalStatusRequest(headers) {
  if (headers === undefined || headers === null) return false;
  if (hasForwardingMarks(headers)) return false;
  const mode = headers['sec-fetch-mode'];
  if (typeof mode !== 'string' || mode === '') return false;
  // Host 必须是回环权威：桌面页面只从 127.0.0.1 / localhost 打开本接口。
  const hostname = hostnameOf(headers);
  return hostname !== undefined && isLoopbackHostname(hostname);
}

/**
 * 写接口的准入：必须是本机桌面直连（判据见文件头）。
 *
 * 2026-11-04 真机修正：DSH **桌面版**把 Web GUI 装在自己的 Electron 壳里，
 * 页面未必从 `http://127.0.0.1:port` 加载（可能是自定义协议或壳内 origin）。
 * 于是 `origin` 可能是 `dsh://…` / `file://…` 或干脆没有 —— 旧实现要求
 * 「Origin 必须是 http(s) 且与本请求 Host 同源」，在本机桌面上反而必然失败，
 * 表现就是「明明在电脑上点，却提示只允许本机桌面操作」。
 *
 * 现在的判据（按证据强度排序，任一成立即可）：
 *   a) Origin 是 http(s) 且与本请求 Host 同源        —— 普通浏览器直连
 *   b) Origin 是自定义/壳协议（非 http(s)）           —— Electron 壳
 *   c) 没有 Origin，但 sec-fetch-site=same-origin 且 Host 回环 —— 同源 fetch 的少数形态
 *
 * 这些条件都建立在 `isLocalStatusRequest` 已经通过的前提上 —— 也就是：
 * Host 必须是 127.0.0.1 / localhost / [::1]、必须带 sec-fetch-mode、必须没有转发头。
 * 而经 `/remote` 转发进来的请求**必然**被剥掉 `sec-fetch-mode` 且 Host 不是回环，
 * 所以放宽 Origin 检查不会让远程设备获得写权限。
 *
 * @param headers - 小写化的请求头对象。
 * @returns true 仅当这是本机浏览器 / WebView 直接发出的请求。
 */
export function isDesktopDirectRequest(headers) {
  // 第一关不可放宽：Host 回环 + 有 sec-fetch-mode + 无转发头。
  // 这一关就足以把 /remote 转发来的请求挡在外面（它一定缺 sec-fetch-mode）。
  if (!isLocalStatusRequest(headers)) return false;

  // sec-fetch-site 允许缺失。2026-11-04 实测 DSH 桌面版（0.2.1-rc.5）的
  // 真实请求头里**既没有 origin 也没有 sec-fetch-site**，只有
  // sec-fetch-mode: cors + host: 127.0.0.1:port + content-type: application/json。
  // 这是 Electron 壳内 fetch 的形态：壳自己发的请求不会带站点元数据。
  // 若存在该头，则必须是 same-origin（cross-site 仍拒）。
  const site = headers['sec-fetch-site'];
  if (typeof site === 'string' && site !== '' && site !== 'same-origin') return false;

  const origin = headers.origin;
  // a) 常规浏览器：Origin 是 http(s) 且同源。
  if (typeof origin === 'string' && origin !== '') {
    if (isSameAuthorityOrigin(origin, headers.host)) return true;
    // b) Origin 不是 http(s)：DSH 桌面壳的自定义协议。
    //    只接受非 http(s) 方案，避免把「http://evil.com」这类跨源请求放进来。
    if (!/^https?:/i.test(origin)) return true;
    return false;
  }

  // c) 完全没有 Origin：DSH 桌面壳内的 fetch 就长这样。
  //    此时的安全性完全由第一关保证：Host 必须回环、必须有 sec-fetch-mode、
  //    必须没有转发头。而 /remote 转发会剥掉全部 sec- 头（deny prefix ['sec-']），
  //    所以远程请求连第一关都过不了。
  return true;
}
