/**
 * 安全边界测试：本机桌面 ⇄ 远程配对设备。
 *
 * 为什么这件事值得单独一批用例：
 * `POST /api/pal-channel/switch` 会改写 DSH 配置、改变二维码指向的公网入口。
 * 它绝不能对远程配对设备开放 —— 而「远程」在这里特别难判，因为
 * `@linxin666/dsh-remote-web-ui` 的 `/remote` 通道会把远程请求重发到 127.0.0.1，
 * 附上进程内的认证凭据，于是 socket、Host、甚至 peer 身份全都看起来是本机。
 *
 * 本测试固定住两件事：
 *   1. 那条转发的**头特征**确实能被我们的判据识别（转发进来的请求被拒）；
 *   2. 真实浏览器/WebView 的正常请求不会被误伤（否则桌面按钮点不动）。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { isDesktopDirectRequest, isLocalStatusRequest } from '../lib/fence.js';

/**
 * 模拟真实桌面浏览器的请求头。
 * Chromium 在 127.0.0.1（potentially trustworthy 源）上 fetch 一定带 Fetch Metadata。
 */
const DESKTOP_FETCH = {
  host: '127.0.0.1:65264',
  'sec-fetch-mode': 'cors',
  'sec-fetch-site': 'same-origin',
  'sec-fetch-dest': 'empty',
  origin: 'http://127.0.0.1:65264',
  'content-type': 'application/json',
};

/**
 * DSH 桌面版（0.2.1-rc.5）Electron 壳内 fetch 的**真实**请求头 —— 2026-11-04 抓包所得。
 *
 * 注意它与普通浏览器不同：**既没有 origin 也没有 sec-fetch-site**，
 * 只有 sec-fetch-mode: cors + 回环 Host + content-type。
 * 这条用例是把当时「本机点按钮却被判成远程设备」的 bug 钉死，
 * 防止以后有人又把 sec-fetch-site 加成硬性要求。
 */
const DESKTOP_ELECTRON_SHELL = {
  host: '127.0.0.1:63619',
  'sec-fetch-mode': 'cors',
  'content-type': 'application/json',
  accept: 'application/json',
  'accept-encoding': 'gzip, deflate',
  'accept-language': '*',
  connection: 'keep-alive',
  'transfer-encoding': 'chunked',
};

/**
 * 模拟经 `@linxin666/dsh-remote-web-ui` 的 `/remote` 通道转发进来的请求。
 *
 * 依据该插件的 `forwardedRequestHeaders`：所有 `sec-` 前缀头被丢弃（只补一条
 * `sec-fetch-site: same-origin`），`origin` / `cookie` / 转发头全部在 denylist 内。
 */
const RELAYED_FROM_PHONE = {
  host: '127.0.0.1:65264',
  'sec-fetch-site': 'same-origin',
  'content-type': 'application/json',
};

test('本机桌面的正常 POST 允许', () => {
  assert.equal(isDesktopDirectRequest(DESKTOP_FETCH), true);
});

test('经 /remote 转发进来的请求被拒（缺 sec-fetch-mode）', () => {
  // 这是本插件最重要的安全断言：手机不能改电脑的对外入口。
  assert.equal(isDesktopDirectRequest(RELAYED_FROM_PHONE), false);
});

test('无 Origin 但 Host 回环 + same-origin 的 POST 放行（DSH 桌面壳）', () => {
  // 2026-11-04 真机修正：DSH 桌面版把 Web GUI 装在自己的 Electron 壳里，
  // 页面未必从 http://127.0.0.1 加载，于是 fetch 可能不带 Origin。
  // 判据放宽的依据：此时仍然要求 Host 回环 + 有 sec-fetch-mode +
  // sec-fetch-site=same-origin + 无转发头。
  // 而 `/remote` 转发会把所有 sec- 前缀的头剥掉（deny prefixes = ['sec-']），
  // 所以远程请求连第一关都过不了 —— 放宽 Origin 不削弱防护。
  const noOrigin = { ...DESKTOP_FETCH };
  delete noOrigin.origin;
  assert.equal(isDesktopDirectRequest(noOrigin), true);
});

test('无 Origin 且 Host 非回环的 POST 仍被拒', () => {
  // 放宽只针对 Origin，Host 回环这一关不能松：这是挡远程的主判据。
  const noOriginRemoteHost = { ...DESKTOP_FETCH, host: 'example.com:8000' };
  delete noOriginRemoteHost.origin;
  assert.equal(isDesktopDirectRequest(noOriginRemoteHost), false);
});

test('Origin 为自定义协议（dsh://）的 POST 放行（Electron 壳）', () => {
  const shellOrigin = { ...DESKTOP_FETCH, origin: 'dsh://host' };
  assert.equal(isDesktopDirectRequest(shellOrigin), true);
});

test('Origin 跨源的请求被拒', () => {
  const crossSite = { ...DESKTOP_FETCH, origin: 'https://evil.example.com' };
  assert.equal(isDesktopDirectRequest(crossSite), false);
});

test('sec-fetch-site 为 cross-site 的请求被拒', () => {
  const crossSite = { ...DESKTOP_FETCH, 'sec-fetch-site': 'cross-site' };
  assert.equal(isDesktopDirectRequest(crossSite), false);
});

test('带转发头的请求被拒（别的反代直接打进来的形态）', () => {
  const forwarded = { ...DESKTOP_FETCH, 'x-forwarded-for': '203.0.113.9' };
  assert.equal(isDesktopDirectRequest(forwarded), false);
  const cf = { ...DESKTOP_FETCH, 'cf-connecting-ip': '203.0.113.9' };
  assert.equal(isDesktopDirectRequest(cf), false);
});

test('Host 不是回环权威的请求被拒（经 frp/CF 用公网域名打进来）', () => {
  const publicHost = { ...DESKTOP_FETCH, host: 'frp.example.com:12451', origin: 'https://frp.example.com:12451' };
  assert.equal(isDesktopDirectRequest(publicHost), false);
});

test('只读状态接口：桌面 GET 允许（同源 GET 不带 Origin）', () => {
  const desktopGet = { host: '127.0.0.1:65264', 'sec-fetch-mode': 'cors', 'sec-fetch-site': 'same-origin' };
  assert.equal(isLocalStatusRequest(desktopGet), true);
});

test('只读状态接口：/remote 转发的 GET 被拒', () => {
  const relayedGet = { host: '127.0.0.1:65264', 'sec-fetch-site': 'same-origin' };
  assert.equal(isLocalStatusRequest(relayedGet), false);
});

test('localhost 与 [::1] 属于本机权威', () => {
  const localhost = { ...DESKTOP_FETCH, host: 'localhost:65264', origin: 'http://localhost:65264' };
  assert.equal(isDesktopDirectRequest(localhost), true);
  const v6 = { ...DESKTOP_FETCH, host: '[::1]:65264', origin: 'http://[::1]:65264' };
  assert.equal(isDesktopDirectRequest(v6), true);
});

test('127/8 内任意地址都算回环，其它 IPv4 不算', () => {
  const other = { ...DESKTOP_FETCH, host: '127.0.0.5:65264', origin: 'http://127.0.0.5:65264' };
  assert.equal(isDesktopDirectRequest(other), true);
  const lan = { ...DESKTOP_FETCH, host: '192.168.1.10:65264', origin: 'http://192.168.1.10:65264' };
  assert.equal(isDesktopDirectRequest(lan), false);
});

test('缺头 / 空对象一律拒绝（失败即关闭）', () => {
  assert.equal(isDesktopDirectRequest({}), false);
  assert.equal(isDesktopDirectRequest(undefined), false);
  assert.equal(isLocalStatusRequest({}), false);
});

test('DSH 桌面版壳内 fetch（无 origin / 无 sec-fetch-site）必须放行', () => {
  // 回归用例：2026-11-04 真实故障 —— 用户在自己电脑上点切换，
  // 却提示「只允许本机桌面操作」。抓包发现 DSH 桌面版（Electron 壳）
  // 发的请求既没有 origin 也没有 sec-fetch-site，而当时的判据硬性要求
  // sec-fetch-site === 'same-origin'，于是必然失败。
  assert.equal(isDesktopDirectRequest(DESKTOP_ELECTRON_SHELL), true);
  assert.equal(isLocalStatusRequest(DESKTOP_ELECTRON_SHELL), true);
});

test('桌面版壳内形态但 Host 非回环 → 仍拒绝', () => {
  // 放宽 origin/site 只对「回环 Host」生效，这条保证远程中转（Host 是公网域名）进不来。
  const remote = { ...DESKTOP_ELECTRON_SHELL, host: 'www.example.com:12451' };
  assert.equal(isDesktopDirectRequest(remote), false);
});

test('桌面版壳内形态但带转发头 → 仍拒绝', () => {
  const relayed = { ...DESKTOP_ELECTRON_SHELL, 'x-forwarded-for': '203.0.113.9' };
  assert.equal(isDesktopDirectRequest(relayed), false);
});

test('桌面版壳内形态但 sec-fetch-site=cross-site → 仍拒绝', () => {
  const cross = { ...DESKTOP_ELECTRON_SHELL, 'sec-fetch-site': 'cross-site' };
  assert.equal(isDesktopDirectRequest(cross), false);
});
