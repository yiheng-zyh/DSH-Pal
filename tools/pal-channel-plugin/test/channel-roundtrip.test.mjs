/**
 * 配置改写的回归测试。
 *
 * 为什么必须单独测这件事：
 * 通道切换的**唯一不可逆风险**是「把用户的 cordis.patch.yml 写坏」。那份文件里有
 * 大量人工注释（trustedHosts 的归属标记 `# DSH Pal remote-access managed`、断连容差的
 * 中文说明、`!!js` 表达式…）。一旦往返序列化把它们冲掉，`trust.mjs` 就认不出自己的
 * 条目，下次同步会重复追加；用户手写的说明也会消失。
 *
 * 所以这里的断言全部围绕「除了目标字段，一个字节的语义都不许变」。
 * 测试数据是**构造的**，不含任何真实域名、IP 或本机路径。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { planChannelSwitch, currentChannel, Channel } from '../../remote-access/channel-plan.mjs';

/**
 * `yaml` 装在 `tools/remote-access/node_modules`，插件目录自己没有 node_modules。
 * 用 createRequire 从依赖**实际所在**的目录解析，而不是指望插件目录能裸解析到它 ——
 * 这样测试的依赖关系和运行时（插件同样从 remote-access 目录借依赖）保持一致。
 */
const requireFromRemoteAccess = createRequire(
  fileURLToPath(new URL('../../remote-access/package.json', import.meta.url)),
);
const YAML = requireFromRemoteAccess('yaml');

/** 构造一份「像真配置」的补丁：带注释、带 !!js、带与通道无关的字段。 */
const SOURCE = `# 顶部说明：这是 profile 补丁层
# 第二行注释也要留住

- id: remote-web-ui
  name: "@linxin666/dsh-remote-web-ui"
  config:
    trustedHosts:
      - frp.example.com # DSH Pal remote-access managed
      - "frp.example.com:12451" # DSH Pal remote-access managed
    publicBaseUrl: "https://frp.example.com:12451"
    autoTunnel: false
    relay: false
    # 断连容忍度：弱网下多给几次重试机会
    offlineAfterMs: 90000
    maxDevices: 24

# 这一行是行尾注释，绝不能被吃掉
- id: some-other-plugin
  name: "@deepseek-ai/dsh-something"
  config:
    enabled: !!js process.platform === 'win32'
`;

/** 统计注释行（排除空行）。 */
function commentLines(text) {
  return text.split('\n').filter((line) => line.trim().startsWith('#')).length;
}

/** 统计 `!!js` 出现次数。 */
function jsTags(text) {
  return (text.match(/!!js/g) ?? []).length;
}

/** 把 `!!js` 换成普通 tag，解析成可比较的 JS 值。 */
function toPlain(text) {
  const doc = YAML.parseDocument(text.replace(/!!js/g, '!js'), {
    customTags: [{ tag: '!js', resolve: (value) => `__JS__${value}` }],
  });
  if (doc.errors.length > 0) throw new Error(doc.errors.map((e) => e.message).join('; '));
  return doc.toJS();
}

/** 深度 diff，返回变化点列表。 */
function diff(a, b, path = '', out = []) {
  if (a === b) return out;
  const both = a && b && typeof a === 'object' && typeof b === 'object';
  if (!both) {
    out.push(`${path}: ${JSON.stringify(a)} -> ${JSON.stringify(b)}`);
    return out;
  }
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  for (const key of keys) {
    const isArray = Array.isArray(a) || Array.isArray(b);
    diff(a?.[key], b?.[key], isArray ? `${path}[${key}]` : path ? `${path}.${key}` : key, out);
  }
  return out;
}

test('切到 cloudflare 时，注释与 !!js 一个都不少', () => {
  // 这是整个功能里最不可逆的失败模式：写坏用户配置。
  const plan = planChannelSwitch(SOURCE, { channel: Channel.CLOUDFLARE });
  assert.ok(plan.changed);
  assert.equal(commentLines(plan.text), commentLines(SOURCE), '注释行数必须完全一致');
  assert.equal(jsTags(plan.text), jsTags(SOURCE), '!!js 标签数量必须完全一致');
});

test('切到 cloudflare 时，语义 diff 只有目标字段', () => {
  const plan = planChannelSwitch(SOURCE, { channel: Channel.CLOUDFLARE });
  const changes = diff(toPlain(SOURCE), toPlain(plan.text));
  // 只允许 publicBaseUrl 与 autoTunnel 变化，其它字段（含别的插件条目）一个都不许动。
  assert.deepEqual(changes.sort(), [
    '[0].config.autoTunnel: false -> true',
    '[0].config.publicBaseUrl: "https://frp.example.com:12451" -> ""',
  ].sort());
});

test('切到 domestic 时，语义 diff 只有 autoTunnel 与 publicBaseUrl', () => {
  const cf = `- id: remote-web-ui
  name: "@linxin666/dsh-remote-web-ui"
  config:
    autoTunnel: true
    publicBaseUrl: ""
    relay: false
    offlineAfterMs: 90000
`;
  const plan = planChannelSwitch(cf, { channel: Channel.DOMESTIC, domesticOrigin: 'https://frp.example.com:12451' });
  const changes = diff(toPlain(cf), toPlain(plan.text));
  // 两个字段都必须变：关掉插件自带隧道（autoTunnel→false），并写入国内入口。
  // relay / offlineAfterMs 等无关字段一个都不许动。
  assert.deepEqual(changes.sort(), [
    '[0].config.autoTunnel: true -> false',
    '[0].config.publicBaseUrl: "" -> "https://frp.example.com:12451"',
  ].sort());
});

test('已经是目标通道时不产生任何字节变化（幂等）', () => {
  // 幂等是「不谎报成功」的基础：不变就返回原文，调用方据此回报 already。
  const plan = planChannelSwitch(SOURCE, {
    channel: Channel.DOMESTIC,
    domesticOrigin: 'https://frp.example.com:12451',
  });
  assert.equal(plan.changed, false);
  assert.equal(plan.text, SOURCE);
});

test('通道字段之外的行尾注释也保留', () => {
  const plan = planChannelSwitch(SOURCE, { channel: Channel.CLOUDFLARE });
  assert.match(plan.text, /# DSH Pal remote-access managed/);
  assert.match(plan.text, /# 这一行是行尾注释/);
  assert.match(plan.text, /# 断连容忍度：弱网下多给几次重试机会/);
});

test('读回 cloudflare 状态时 autoTunnel 为 true 且地址为空', () => {
  const plan = planChannelSwitch(SOURCE, { channel: Channel.CLOUDFLARE });
  const state = currentChannel(plan.text, { settings: false });
  assert.equal(state.channel, Channel.CLOUDFLARE);
  assert.equal(state.autoTunnel, true);
  // 关键：必须清空，否则插件的「publicBaseUrl 优先于 autoTunnel」规则会让二维码指向旧地址。
  assert.equal(state.publicBaseUrl, '');
});
