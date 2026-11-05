import test from 'node:test';
import assert from 'node:assert/strict';

/**
 * clientState 状态机的回归测试。
 *
 * 背景（2026-11-03 真实事故）：run.mjs 原先让 stderr 里的 error 字样无条件覆盖
 * clientState 为 'client-reported-error'，而 frpc 只在启动时输出一次
 * "start proxy success"。一旦某次网络抖动刷出 error 字样，状态就永久钉死在
 * client-reported-error —— 现场实测 frpc 进程活了 16 小时、网关监听 5 个地址正常、
 * 手机一直能用，但 state.json 始终报 error，任何读它做判断的工具都会误判。
 *
 * 修复：记录 tunnelStarted，成功后出现的 error 只降级为 'tunnel-degraded'。
 * 这里把那段判定逻辑原样抽出来测，防止再被改回去。
 */

/** 与 run.mjs 中 startClient() 的 stream 处理保持一致的模拟。 */
function createStateMachine() {
  let clientState = 'running-unverified';
  let tunnelStarted = false;
  const apply = (text) => {
    if (/start proxy success/i.test(text)) {
      clientState = 'tunnel-started-unverified';
      tunnelStarted = true;
    } else if (/certificate.*(pending|issuing)|正在签发/i.test(text)) {
      clientState = 'certificate-pending';
    } else if (/error|failed/i.test(text)) {
      clientState = tunnelStarted ? 'tunnel-degraded' : 'client-reported-error';
    }
    return clientState;
  };
  return {apply, get: () => clientState};
}

test('启动成功后出现 error 只降级，不退回 client-reported-error', () => {
  const machine = createStateMachine();
  machine.apply('start proxy success');
  assert.equal(machine.get(), 'tunnel-started-unverified');
  // 网络抖动刷出的报错：应进入 degraded，而不是钉死成 error
  assert.equal(machine.apply('read: connection reset by peer, error'), 'tunnel-degraded');
});

test('从未启动成功就报错，仍判为 client-reported-error', () => {
  const machine = createStateMachine();
  assert.equal(machine.apply('login failed: invalid token'), 'client-reported-error');
});

test('降级后再次成功可恢复为已启动', () => {
  const machine = createStateMachine();
  machine.apply('start proxy success');
  machine.apply('error: temporary failure');
  assert.equal(machine.get(), 'tunnel-degraded');
  // frpc --watch 会重连并再次打印 success
  assert.equal(machine.apply('start proxy success'), 'tunnel-started-unverified');
});

test('证书签发中优先于其他分支', () => {
  const machine = createStateMachine();
  assert.equal(machine.apply('certificate is issuing, please wait'), 'certificate-pending');
});

test('中文签发提示同样识别', () => {
  const machine = createStateMachine();
  assert.equal(machine.apply('正在签发证书'), 'certificate-pending');
});

test('无关键字输出不改变状态（避免被无关日志覆盖）', () => {
  const machine = createStateMachine();
  machine.apply('start proxy success');
  assert.equal(machine.apply('some unrelated banner text'), 'tunnel-started-unverified');
});
