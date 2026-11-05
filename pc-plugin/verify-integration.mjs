/**
 * 集成验证：用真实的 tailscale status + 真实的回环 issue 端点，
 * 跑通 autopair handler 的完整链路。
 *
 * 目的：在「请用户重启 DSH」之前，证明代码逻辑本身没问题 ——
 * 避免又一次重启后发现还是不通。
 *
 * 用法：node verify-integration.mjs [DSH端口]
 */
import {apply} from './autopair.mjs';
import http from 'node:http';
import {execFileSync} from 'node:child_process';

const PORT = Number(process.argv[2] ?? 55167);

// 1. 装一个假 ctx，捕获注册的路由
const routes = [];
const logs = [];
const ctx = {
  logger: () => ({info: m => logs.push(m), warn: m => logs.push('WARN ' + m)}),
  webServer: {port: PORT, register: route => { routes.push(route); return () => {}; }},
  effect: fn => { const d = fn(); return typeof d === 'function' ? d : () => {}; },
  on: () => () => {},
};
apply(ctx, {autopairEnabled: true});

const handler = routes[0]?.handler;
if (typeof handler !== 'function') {
  console.log('FAIL: 没有注册 handler');
  process.exit(1);
}

// 2. 造一个假的 req/res 跑 handler
function run(headers) {
  return new Promise(resolve => {
    const res = {
      statusCode: null,
      body: '',
      writeHead(code) { this.statusCode = code; },
      end(chunk) { this.body = chunk ?? ''; resolve({status: this.statusCode, body: this.body}); },
    };
    handler({url: '/dsh-mobile/autopair', method: 'POST', headers}, res);
  });
}

// 3. 真实 tailscale 状态
const status = JSON.parse(
  execFileSync('C:\\Program Files\\Tailscale\\tailscale.exe', ['status', '--json'], {windowsHide: true}).toString()
);
const selfIp = String(status.Self.TailscaleIPs[0]).split('/')[0];
console.log('本机 Tailscale IP:', selfIp, ' BackendState:', status.BackendState);
console.log('DSH 端口:', PORT, '\n');

// 4. 先确认回环 issue 端点可用（这是插件内部的依赖）
function loopbackIssue() {
  return new Promise(resolve => {
    const body = '{}';
    const req = http.request({
      host: '127.0.0.1', port: PORT, path: '/api/pair/issue', method: 'POST',
      headers: {'content-type': 'application/json', 'content-length': Buffer.byteLength(body),
        host: '127.0.0.1:' + PORT},
      timeout: 6000,
    }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({code: res.statusCode, body: Buffer.concat(chunks).toString()}));
    });
    req.on('timeout', () => { req.destroy(); resolve({code: 'timeout'}); });
    req.on('error', e => resolve({code: 'error', body: e.message}));
    req.end(body);
  });
}

const results = [];
function record(label, pass, detail) {
  results.push({label, pass, detail});
  console.log(`  ${pass ? 'PASS' : 'FAIL'}  ${label}${detail ? '  (' + detail + ')' : ''}`);
}

(async () => {
  // 依赖检查
  const issue = await loopbackIssue();
  console.log('=== 依赖：回环 issue 端点 ===');
  console.log('  code =', issue.code);
  if (issue.code !== 200) {
    console.log('  -> 回环签发不可用，后续集成测试无意义');
    process.exit(1);
  }

  console.log('\n=== 完整链路 ===');
  // 本机 IP（Serve 转发时 x-forwarded-for 就是访客的 tailnet IP）
  const okRes = await run({'x-forwarded-for': selfIp, 'tailscale-user-login': 'yiheng-zyh@github'});
  const okParsed = (() => { try { return JSON.parse(okRes.body); } catch { return {}; } })();
  record('自家 tailnet 设备 → 200 且拿到令牌',
    okRes.status === 200 && typeof okParsed.token === 'string' && /^[A-Za-z0-9_-]{16,256}$/.test(okParsed.token),
    'status=' + okRes.status + ' token=' + (okParsed.token ? 'present(' + okParsed.token.length + ')' : 'missing'));

  // 陌生 IP
  const badRes = await run({'x-forwarded-for': '203.0.113.77'});
  record('陌生 IP → 403', badRes.status === 403, badRes.body);

  // 无转发头（真回环调用）
  const directRes = await run({});
  record('无转发头 → 403（不代签）', directRes.status === 403, directRes.body);

  // GET 方法
  const getRes = await new Promise(resolve => {
    const res = {statusCode: null, body: '',
      writeHead(code) { this.statusCode = code; },
      end(chunk) { this.body = chunk ?? ''; resolve({status: this.statusCode, body: this.body}); }};
    handler({url: '/dsh-mobile/autopair', method: 'GET', headers: {'x-forwarded-for': selfIp}}, res);
  });
  record('GET → 403（只接受 POST）', getRes.status === 403, getRes.body);

  console.log('\n=== 插件日志 ===');
  for (const line of logs) console.log('  ' + line);

  const failed = results.filter(r => !r.pass).length;
  console.log(`\n=== 结论：${results.length - failed}/${results.length} 通过 ===`);
  process.exit(failed === 0 ? 0 : 1);
})();
