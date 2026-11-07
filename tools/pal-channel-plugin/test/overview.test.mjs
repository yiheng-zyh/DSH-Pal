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
  installForChannel,
} from '../lib/overview.js';
import { compareVersions, checkUpdate, parseChangelog } from '../lib/update.js';
import { applyUpdate } from '../lib/update-service.js';
import { readCustomOrigin, setCustomOrigin } from '../lib/custom-origin-service.js';
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
  // 顺序：两条隧道 → 两条直连 → 自定义 HTTPS → 组网。
  // 自定义排在直连之后是有意的：它和隧道同类（公网域名），但属于「可选补充」，
  // 放在常用通道之后，不让它插在直连与组网中间打断阅读。
  assert.deepEqual(overview.channels.map((c) => c.id), ['domestic', 'cloudflare', 'ipv6', 'lan', 'custom', 'mesh']);
  assert.equal(overview.channels[4].phase, 'off', '没填自定义域名时是 off');
  assert.equal(overview.channels[4].editable, true, '自定义入口可编辑');
  assert.equal(overview.channels[0].phase, 'current');
  assert.equal(overview.channels[2].phase, 'unknown', '本机监听但无外部证据时不能绿');
  assert.equal(overview.channels[2].external, 'unknown');
  assert.equal(overview.channels[3].phase, 'off');
  assert.equal(overview.channels[5].phase, 'ready');
  assert.match(overview.channels[5].detail, /me@github/, '组网通道要显示登录账号');
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

test('每条通道都带「配置说明」，各条各有自己的安装流程', () => {
  const overview = buildChannelOverview({
    tunnel: {
      channel: 'domestic',
      channels: [
        { id: 'domestic', name: '国内 frp 隧道', available: true, origin: 'https://a.example:12451' },
        { id: 'cloudflare', name: 'Cloudflare 快速隧道', available: true, origin: null },
      ],
    },
    directSnapshot: { direct: { enabled: false, ipv6: false, lan: false }, entries: [] },
    addresses: [],
  });
  for (const channel of overview.channels) {
    assert.ok(channel.install, `${channel.id} 必须有配置说明`);
    assert.ok(Array.isArray(channel.install.steps) && channel.install.steps.length > 0);
    assert.match(channel.install.docUrl, /^https:\/\/github\.com\//);
  }
  const domestic = overview.channels.find((c) => c.id === 'domestic');
  assert.match(domestic.install.steps.join(' '), /实名/);
  assert.match(domestic.install.steps.join(' '), /save-sakura-token\.cmd/);
  assert.equal(installForChannel({ id: 'unknown-channel' }), null);
});

test('国内 frp 通道带「每天签到领流量」提醒，其它通道没有', () => {
  const overview = buildChannelOverview({
    tunnel: {
      channel: 'domestic',
      channels: [
        { id: 'domestic', name: '国内 frp 隧道', available: true, origin: 'https://a.example:12451' },
        { id: 'cloudflare', name: 'Cloudflare 快速隧道', available: true, origin: null },
      ],
    },
    directSnapshot: { direct: { enabled: false, ipv6: false, lan: false }, entries: [] },
    addresses: [],
  });
  const domestic = overview.channels.find((c) => c.id === 'domestic');
  assert.ok(domestic.notice, '国内 frp 必须有签到提醒');
  assert.match(domestic.notice.text, /签到/);
  assert.match(domestic.notice.url, /^https:\/\/www\.natfrp\.com\//);
  // 官方公开文档没写死签到额度与有效期，文案里也不许写死（避免误导）。
  assert.doesNotMatch(domestic.notice.title, /\d+\s*GB/i);
  assert.match(domestic.notice.title, /以官方签到页/);
  assert.equal(overview.channels.find((c) => c.id === 'cloudflare').notice, null);
});

test('版本比较：分段数字比较，兼容 v 前缀与预发布后缀', () => {
  assert.equal(compareVersions('0.1.10', '0.1.9') > 0, true);
  assert.equal(compareVersions('v0.2.0', '0.1.99') > 0, true);
  assert.equal(compareVersions('0.1.0', '0.1.0'), 0);
  assert.equal(compareVersions('0.1.0-alpha.2', '0.1.0') < 0, true);
});

test('版本比较：带前缀的版本号也要正确（否则更新判定静默失效）', () => {
  // 2026-11-07 实测发现的真 bug：旧实现按第一个 '-' 切分，
  // `pal-plugin-0.1.10` 的 core 变成 `pal`（解析成 0）、pre 变成 `plugin-0.1.10`，
  // 于是 0.1.10 被判成**比 0.1.9 更旧**。加前缀后更新判定会整体失效且不报错。
  assert.equal(compareVersions('pal-plugin-0.1.10', 'pal-plugin-0.1.9') > 0, true);
  assert.equal(compareVersions('pal-plugin-0.2.0', 'pal-plugin-0.1.99') > 0, true);
  assert.equal(compareVersions('pal-plugin-0.1.0', 'pal-plugin-0.1.0'), 0);
  // 混比：带前缀与不带前缀只要数字一样就是同一个版本（迁移期必须成立）。
  assert.equal(compareVersions('pal-plugin-0.1.0', '0.1.0'), 0);
  // 预发布语义不能被前缀破坏。
  assert.equal(compareVersions('pal-plugin-0.1.0-alpha.2', 'pal-plugin-0.1.0') < 0, true);
});

test('版本号校验正则必须接受带前缀的版本（否则所有版本源都判不合法）', async () => {
  const source = await fs.readFile(new URL('../lib/update.js', import.meta.url), 'utf8');
  const literal = /const VERSION_PATTERN = \/(.+)\/;/.exec(source);
  assert.ok(literal, '必须能定位到 VERSION_PATTERN');
  const pattern = new RegExp(literal[1]);
  assert.equal(pattern.test('pal-plugin-0.1.0'), true, '带前缀的版本号必须合法');
  assert.equal(pattern.test('0.1.0'), true, '普通版本号仍然合法');
  assert.equal(pattern.test('v1.2.3'), true);
  assert.equal(pattern.test('abc'), false, '非版本号仍要被拒绝');
  assert.equal(pattern.test('1.2'), false, '缺第三段仍要被拒绝');
});

test('插件版本号必须是合法 semver：pnpm 会直接拒绝带前缀的 version', async () => {
  // 实测：`"version": "pal-plugin-0.1.0"` 会得到
  //   [ERR_PNPM_BAD_PACKAGE_JSON] Invalid version: "pal-plugin-0.1.0"
  // 插件装不上 = 比版本号难看严重得多。所以「区分插件/App」只能靠版本段位、
  // name 与 displayName，不能用 version 字段加前缀。
  const manifest = JSON.parse(
    await fs.readFile(new URL('../package.json', import.meta.url), 'utf8'),
  );
  assert.match(
    manifest.version,
    /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/,
    'version 必须是标准 semver（前缀会让 pnpm 拒绝安装）',
  );
  // 与 App 的 0.1.x 区分开：插件的次版本号必须 >= 2。
  const [, minor] = manifest.version.split('.');
  assert.ok(Number(minor) >= 2, `插件版本 ${manifest.version} 要与 App 的 0.1.x 区分开`);
});

test('更新检查：并发打所有版本源，用冠军，公告跟着冠军的镜像走', async () => {
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
  // 三个镜像的 package.json 是并发打出去的（顺序降级会让不通的源卡满超时），
  // 之后只额外取一次 CHANGELOG，且必须与冠军同一个镜像前缀。
  const versionCalls = calls.filter((url) => url.endsWith('/package.json'));
  assert.equal(versionCalls.length, 3, `calls=${calls.join(',')}`);
  const changelogCalls = calls.filter((url) => url.endsWith('/CHANGELOG.md'));
  assert.equal(changelogCalls.length, 1, `calls=${calls.join(',')}`);
  assert.equal(
    changelogCalls[0].replace(/\/CHANGELOG\.md$/, ''),
    versionCalls[0].replace(/\/package\.json$/, ''),
    '公告必须来自冠军镜像，否则会出现「版本从 A 源拿、公告从 B 源拿」的半套更新',
  );
  assert.deepEqual(result.notes, ['【新增】', '· 新功能 A']);
});

test('更新检查：App 的 Release tag 不许当插件版本（否则永远误报有新版本）', async () => {
  const seen = [];
  // 造一个会立刻回 App 发布号的源：修复前它会被当成 latest，导致插件永远"可更新"。
  const fetchImpl = async (url) => {
    seen.push(url);
    if (url.includes('api.github.com')) {
      return { ok: true, json: async () => ({ tag_name: 'v0.1.1' }), text: async () => '' };
    }
    return {
      ok: true,
      json: async () => ({ version: '0.1.0' }),
      text: async () => '## 0.1.0 - 2026-11-05\n\n- x\n',
    };
  };
  const result = await checkUpdate({ fetchImpl, local: '0.1.0' });
  assert.equal(result.ok, true);
  assert.equal(result.latest, '0.1.0', 'latest 必须来自插件 package.json，不是 Release tag');
  assert.equal(result.updateAvailable, false);
  assert.equal(
    seen.some((url) => url.includes('api.github.com')),
    false,
    '插件版本判定不该再去问 GitHub Releases（那是 App 的版本号）',
  );
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

test('自定义 HTTPS 入口：填了自己的域名就用它，清除后回到 publicOrigin', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-custom-'));
  await fs.writeFile(
    path.join(directory, 'config.json'),
    JSON.stringify({ version: 1, publicOrigin: 'https://sakura.example:12451', gatewayPort: 18772, provider: 'external' }),
  );

  const saved = await setCustomOrigin({ origin: 'https://ds.example.com' }, { directory });
  assert.equal(saved.status, 200);
  const written = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.equal(written.customOrigin, 'https://ds.example.com');
  assert.equal(written.publicOrigin, 'https://sakura.example:12451', '不能覆盖 Sakura 那条地址');

  const read = await readCustomOrigin({ directory });
  assert.equal(read.effectiveOrigin, 'https://ds.example.com', '自定义优先作为发布地址');

  // 清除：回到 publicOrigin。
  const cleared = await setCustomOrigin({ origin: null }, { directory });
  assert.equal(cleared.status, 200);
  const after = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.equal('customOrigin' in after, false);
  const readAfter = await readCustomOrigin({ directory });
  assert.equal(readAfter.effectiveOrigin, 'https://sakura.example:12451');
});

test('自定义 HTTPS 入口：拒绝 http、IP 与自签证书类地址', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-custom-'));
  await fs.writeFile(
    path.join(directory, 'config.json'),
    JSON.stringify({ version: 1, publicOrigin: 'https://a.example', gatewayPort: 18772, provider: 'external' }),
  );
  for (const bad of ['http://ds.example.com', 'https://192.168.1.10', 'https://localhost', 'not-a-url']) {
    const result = await setCustomOrigin({ origin: bad }, { directory });
    assert.equal(result.status, 400, `${bad} 应被拒绝`);
  }
  const untouched = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
  assert.equal('customOrigin' in untouched, false, '校验失败不能留下半个字段');
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

test('应用更新：本地开发仓没有 remote 时不报错，而是按版本判定「无需更新」', async () => {
  const executeImpl = async (command, args) => {
    const full = args.join(' ');
    if (full.includes('remote get-url')) throw new Error('No such remote');
    throw new Error('不该走到这里：没有 remote 就不能尝试 pull');
  };
  // 本机真实装法：私有开发区没有 remote，本地 0.1.0 == 公开发布 0.1.0。
  const checkUpdateImpl = async ({ local }) => ({ ok: true, local, latest: local, updateAvailable: false });
  const result = await applyUpdate({ executeImpl, checkUpdateImpl });
  assert.equal(result.status, 200, '本地开发装法不是故障，不该回 409 红字');
  assert.equal(result.body.ok, true);
  assert.equal(result.body.changed, false);
  assert.equal(result.body.code, 'local-ahead');
  assert.match(result.body.note, /无需更新/);
});

test('应用更新：本地开发区比公开发布旧时，如实说「先同步公开仓」而不是假装更新成功', async () => {
  const executeImpl = async (command, args) => {
    const full = args.join(' ');
    if (full.includes('remote get-url')) return { stdout: '' };
    throw new Error('不该走到这里');
  };
  const checkUpdateImpl = async ({ local }) => ({ ok: true, local, latest: '9.9.9', updateAvailable: true });
  const result = await applyUpdate({ executeImpl, checkUpdateImpl });
  assert.equal(result.status, 200);
  assert.equal(result.body.changed, false, '绝不能假装更新成功');
  assert.equal(result.body.code, 'local-behind');
  assert.match(result.body.note, /同步公开仓/);
  assert.equal(result.body.latest, '9.9.9');
});

test('应用更新：没有 remote 且版本比对也失败时，说实话而不是编造结论', async () => {
  const executeImpl = async () => {
    throw new Error('No such remote');
  };
  const checkUpdateImpl = async () => ({ ok: false });
  const result = await applyUpdate({ executeImpl, checkUpdateImpl });
  assert.equal(result.status, 200);
  assert.equal(result.body.code, 'no-remote');
  assert.match(result.body.note, /未能比对/);
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