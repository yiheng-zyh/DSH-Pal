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
    'settings.section#pal-channel-channels',
    'shell.overlay#pal-channel-update-notice',
  ]);
});