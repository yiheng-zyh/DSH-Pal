/**
 * 连接通道总览 / 更新检查 / 直连开关 的单测。
 *
 * 这些测试守的是「面板说的话必须和事实一致」：
 *   - 地址判据与网关同源（含 Teredo/WSL 接口黑名单），不能多算；
 *   - 网关状态必须按 updatedAt 判新鲜度，陈旧的 state.json 不能报 listening；
 *   - 直连开关是「完整期望集合」语义，且失败时不写坏文件；
 *   - 版本比较不能把 0.1.10 判成比 0.1.9 老。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {
  buildChannelOverview, collectDirect, directChannel, globalIpv6Addresses, helpForChannel,
} from '../lib/overview.js';
import { compareVersions, checkUpdate, parseChangelog } from '../lib/update.js';
import { applyUpdate } from '../lib/update-service.js';
import { setDirect } from '../lib/direct-service.js';

/** 造一个 os.networkInterfaces() 形状的假表。 */
function fakeInterfaces(entries) {
  return entries;
}

test('IPv6 体检：与网关同源，Teredo / ULA / 链路本地都不算', () => {
  const interfaces = fakeInterfaces({
    WLAN: [
      { address: '2001:250:3009:2:9422:2b41:7a40:d122', family: 'IPv6', internal: false },
      { address: 'fe80::1%12', family: 'IPv6', internal: false },
      { address: 'fd7a:115c:a1e0::53', family: 'IPv6', internal: false },
      { address: '::1', family: 'IPv6', internal: true },
    ],
    // 隧道伪接口与虚拟网卡必须整块跳过（direct.mjs 的黑名单）。
    'Teredo Tunneling Pseudo-Interface': [
      { address: '2001:0:14c9:d502:2857:ab1c:c503:7a65', family: 'IPv6', internal: false },
    ],
    Tailscale: [
      { address: 'fd7a:115c:a1e0::c029:d60e', family: 'IPv6', internal: false },
    ],
    'vEthernet (WSL)': [
      { address: '172.20.0.1', family: 'IPv4', internal: false },
    ],
  });
  const addresses = globalIpv6Addresses(interfaces);
  assert.deepEqual(addresses.map((item) => item.address), [
    '2001:250:3009:2:9422:2b41:7a40:d122',
  ]);
});

test('通道清单：隧道在前、直连居中、组网如实反映电脑侧准备情况', () => {
  const overview = buildChannelOverview({
    tunnel: {
      channel: 'domestic',
      channels: [
        { id: 'domestic', name: '国内 frp 隧道', available: true, origin: 'https://a.example:12451' },
        { id: 'cloudflare', name: 'Cloudflare 快速隧道', available: true, origin: null },
      ],
    },
    directSnapshot: {
      direct: { enabled: true, ipv6: true, lan: false },
      entries: [
        {
          kind: 'ipv6',
          address: '2001:250::1',
          port: 18772,
          origin: 'http://[2001:250::1]:18772',
          listening: true,
          valid: true,
          // 没有外部请求证据 → 只能是「外部未验证」，不能给绿色。
        },
      ],
    },
    addresses: [{ address: '2001:250::1' }],
    meshSnapshot: {
      tailscale: { installed: true, running: true, online: true, account: 'me@github', ips: ['100.64.0.1'] },
      wireguard: { installed: false, serving: false },
    },
  });
  assert.deepEqual(overview.channels.map((c) => c.id), ['domestic', 'cloudflare', 'ipv6', 'lan', 'mesh']);
  assert.equal(overview.channels[0].phase, 'current');
  assert.equal(overview.channels[2].phase, 'unknown', '本机监听但无外部证据时不能绿');
  assert.equal(overview.channels[2].external, 'unknown');
  assert.equal(overview.channels[3].phase, 'off');
  assert.equal(overview.channels[4].phase, 'ready');
  assert.match(overview.channels[4].detail, /me@github/, '组网通道要显示登录账号');
});

test('直连通道：收到过外部请求才算外部已验证（绿色）', () => {
  const verified = directChannel({
    kind: 'ipv6',
    label: 'IPv6 直连',
    direct: { enabled: true, ipv6: true, lan: false },
    entries: [
      {
        kind: 'ipv6',
        address: '2001:250::1',
        port: 18772,
        origin: 'http://[2001:250::1]:18772',
        listening: true,
        valid: true,
        lastExternalAt: new Date().toISOString(),
      },
    ],
    addresses: [{ address: '2001:250::1' }],
  });
  assert.equal(verified.phase, 'ready');
  assert.equal(verified.external, 'verified');
});

test('fe80 这类非公网地址即使被写进 state.json，也不会被当成链接', () => {
  const channel = directChannel({
    kind: 'ipv6',
    label: 'IPv6 直连',
    direct: { enabled: true, ipv6: true, lan: false },
    entries: [
      {
        kind: 'ipv6',
        address: 'fe80::1234:5678',
        port: 18772,
        origin: 'http://[fe80::1234:5678]:18772',
        listening: true,
        valid: false, // collectDirect 已经判过：链路本地不是公网地址
      },
    ],
    addresses: [{ address: '2001:250::1' }],
  });
  assert.equal(channel.phase, 'pending', '只有非法地址时等于没绑上');
  assert.equal(channel.origin, null, '绝不能让 fe80 变成可点链接');
  assert.match(channel.detail, /已忽略 1 个非公网地址/);
});

test('每条异常通道各有一个「怎么办」，且 IPv6 与局域网给的是各自的排查项', () => {
  const ipv6 = directChannel({
    kind: 'ipv6',
    label: 'IPv6 直连',
    direct: { enabled: true, ipv6: true, lan: false },
    entries: [
      {
        kind: 'ipv6',
        address: '2001:250::1',
        port: 18772,
        origin: 'http://[2001:250::1]:18772',
        listening: true,
        valid: true,
      },
    ],
    addresses: [{ address: '2001:250::1' }],
  });
  const ipv6Help = helpForChannel(ipv6);
  assert.ok(ipv6Help, '未验证时必须有怎么办');
  assert.match(ipv6Help.steps.join(' '), /路由器/);
  assert.match(ipv6Help.steps.join(' '), /IPv6 防火墙/);

  const lan = directChannel({
    kind: 'lan',
    label: 'Wi-Fi 直连',
    direct: { enabled: true, ipv6: false, lan: true },
    entries: [
      {
        kind: 'lan',
        address: '192.168.1.5',
        port: 18772,
        origin: 'http://192.168.1.5:18772',
        listening: true,
        valid: true,
      },
    ],
    addresses: [],
  });
  const lanHelp = helpForChannel(lan);
  assert.ok(lanHelp, '未验证时必须有怎么办');
  assert.match(lanHelp.steps.join(' '), /AP 隔离/, '局域网排查要说 AP 隔离');
  assert.doesNotMatch(lanHelp.steps.join(' '), /IPv6 防火墙/, '局域网不该提 IPv6 防火墙');

  // 正常通道不该多一个按钮打扰用户。
  const ready = { ...ipv6, phase: 'ready' };
  assert.equal(helpForChannel(ready), null);
});

test('组网通道没准备好时，给出缺什么就装什么的步骤', () => {
  const help = helpForChannel(
    { id: 'mesh', phase: 'pending' },
    { mesh: { tailscale: { installed: true, running: false }, wireguard: { installed: false, serving: false } } },
  );
  assert.ok(help);
  const text = help.steps.join(' ');
  assert.match(text, /Tailscale 装了但没登录/);
  assert.match(text, /没装 WireGuard/);
});

test('直连通道：开了但没绑上要说 pending，而不是谎报 ready', () => {
  const pending = directChannel({
    kind: 'ipv6',
    label: 'IPv6 直连',
    direct: { enabled: true, ipv6: true, lan: false },
    entries: [],
    addresses: [{ address: '2001:250::1' }],
  });
  assert.equal(pending.phase, 'pending');
  assert.equal(pending.listening, 0);

  const off = directChannel({
    kind: 'lan',
    label: 'Wi-Fi 直连',
    direct: { enabled: false, ipv6: false, lan: false },
    entries: [],
    addresses: [],
  });
  assert.equal(off.phase, 'off');
});

test('网关新鲜度：超过 20 秒没更新的 state.json 不能报 running', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-overview-'));
  await fs.writeFile(
    path.join(directory, 'config.json'),
    JSON.stringify({ version: 1, publicOrigin: 'https://example.com', gatewayPort: 18772, provider: 'external' }),
  );
  await fs.writeFile(
    path.join(directory, 'state.json'),
    JSON.stringify({
      updatedAt: new Date(Date.now() - 60000).toISOString(),
      targetAvailable: true,
      targetPort: 58023,
      direct: [{ kind: 'ipv6', address: '2001:250::1', port: 18772, listening: true, origin: 'http://[2001:250::1]:18772' }],
    }),
  );
  const snapshot = await collectDirect({ directory });
  assert.equal(snapshot.installed, true);
  assert.equal(snapshot.gateway.running, false, '陈旧的网关不能被当成运行中');
});

test('网关新鲜度：刚写过的 state.json 报 running', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-overview-'));
  await fs.writeFile(
    path.join(directory, 'config.json'),
    JSON.stringify({ version: 1, publicOrigin: 'https://example.com', gatewayPort: 18772, provider: 'external' }),
  );
  await fs.writeFile(
    path.join(directory, 'state.json'),
    JSON.stringify({ updatedAt: new Date().toISOString(), targetAvailable: true, targetPort: 58023, direct: [] }),
  );
  const snapshot = await collectDirect({ directory });
  assert.equal(snapshot.gateway.running, true);
});

test('直连开关：完整期望集合语义 + 全关时删段', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-direct-'));
  await fs.writeFile(
    path.join(directory, 'config.json'),
    JSON.stringify({ version: 1, publicOrigin: 'https://example.com', gatewayPort: 18772, provider: 'external' }),
  );

  const on = await setDirect({ ipv6: true, lan: false }, { directory });
  assert.equal(on.status, 200);
  const written = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.deepEqual(written.direct, { enabled: true, ipv6: true, lan: false });

  // 再开 lan、不写 ipv6：按完整期望集合，ipv6 应该被关掉，而不是叠加。
  await setDirect({ ipv6: false, lan: true }, { directory });
  const second = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.deepEqual(second.direct, { enabled: true, ipv6: false, lan: true });

  // 全关：direct 段应该消失，回到改动前的行为。
  const off = await setDirect({ ipv6: false, lan: false }, { directory });
  assert.equal(off.status, 200);
  const third = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.equal('direct' in third, false);
});

test('直连开关：没有 config.json 时报 no-config，不创建半成品', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-direct-'));
  const result = await setDirect({ ipv6: true }, { directory });
  assert.equal(result.status, 409);
  assert.equal(result.body.code, 'no-config');
  await assert.rejects(fs.access(path.join(directory, 'config.json')));
});

test('版本比较：分段数字比较，兼容 v 前缀与预发布后缀', () => {
  assert.equal(compareVersions('0.1.10', '0.1.9') > 0, true);
  assert.equal(compareVersions('v0.2.0', '0.1.99') > 0, true);
  assert.equal(compareVersions('0.1.0', '0.1.0'), 0);
  assert.equal(compareVersions('0.1.0-alpha.2', '0.1.0') < 0, true);
});

test('更新检查：第一个源成功就用它，且不把读失败当成旧版本', async () => {
  const calls = [];
  const fetchImpl = async (url) => {
    calls.push(url);
    return {
      ok: true,
      json: async () => ({ version: '0.2.0' }),
      text: async () => '## 0.2.0 - 2026-11-10\n\n### 新增\n\n- 新功能 A\n',
    };
  };
  const result = await checkUpdate({ fetchImpl, local: '0.1.0' });
  assert.equal(result.ok, true);
  assert.equal(result.latest, '0.2.0');
  assert.equal(result.updateAvailable, true);
  // 第一次是版本源，第二次是公告源；版本源成功就不该再去打 GitHub API。
  assert.equal(calls.length, 2, `calls=${calls.join(',')}`);
  assert.match(calls[0], /package\.json/);
  assert.match(calls[1], /CHANGELOG\.md/);
  assert.deepEqual(result.notes, ['【新增】', '· 新功能 A']);
});

test('更新公告：解析 CHANGELOG 里目标版本的段落，找不到就退回最新', () => {
  const markdown = [
    '# 更新记录',
    '',
    '## 0.2.0 - 2026-11-10',
    '',
    '### 新增',
    '- 新功能 A',
    '',
    '## 0.1.0 - 2026-11-05',
    '',
    '### 新增',
    '- 老功能 B',
  ].join('\n');
  assert.deepEqual(parseChangelog(markdown, '0.2.0'), ['【新增】', '· 新功能 A']);
  assert.deepEqual(parseChangelog(markdown, '9.9.9'), ['【新增】', '· 新功能 A'], '未知版本退回最新段落');
  assert.deepEqual(parseChangelog('', '0.1.0'), []);
});

test('应用更新：工作区干净时执行 git pull --ff-only 并如实报变化', async () => {
  const calls = [];
  let revCount = 0;
  const executeImpl = async (command, args) => {
    calls.push(args.join(' '));
    const full = args.join(' ');
    if (full.includes('remote get-url')) return { stdout: 'ssh://git@ssh.github.com:443/u/r.git\n' };
    if (full.includes('status --porcelain')) return { stdout: '' };
    if (full.includes('rev-parse')) {
      revCount += 1;
      return { stdout: revCount === 1 ? 'aaaa111\n' : 'bbbb222\n' };
    }
    if (full.includes('pull --ff-only')) return { stdout: 'Updating aaaa111..bbbb222\n' };
    return { stdout: '' };
  };
  const result = await applyUpdate({ executeImpl });
  assert.equal(result.status, 200);
  assert.equal(result.body.ok, true);
  assert.equal(result.body.changed, true);
  assert.match(result.body.note, /重启 DSH/);
  assert.ok(calls.some((c) => c.includes('pull --ff-only')));
});

test('应用更新：有未提交改动时拒绝，一个文件都不动', async () => {
  const executeImpl = async (command, args) => {
    const full = args.join(' ');
    if (full.includes('remote get-url')) return { stdout: 'ssh://git@ssh.github.com:443/u/r.git\n' };
    if (full.includes('status --porcelain')) return { stdout: ' M tools/pal-channel-plugin/lib/client.js\n' };
    throw new Error('不该走到这里：有改动时不能执行 pull');
  };
  const result = await applyUpdate({ executeImpl });
  assert.equal(result.status, 409);
  assert.equal(result.body.code, 'dirty-worktree');
  assert.ok(result.body.files.some((f) => f.includes('client.js')));
});

test('应用更新：本地开发仓没有 remote 时如实说明，不尝试拉取', async () => {
  const executeImpl = async (command, args) => {
    const full = args.join(' ');
    if (full.includes('remote get-url')) throw new Error('No such remote');
    throw new Error('不该走到这里');
  };
  const result = await applyUpdate({ executeImpl });
  assert.equal(result.status, 409);
  assert.equal(result.body.code, 'no-remote');
});

test('更新检查：所有源都失败时如实报失败，不编造 latest', async () => {
  const fetchImpl = async () => {
    throw new Error('network unreachable');
  };
  const result = await checkUpdate({ fetchImpl, local: '0.1.0' });
  assert.equal(result.ok, false);
  assert.equal(result.latest, undefined);
  assert.equal(result.local, '0.1.0');
  assert.match(result.error, /network unreachable/);
});