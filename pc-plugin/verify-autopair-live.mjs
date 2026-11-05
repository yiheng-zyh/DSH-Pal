/**
 * 端到端验证：autopair 的 api/gate listener 在真实 tailscale 状态下的行为。
 *
 * 重要语义（这个脚本第一版就搞错过）：
 * `api/gate` 是 waterfall —— listener 调 next() 表示「交给下游判断」，
 * **不是**「放行」。下游（dsh-remote-web-ui 的 gate）才会真正发 403。
 * 所以判据必须是「本插件有没有主动放行/主动否决」，不可能靠 next() 是否被调来判。
 *
 * 本脚本因此直接观察插件对外表现：给一个桩 next，看 promise 的返回值和是否抛错，
 * 并用 plugin 的日志（report）确认它走到了哪个分支。
 *
 * 运行：node verify-autopair-live.mjs
 */
import {apply, verifyTailnetPeer} from './autopair.mjs';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';

// ── 1. 真实 tailscale 状态 ────────────────────────────────────────────────
console.log('=== 读取本机真实 tailscale 状态 ===');
const status = JSON.parse(
  execFileSync('C:\\Program Files\\Tailscale\\tailscale.exe', ['status', '--json'], {windowsHide: true}).toString()
);
const selfIps = status.Self.TailscaleIPs.map(x => String(x).split('/')[0]);
const peers = Object.values(status.Peer || {});
const onlinePeers = peers.filter(p => p.Online).flatMap(p => p.TailscaleIPs.map(x => String(x).split('/')[0]));
console.log('  本机 IP:', selfIps.join(', '));
console.log('  在线 peer:', onlinePeers.join(', ') || '(无，手机 Tailscale 未连接)');
console.log('  BackendState:', status.BackendState);

// ── 2. 纯函数判据（这是真正的决策逻辑，必须独立验证）──────────────────────
console.log('\n=== 决策逻辑（verifyTailnetPeer）===');
const checks = [];
function check(label, fn) {
  try {
    fn();
    checks.push([label, true]);
    console.log('  PASS', label);
  } catch (error) {
    checks.push([label, false]);
    console.log('  FAIL', label, '->', error.message);
  }
}

check('自家设备放行', () => {
  assert.equal(verifyTailnetPeer([selfIps[0]], status).ok, true);
});
check('陌生 IP 拒绝（这是安全边界）', () => {
  const v = verifyTailnetPeer(['203.0.113.77'], status);
  assert.equal(v.ok, false);
  assert.equal(v.reason, 'unknown-source');
});
check('Tailscale 未运行/无状态时拒绝', () => {
  assert.equal(verifyTailnetPeer([selfIps[0]], null).ok, false);
});
if (onlinePeers.length > 0) {
  check('在线 peer 放行', () => {
    assert.equal(verifyTailnetPeer([onlinePeers[0]], status).ok, true);
  });
}

// ── 3. listener 接线是否正确（能否被 api/gate 挂上、是否只对 issue 生效）──
console.log('\n=== listener 接线 ===');
const handlers = [];
const logs = [];
apply(
  {
    logger: () => ({info: m => logs.push(m)}),
    effect: fn => {
      fn();
      return () => {};
    },
    on: (event, handler) => {
      if (event === 'api/gate') handlers.push(handler);
      return () => {};
    },
  },
  {autopairEnabled: true}
);
check('注册了一个 api/gate handler', () => {
  assert.equal(handlers.length, 1);
});
const gate = handlers[0];

/** 记录插件是否主动放行（调用了 next）。 */
async function gateCalls(headers, url = '/api/pair/issue') {
  logs.length = 0;
  let nextCalls = 0;
  const result = await gate({url, headers, method: 'POST'}, 'POST', () => {
    nextCalls++;
    return 'delegated';
  });
  return {result, nextCalls, logs: [...logs]};
}

const selfCall = await gateCalls({'x-forwarded-for': selfIps[0]});
check('自家设备：交给下游（下游签发逻辑会放行）', () => {
  assert.equal(selfCall.nextCalls, 1);
  assert.ok(selfCall.logs.some(m => m.includes('granted')));
});

const unknownCall = await gateCalls({'x-forwarded-for': '203.0.113.77'});
check('陌生 IP：交给下游但明确记为 declined（下游会 403）', () => {
  assert.equal(unknownCall.nextCalls, 1);
  assert.ok(unknownCall.logs.some(m => m.includes('declined')));
});

const otherPath = await gateCalls({'x-forwarded-for': selfIps[0]}, '/api/session/list');
check('非 issue 路径：不介入', () => {
  assert.equal(otherPath.nextCalls, 1);
  assert.equal(otherPath.logs.length, 0);
});

const loopback = await gateCalls({});
check('无转发头（真回环）：不介入', () => {
  assert.equal(loopback.nextCalls, 1);
  assert.equal(loopback.logs.length, 0);
});

// ── 4. 结论 ──────────────────────────────────────────────────────────────
console.log('\n=== 结论 ===');
const failed = checks.filter(([, pass]) => !pass);
console.log(`  ${checks.length - failed.length}/${checks.length} 通过`);
process.exit(failed.length === 0 ? 0 : 1);
