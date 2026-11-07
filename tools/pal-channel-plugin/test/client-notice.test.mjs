/**
 * 更新提醒弹层的「该不该出现」测试。
 *
 * 浏览器半是 UMD（`window.__ModuleLoader__.load`），这里造一个最小宿主把 React
 * 换成可控 mock —— 只验组件的判定分支，不验真实渲染（那需要真实浏览器）。
 * 要守住的三条：
 *   1. 没有可用的更新信息时不打扰（返回 null）；
 *   2. 有新版本时必须出现（覆盖在对话之上）；
 *   3. 用户点过「稍后」的那个版本不再弹，但换新版本要重新弹。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

globalThis.window = {
  __ModuleLoader__: {
    load(definition) {
      globalThis.__palClientModule = definition;
    },
  },
  localStorage: {
    getItem: () => null,
    setItem: () => {},
  },
};

await import('../lib/client.js');

/** 造一个只够用的 React mock：useState 按给定序列回值，useEffect 不执行。 */
function makeReact(values) {
  let index = 0;
  return {
    useState: (initial) => [index < values.length ? values[index++] : initial, () => {}],
    useEffect: () => {},
    createElement: (type, props, ...children) => ({ type, props: props || {}, children }),
  };
}

function loadComponent(react) {
  return globalThis.__palClientModule.factory((name) => (name === 'react' ? react : undefined));
}

const UPDATE_DATA = {
  ok: true,
  local: '0.1.0',
  latest: '0.2.0',
  updateAvailable: true,
  notes: ['【新增】', '· 新功能 A'],
};

test('没有更新信息时不弹任何东西', () => {
  const client = loadComponent(makeReact([]));
  assert.equal(client.UpdateNotice(), null);
});

test('已是最新时不弹', () => {
  const client = loadComponent(
    makeReact([{ data: { ...UPDATE_DATA, updateAvailable: false }, dismissed: '' }, false, null]),
  );
  assert.equal(client.UpdateNotice(), null);
});

test('检查失败（ok=false）不弹，不拿错误吓用户', () => {
  const client = loadComponent(
    makeReact([{ data: { ok: false, local: '0.1.0', error: 'network' }, dismissed: '' }, false, null]),
  );
  assert.equal(client.UpdateNotice(), null);
});

test('有新版本时弹出覆盖层，带更新内容与两个按钮', () => {
  const client = loadComponent(makeReact([{ data: UPDATE_DATA, dismissed: '' }, false, null]));
  const element = client.UpdateNotice();
  assert.ok(element, '必须弹出来');
  assert.equal(element.props.style.position, 'fixed', '必须是覆盖在界面之上的层');
  const text = JSON.stringify(element);
  assert.match(text, /0\.1\.0/);
  assert.match(text, /0\.2\.0/);
  assert.match(text, /新功能 A/, '更新公告要展示出来');
  assert.match(text, /noticeLater|稍后|Later/i);
});

test('点过「稍后」的版本不再弹，但不影响更早的判定', () => {
  const client = loadComponent(makeReact([{ data: UPDATE_DATA, dismissed: '0.2.0' }, false, null]));
  assert.equal(client.UpdateNotice(), null);
});

test('换了一个新版本会重新提醒（不能被上一次的稍后永久压住）', () => {
  const client = loadComponent(
    makeReact([
      { data: { ...UPDATE_DATA, latest: '0.3.0' }, dismissed: '0.2.0' },
      false,
      null,
    ]),
  );
  const element = client.UpdateNotice();
  assert.ok(element, '新版本必须重新弹');
  assert.match(JSON.stringify(element), /0\.3\.0/);
});

test('本地开发装法的结论不能画成红字（那是正常状态，不是更新失败）', async () => {
  // 2026-11-07 用户截图里的红字就来自这里：本地开发区没有 remote，
  // applyUpdate 返回 code=no-remote，旧代码一律按 blocked 用 S.error 渲染。
  const source = await fs.readFile(new URL('../lib/client.js', import.meta.url), 'utf8');
  assert.match(
    source,
    /local-ahead[\s\S]{0,400}?local-behind[\s\S]{0,400}?no-remote/,
    '三种本地开发装法结论必须都被识别',
  );
  assert.match(
    source,
    /kind:\s*localOnly\s*\?\s*"info"\s*:\s*"done"/,
    '本地装法要落到 info（中性样式），不能落到 blocked（红字）',
  );
  // 红字只留给 blocked：这是「真的出错了」的唯一信号。
  assert.match(
    source,
    /result\.kind === "blocked"\s*\?\s*S\.error\s*:\s*S\.noticeMeta/,
    '只有 blocked 才用错误样式',
  );
});

test('插件版本只认自己的 package.json，绝不拿 App 的 Release tag 当版本', async () => {
  const raw = await fs.readFile(new URL('../lib/update.js', import.meta.url), 'utf8');
  // 注释里解释历史教训是必要的（否则下一个人还会踩）；要守的是**代码**里不许再问 Releases。
  const code = raw
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|\s)\/\/.*$/gm, '$1');
  assert.equal(
    /api\.github\.com/.test(code),
    false,
    '代码里不能再请求 GitHub Releases —— 那是 App 的版本号，会让插件永远误报可更新',
  );
  assert.equal(/tag_name/.test(code), false, '代码里不能再从 Release tag 推断插件版本');
  // 发布页地址仍然保留，只是用途变成「打开发布页」，不再参与版本判定。
  assert.match(code, /RELEASE_URL\s*=\s*'https:\/\/github\.com\/yiheng-zyh\/DSH-Pal\/releases\/latest'/);
});

test('面板里的直连开关区块仍然注册在 settings.section，提醒层单独挂 shell.overlay', () => {
  const registered = [];
  const react = makeReact([]);
  const client = loadComponent(react);
  const ctx = {
    slots: {
      inject: (slot, factory) => {
        factory();
        return () => {};
      },
      register: (definition) => {
        registered.push(`${definition.name}#${definition.id}`);
        return () => {};
      },
    },
    locale: { register: () => () => {}, bind: () => (key) => key },
    effect: (factory) => factory(),
    get: () => undefined,
  };
  client.apply(ctx);
  assert.deepEqual(registered, [
    'settings.section#pal-channel',
    'shell.overlay#pal-channel-update-notice',
  ]);
  assert.equal(
    registered.filter((item) => item.startsWith('settings.section#')).length,
    1,
    '设置左侧导航只能有一个本插件的入口',
  );
});

test('合并后的总览里同时有隧道切换按钮和直连开关，功能没有丢', () => {
  const overview = {
    ok: true,
    pluginVersion: '0.1.0',
    channels: [
      { id: 'domestic', name: '国内 frp 隧道', group: 'public', configured: true, enabled: true, phase: 'current', origin: 'https://a.example:12451', detail: '' },
      { id: 'cloudflare', name: 'Cloudflare 快速隧道', group: 'public', configured: true, enabled: false, phase: 'available', origin: null, detail: '已配置，可切换' },
      { id: 'ipv6', name: 'IPv6 直连', group: 'direct', configured: true, enabled: true, phase: 'ready', origin: 'http://[2001:250::1]:18772', detail: '' },
      { id: 'lan', name: 'Wi-Fi 直连', group: 'direct', configured: false, enabled: false, phase: 'off', origin: null, detail: '未开启' },
      { id: 'mesh', name: '组网通道（Tailscale / WireGuard）', group: 'mesh', configured: null, enabled: null, phase: 'app-managed', origin: null, detail: '' },
    ],
    checks: {
      ipv6: { count: 2, addresses: ['2001:250::1'] },
      gateway: { running: true },
      installed: true,
      direct: { enabled: true, ipv6: true, lan: false },
    },
  };
  const client = loadComponent(
    makeReact([{ phase: 'ok', data: overview }, { phase: 'idle', data: null }, '', '', false, false]),
  );
  const text = JSON.stringify(client.ChannelsPanel());
  assert.match(text, /switchTo/, '可用隧道要有「切到这条」按钮');
  assert.match(text, /turnOn|turnOff/, '直连通道要有开关');
  assert.match(text, /18772/, '要显示直连地址');
  assert.match(text, /2001:250::1/, '地址要带出来');
});

test('浏览器半的 reportFailure 必须存在（曾经被误删导致插件静默失败）', async () => {
  const source = await fs.readFile(
    new URL('../lib/client.js', import.meta.url),
    'utf8',
  );
  // 2026-11-06 的教训：合并设置入口时脚本删组件，把 reportFailure 定义一起带走了，
  // 于是 apply 里每次报错都会抛 ReferenceError —— 插件加载失败变成完全静默。
  assert.match(source, /function reportFailure\s*\(/, 'reportFailure 必须有定义');
  assert.match(source, /\/api\/pal-channel\/client-log/, '失败要落到 host 日志');
  // 有调用就必须有定义，数量至少能对上（1 个定义，多处调用）。
  const calls = (source.match(/reportFailure\(/g) || []).length;
  assert.ok(calls > 1, `应至少有定义 + 调用，实际出现 ${calls} 次`);
});

test('公网隧道两条要框在「二选一」分组里，其它通道不进这个框', () => {
  const overview = {
    ok: true,
    pluginVersion: '0.1.0',
    channels: [
      { id: 'domestic', name: '国内 frp 隧道', group: 'public', configured: true, enabled: true, phase: 'current', origin: 'https://a.example:12451', detail: '' },
      { id: 'cloudflare', name: 'Cloudflare 快速隧道', group: 'public', configured: true, enabled: false, phase: 'available', origin: null, detail: '' },
      { id: 'ipv6', name: 'IPv6 直连', group: 'direct', configured: false, enabled: false, phase: 'off', origin: null, detail: '' },
    ],
    checks: {
      ipv6: { count: 2, addresses: ['2001:250::1'] },
      gateway: { running: true },
      installed: true,
      direct: { enabled: false, ipv6: false, lan: false },
    },
  };
  const client = loadComponent(
    makeReact([{ phase: 'ok', data: overview }, { phase: 'idle', data: null }, '', '', false, false]),
  );
  const text = JSON.stringify(client.ChannelsPanel());
  assert.match(text, /tunnelGroupTitle/, '要有分组标题');
  assert.match(text, /tunnelGroupHint/, '要说明只能同时走一条');
  assert.match(text, /tunnel-group/, '两条隧道要装进同一个分组容器');
  // 组内行的样式是"无底部分隔线"，与组外行区分（组外是 1px solid）。
  assert.match(text, /borderBottom\\?":\\?"none/, '组内行要去掉底部分隔线');
});