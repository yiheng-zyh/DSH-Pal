/**
 * 「不许谎报成功」专项测试 —— 契约测试，用假文件系统和假依赖驱动 switchChannel。
 *
 * 为什么这一批最重要：
 * 用户点的是「一键切换」。如果接口返回 ok 但实际没写、或只写了一半、
 * 或写了却没生效，用户看到的是"成功"，然后拿手机扫一个指向旧地址/坏地址的二维码 ——
 * 这比明确报错糟得多。所以每个失败分支都必须在测试里被钉死：
 * **返回 ok:true 的路径，必须伴随磁盘上真实的目标状态。**
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { switchChannel, findRemoteEntry, channelCatalog } from '../lib/channel-service.js';
import { currentChannel, Channel } from '../../remote-access/channel-plan.mjs';

/** 构造临时目录，用完即删。 */
async function withTempDir(run) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-channel-test-'));
  try {
    return await run(dir);
  } finally {
    await fs.rm(dir, { recursive: true, force: true });
  }
}

/** 一份最小可用的补丁（自动生成，不含任何真实地址）。 */
const PATCH = `# 测试用补丁
- id: remote-web-ui
  name: "@linxin666/dsh-remote-web-ui"
  config:
    publicBaseUrl: "https://frp.example.com:12451"
    autoTunnel: false
    relay: false
`;

/** 记录备份调用次数（用来验证「不变就不备份」）。 */
function backupSpy() {
  const calls = [];
  const fn = async (bytes) => {
    calls.push(bytes.length);
    return 'C:/fake/backup.dpapi';
  };
  fn.calls = calls;
  return fn;
}

/** 永远成功的预检。 */
const preflightOk = async () => ({ ok: true, detail: 'ok', probe: 'https://frp.example.com:12451/api/pair/status' });
/** 永远失败的预检。 */
const preflightFail = async () => ({ ok: false, detail: 'ECONNREFUSED', probe: 'https://frp.example.com:12451/api/pair/status' });

test('切到 cloudflare 成功：磁盘上真的是目标状态', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const result = await switchChannel(
      { patchPath, domesticOrigin: 'https://frp.example.com:12451', backupEncrypted: backupSpy(), preflight: preflightOk, entry: () => undefined },
      Channel.CLOUDFLARE,
    );
    assert.equal(result.status, 202, '没有 entry 时磁盘已写成功但运行时未生效，用 202 区分');
    assert.equal(result.body.ok, true);
    assert.equal(result.body.changed, true);
    // 关键：不是看返回值，而是**重新从磁盘读**确认。
    const onDisk = currentChannel(await fs.readFile(patchPath, 'utf8'), { settings: false });
    assert.equal(onDisk.channel, Channel.CLOUDFLARE);
    assert.equal(onDisk.autoTunnel, true);
    assert.equal(onDisk.publicBaseUrl, '', 'publicBaseUrl 必须清空，否则插件会继续用旧地址');
    assert.equal(result.body.runtimeApplied, false, '没有 entry 时如实回报运行时未生效');
    assert.equal(result.body.requiresRestart, true);
  });
});

test('已经是目标通道：不写、不备份，回报 changed:false', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const spy = backupSpy();
    const result = await switchChannel(
      { patchPath, domesticOrigin: 'https://frp.example.com:12451', backupEncrypted: spy, preflight: preflightOk, entry: () => undefined },
      Channel.DOMESTIC,
    );
    assert.equal(result.status, 200);
    assert.equal(result.body.ok, true);
    assert.equal(result.body.changed, false, '没有变化就必须如实回报 changed:false');
    assert.equal(result.body.message, '已经是目标通道，未做任何修改');
    assert.equal(spy.calls.length, 0, '没有变化时不应该产生备份');
    assert.equal(await fs.readFile(patchPath, 'utf8'), PATCH, '文件必须一个字节都没动');
  });
});

test('预检失败：拒绝切换，一个字节都不写', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    // 先置成 cloudflare，这样才能真的尝试切回 domestic
    await fs.writeFile(patchPath, PATCH.replace('autoTunnel: false', 'autoTunnel: true').replace('"https://frp.example.com:12451"', '""'), 'utf8');
    const before = await fs.readFile(patchPath, 'utf8');
    const spy = backupSpy();
    const result = await switchChannel(
      { patchPath, domesticOrigin: 'https://frp.example.com:12451', backupEncrypted: spy, preflight: preflightFail, entry: () => undefined },
      Channel.DOMESTIC,
    );
    assert.equal(result.status, 409);
    assert.equal(result.body.ok, false);
    assert.equal(result.body.code, 'preflight-failed');
    assert.equal(await fs.readFile(patchPath, 'utf8'), before, '预检失败后配置必须完全没动');
    assert.equal(spy.calls.length, 0, '预检失败连备份都不该产生');
  });
});

test('非法通道名：400 且不触碰文件', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const result = await switchChannel(
      { patchPath, domesticOrigin: '', backupEncrypted: backupSpy(), preflight: preflightOk, entry: () => undefined },
      'bogus',
    );
    assert.equal(result.status, 400);
    assert.equal(result.body.code, 'bad-channel');
    assert.equal(await fs.readFile(patchPath, 'utf8'), PATCH);
  });
});

test('备份失败：中止写入（没有退路就不写）', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const result = await switchChannel(
      {
        patchPath,
        domesticOrigin: 'https://frp.example.com:12451',
        backupEncrypted: async () => { throw new Error('DPAPI unavailable'); },
        preflight: preflightOk,
        entry: () => undefined,
      },
      Channel.CLOUDFLARE,
    );
    assert.equal(result.status, 500);
    assert.equal(result.body.code, 'backup-failed');
    assert.equal(await fs.readFile(patchPath, 'utf8'), PATCH, '备份失败后配置必须完全没动');
  });
});

test('并发修改：检测到就放弃，绝不覆盖对方写入', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const otherContent = `${PATCH}# 别人刚写进来的改动\n`;
    const result = await switchChannel(
      {
        patchPath,
        domesticOrigin: 'https://frp.example.com:12451',
        // 备份时偷偷改文件，模拟设置页面同时保存
        backupEncrypted: async () => {
          await fs.writeFile(patchPath, otherContent, 'utf8');
          return 'C:/fake/backup.dpapi';
        },
        preflight: preflightOk,
        entry: () => undefined,
      },
      Channel.CLOUDFLARE,
    );
    assert.equal(result.status, 409);
    assert.equal(result.body.code, 'concurrent-change');
    assert.equal(await fs.readFile(patchPath, 'utf8'), otherContent, '对方的写入必须被保留');
  });
});

test('配置里没有 remote-web-ui 条目：明确报错而不是瞎写', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    const noPlugin = '- id: something-else\n  name: x\n';
    await fs.writeFile(patchPath, noPlugin, 'utf8');
    const result = await switchChannel(
      { patchPath, domesticOrigin: 'https://frp.example.com:12451', backupEncrypted: backupSpy(), preflight: preflightOk, entry: () => undefined },
      Channel.CLOUDFLARE,
    );
    assert.equal(result.status, 500);
    assert.equal(result.body.ok, false);
    assert.equal(result.body.code, 'no-plugin-entry');
    assert.equal(await fs.readFile(patchPath, 'utf8'), noPlugin);
  });
});

test('配置文件不存在：报 no-config', async () => {
  await withTempDir(async (dir) => {
    const result = await switchChannel(
      { patchPath: path.join(dir, 'nope.yml'), domesticOrigin: '', backupEncrypted: backupSpy(), preflight: preflightOk, entry: () => undefined },
      Channel.CLOUDFLARE,
    );
    assert.equal(result.body.ok, false);
    assert.equal(result.body.code, 'no-config');
  });
});

test('国内入口未配置：拒绝并说明原因', async () => {
  await withTempDir(async (dir) => {
    const patchPath = path.join(dir, 'cordis.patch.yml');
    await fs.writeFile(patchPath, PATCH, 'utf8');
    const result = await switchChannel(
      { patchPath, domesticOrigin: '', backupEncrypted: backupSpy(), preflight: preflightOk, entry: () => undefined },
      Channel.DOMESTIC,
    );
    assert.equal(result.status, 409);
    assert.equal(result.body.code, 'no-domestic-origin');
  });
});

test('findRemoteEntry 按 id 或包名都能找到', () => {
  assert.equal(findRemoteEntry([{ options: { id: 'remote-web-ui', name: 'x' } }])?.options.id, 'remote-web-ui');
  assert.equal(
    findRemoteEntry([{ options: { id: 'other', name: '@linxin666/dsh-remote-web-ui' } }])?.options.name,
    '@linxin666/dsh-remote-web-ui',
  );
  assert.equal(findRemoteEntry([]), undefined);
  assert.equal(findRemoteEntry(undefined), undefined);
});

test('通道清单如实标注可用性', () => {
  const withoutOrigin = channelCatalog('');
  assert.equal(withoutOrigin.find((c) => c.id === Channel.DOMESTIC).available, false);
  assert.equal(withoutOrigin.find((c) => c.id === Channel.CLOUDFLARE).available, true);
  const withOrigin = channelCatalog('https://frp.example.com:12451');
  assert.equal(withOrigin.find((c) => c.id === Channel.DOMESTIC).available, true);
  assert.equal(withOrigin.find((c) => c.id === Channel.DOMESTIC).origin, 'https://frp.example.com:12451');
});
