import test from 'node:test';
import assert from 'node:assert/strict';
import {planChannelSwitch,currentChannel,Channel,normalizeChannel} from './channel-plan.mjs';

const SOURCE = `- id: remote-web-ui
  name: "@linxin666/dsh-remote-web-ui"
  config:
    publicBaseUrl: https://frp.example.com:12451
    autoTunnel: false
    relay: false
    offlineAfterMs: 90000
    maxDevices: 24
    trustedHosts:
      - frp.example.com
`;

test('切到 cloudflare：开 autoTunnel 并清空 publicBaseUrl', () => {
  // 插件规则是「publicBaseUrl 填了就用它，优先于 autoTunnel」，
  // 所以切 CF 必须清空，否则二维码永远指向旧入口（2026-11-04 实测踩到）。
  const plan = planChannelSwitch(SOURCE, {channel: Channel.CLOUDFLARE});
  assert.equal(plan.channel, 'cloudflare');
  assert.ok(plan.changed, '应当有变更');
  assert.ok(plan.fields.includes('autoTunnel'));
  assert.ok(plan.fields.includes('publicBaseUrl'));
  assert.match(plan.text, /autoTunnel: true/);
  assert.match(plan.text, /publicBaseUrl: ""/);
});

test('切到 domestic：关 autoTunnel 并写入国内入口', () => {
  const cf = `- id: remote-web-ui
  name: "@linxin666/dsh-remote-web-ui"
  config:
    autoTunnel: true
    publicBaseUrl: ""
`;
  const plan = planChannelSwitch(cf, {channel: Channel.DOMESTIC, domesticOrigin: 'https://frp.example.com:12451'});
  assert.match(plan.text, /autoTunnel: false/);
  // YAML 可能给含冒号的 URL 加引号（https://x:12451），两种写法都接受，
  // 断言解析后的值而不是字符串字面量，避免被序列化细节绑死。
  const parsed = currentChannel(plan.text, {settings: false});
  assert.equal(parsed.channel, 'domestic');
  assert.equal(parsed.publicBaseUrl, 'https://frp.example.com:12451');
});

test('切换不碰其它配置项', () => {
  // trustedHosts / offlineAfterMs / maxDevices 属于用户与插件既有配置，
  // 切通道不该改它们 —— 这是最小改动原则。
  const plan = planChannelSwitch(SOURCE, {channel: Channel.CLOUDFLARE});
  assert.match(plan.text, /offlineAfterMs: 90000/);
  assert.match(plan.text, /maxDevices: 24/);
  assert.match(plan.text, /frp\.example\.com/); // trustedHosts 里的条目保留
});

test('已在目标通道时不产生变更（幂等）', () => {
  const plan = planChannelSwitch(SOURCE, {channel: Channel.DOMESTIC, domesticOrigin: 'https://frp.example.com:12451'});
  assert.equal(plan.changed, false, '已经是 domestic 就不该再写');
  assert.equal(plan.text, SOURCE);
});

test('读回当前通道', () => {
  const state = currentChannel(SOURCE, {settings: false});
  assert.equal(state.channel, 'domestic');
  assert.equal(state.autoTunnel, false);
  assert.equal(state.publicBaseUrl, 'https://frp.example.com:12451');
  assert.equal(state.relay, false);
});

test('autoTunnel=true 时读回 cloudflare', () => {
  const cf = `- id: remote-web-ui
  name: x
  config:
    autoTunnel: true
    publicBaseUrl: ""
`;
  assert.equal(currentChannel(cf, {settings: false}).channel, 'cloudflare');
});

test('非法通道名被拒绝', () => {
  assert.throws(() => normalizeChannel('bogus'), /channel must be/);
  assert.throws(() => planChannelSwitch(SOURCE, {channel: 'nope'}), /channel must be/);
});

test('非法输入抛错而不是写坏文件', () => {
  // 不管是 YAML 解析失败，还是找不到唯一的 remote-web-ui 配置，
  // 都必须抛错 —— 绝不能返回一份"看起来能写"的文本把配置写坏。
  assert.throws(
    () => planChannelSwitch('::::not yaml:::', {channel: Channel.CLOUDFLARE}),
    /not valid YAML|Expected exactly one remote-web-ui/,
  );
  // 完全没有插件条目时也必须拒绝
  assert.throws(
    () => planChannelSwitch('- id: something-else\n  name: x\n', {channel: Channel.CLOUDFLARE}),
    /Expected exactly one remote-web-ui/,
  );
});
