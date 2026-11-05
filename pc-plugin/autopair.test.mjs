/**
 * autopair 纯函数单测。运行：node --test autopair.test.mjs
 *
 * 注意 status 的形状是用**真实数据**校准过的：Tailscale 1.104.2 的
 * status --json 里 LoginName 是空的，只给 UserID。测试同时覆盖「有登录名」
 * （旧版/未来版）和「只有 UserID」（当前版）两种形状。
 */
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {verifyTailnetPeer, clientIpsFrom} from './autopair.mjs';

const SELF_IP = '100.78.214.13';
const PHONE_IP = '100.106.89.22';
const OTHER_IP = '100.70.68.104';

// 当前版本的真实形状：只有 UserID，没有 LoginName。
const currentShape = {
  BackendState: 'Running',
  Self: {HostName: 'Naily', UserID: 3571655695752710,
    TailscaleIPs: [SELF_IP, 'fd7a:115c:a1e0::c029:d60e']},
  Peer: {
    a1: {HostName: 'redmi-k70-ultra-1', UserID: 3571655695752710, Online: true,
      TailscaleIPs: [PHONE_IP, 'fd7a:115c:a1e0::2429:5917']},
    b2: {HostName: 'xiaomi-pad', UserID: 9999999999999999, Online: false,
      TailscaleIPs: [OTHER_IP]},
  },
};

// 旧版/未来版的形状：带 LoginName。
const withLoginShape = {
  BackendState: 'Running',
  Self: {HostName: 'naily', LoginName: 'yiheng-zyh@github', TailscaleIPs: [SELF_IP]},
  Peer: {
    a1: {HostName: 'redmi', LoginName: 'yiheng-zyh@github', TailscaleIPs: [PHONE_IP]},
    b2: {HostName: 'pad', LoginName: 'someone-else@github', TailscaleIPs: [OTHER_IP]},
  },
};

// ── 当前版本形状（只有 UserID）────────────────────────────────────────────
test('当前版形状：自家设备 → 放行', () => {
  const v = verifyTailnetPeer([PHONE_IP], currentShape);
  assert.equal(v.ok, true);
  assert.equal(v.reason, 'tailnet-peer');
});

test('当前版形状：本机 IP 也放行', () => {
  assert.equal(verifyTailnetPeer([SELF_IP], currentShape).ok, true);
});

test('当前版形状：IPv6 tailnet 地址也认', () => {
  assert.equal(verifyTailnetPeer(['fd7a:115c:a1e0::2429:5917'], currentShape).ok, true);
});

test('当前版形状：陌生 IP → 拒绝（核心安全边界）', () => {
  const v = verifyTailnetPeer(['203.0.113.77'], currentShape);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'unknown-source');
});

test('当前版形状：不同 UserID 的设备 → 拒绝', () => {
  const v = verifyTailnetPeer([OTHER_IP], currentShape);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'foreign-account');
});

test('当前版形状：Serve 登录名无法交叉验证时不误杀', () => {
  // 这是第一版的实际故障：设备表只有 UserID，头里有登录名，
  // 直接比较必然不等 → 所有经 Serve 的请求 403。必须放行。
  assert.equal(verifyTailnetPeer([PHONE_IP], currentShape, [], 'yiheng-zyh@github').ok, true);
});

// ── 带 LoginName 的形状 ──────────────────────────────────────────────────
test('带登录名：同账号放行', () => {
  assert.equal(verifyTailnetPeer([PHONE_IP], withLoginShape).ok, true);
});

test('带登录名：异账号拒绝', () => {
  const v = verifyTailnetPeer([OTHER_IP], withLoginShape);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'foreign-account');
});

test('带登录名：Serve 头登录名一致时放行', () => {
  assert.equal(verifyTailnetPeer([PHONE_IP], withLoginShape, [], 'yiheng-zyh@github').ok, true);
});

test('带登录名：Serve 头明显不符 → 拒绝', () => {
  const v = verifyTailnetPeer([PHONE_IP], withLoginShape, [], 'attacker@evil.com');
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'login-header-mismatch');
});

test('带登录名：前缀写法也认（user@ ↔ user@github）', () => {
  assert.equal(verifyTailnetPeer([PHONE_IP], withLoginShape, [], 'yiheng-zyh@').ok, true);
  assert.equal(verifyTailnetPeer([PHONE_IP], withLoginShape, [], 'yiheng-zyh').ok, true);
});

// ── 失败安全 ─────────────────────────────────────────────────────────────
test('Tailscale 未运行 → 拒绝', () => {
  const stopped = {...currentShape, BackendState: 'NeedsLogin'};
  const v = verifyTailnetPeer([PHONE_IP], stopped);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'not-running');
});

test('status 缺失 → 拒绝', () => {
  assert.equal(verifyTailnetPeer([PHONE_IP], null).ok, false);
});

test('空候选列表 → 拒绝', () => {
  assert.equal(verifyTailnetPeer([], currentShape).ok, false);
});

test('allowLogins 白名单：命中放行', () => {
  assert.equal(verifyTailnetPeer([PHONE_IP], withLoginShape, ['yiheng-zyh@github']).ok, true);
});

test('allowLogins 白名单：未命中拒绝', () => {
  const v = verifyTailnetPeer([PHONE_IP], withLoginShape, ['nobody@github']);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'login-not-allowed');
});

// ── 请求头解析 ───────────────────────────────────────────────────────────
test('clientIpsFrom：x-forwarded-for 支持多段', () => {
  assert.deepEqual(clientIpsFrom({'x-forwarded-for': '100.106.89.22, 10.0.0.1'}),
    ['100.106.89.22', '10.0.0.1']);
});

test('clientIpsFrom：无转发头返回空（回环直连不该走代签）', () => {
  assert.deepEqual(clientIpsFrom({}), []);
  assert.deepEqual(clientIpsFrom({host: '127.0.0.1:55167'}), []);
});

test('clientIpsFrom：兼容 x-real-ip / tailscale-user-ip', () => {
  assert.deepEqual(clientIpsFrom({'x-real-ip': '100.1.2.3'}), ['100.1.2.3']);
  assert.deepEqual(clientIpsFrom({'tailscale-user-ip': '100.4.5.6'}), ['100.4.5.6']);
});
