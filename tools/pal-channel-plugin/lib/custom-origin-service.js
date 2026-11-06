/**
 * 自定义 HTTPS 入口的读写 —— 用户已经有自己的域名/反代时走这条。
 *
 * 典型场景（都不需要 Sakura frp）：
 *   - 家里路由器/群晖上已经有 nginx、caddy，把某个子域反代到这台电脑的网关端口；
 *   - 用 Cloudflare Named 隧道绑自己的域名；
 *   - 公司/学校给了一个能做 HTTPS 转发的外部地址。
 *
 * 设计要点：
 *  - 写进 `config.json` 的 `customOrigin` 字段，**不动** publicOrigin（那是 Sakura 那条
 *    隧道的地址，重配隧道会重写）。两者并存，publishedOrigin() 决定谁生效。
 *  - 必须同时把该域名写进插件的 trustedHosts：插件的动态信任只认私有地址，
 *    不认公网域名，不登记就无法完成首次配对（这是最容易踩的坑）。
 *  - 只允许 https + 受信证书的域名：直连那种 http 明文入口走 IPv6/LAN 通道，
 *    不在这里开口子。
 *
 * @module dsh-pal-channel/custom-origin-service
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { validateConfig } from '../../remote-access/config.mjs';
import { applyTrustedHost } from '../../remote-access/trust.mjs';
import { remoteAccessDirectory } from './overview.js';

/** 写前读原文件、校验、原子写；语义与 direct-service 一致。 */
async function writeConfig(raw, directory) {
  const file = path.join(directory, 'config.json');
  const backup = Buffer.from(await fs.readFile(file));
  const next = validateConfig(raw);
  const temporary = `${file}.dshpal-custom.tmp`;
  try {
    await fs.writeFile(temporary, JSON.stringify(next, null, 2));
    const after = Buffer.from(await fs.readFile(file));
    if (!after.equals(backup)) {
      return { status: 409, body: { ok: false, code: 'conflict', error: '配置文件在写入期间被其它程序改动，本次未写入，请重试' } };
    }
    await fs.rename(temporary, file);
  } catch (error) {
    await fs.unlink(temporary).catch(() => {});
    return { status: 500, body: { ok: false, code: 'write-failed', error: String(error?.message ?? error) } };
  }
  return { status: 200, body: { ok: true, config: next } };
}

/**
 * 设置 / 清除自定义 HTTPS 入口。
 * @param {{origin?:string|null}} input - 传空或 null 表示清除，回到 publicOrigin
 */
export async function setCustomOrigin(input, { directory = remoteAccessDirectory() } = {}) {
  if (!directory) {
    return done(409, {
      ok: false,
      code: 'not-installed',
      error: '这台电脑没有安装 DSH Pal 的远程访问工具，无法保存自定义入口',
    });
  }
  const file = path.join(directory, 'config.json');
  let raw;
  try {
    raw = JSON.parse(await fs.readFile(file, 'utf8'));
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return done(409, {
        ok: false,
        code: 'no-config',
        error: '还没有 remote-access 配置（config.json 不存在）。请先运行一次 tools/remote-access/configure.mjs。',
      });
    }
    return done(409, { ok: false, code: 'bad-config', error: '现有 config.json 不是合法 JSON，未做任何改动' });
  }

  const requested = input?.origin;
  const clearing = requested === undefined || requested === null || String(requested).trim() === '';
  if (clearing) {
    delete raw.customOrigin;
    const written = await writeConfig(raw, directory);
    if (written.status !== 200) return done(written.status, written.body);
    return done(200, {
      ok: true,
      customOrigin: null,
      note: '已清除自定义入口，回到 remote-access 自己配置的 publicOrigin。网关约 5 秒内热生效。',
    });
  }

  // 规范化 + 校验（config.customOrigin 会做 https/证书域名检查）。
  raw.customOrigin = String(requested).trim();
  let next;
  try {
    next = validateConfig(raw);
  } catch (error) {
    return done(400, { ok: false, code: 'invalid-origin', error: String(error?.message ?? error) });
  }
  const written = await writeConfig(raw, directory);
  if (written.status !== 200) return done(written.status, written.body);

  /**
   * 同步 trustedHosts。失败**不能让整次保存看起来失败**：配置已经写进去了，
   * 只是首次配对可能被 Host 白名单挡住 —— 必须如实把这件事告诉用户，
   * 并给出补救办法，而不是假装成功。
   */
  let trusted = { changed: false };
  let trustedError = null;
  if (typeof next.dshConfigPath === 'string' && next.dshConfigPath) {
    try {
      trusted = await applyTrustedHost(next.dshConfigPath, next.publicOrigin, { extraOrigins: [next.customOrigin] });
    } catch (error) {
      trustedError = String(error?.message ?? error);
    }
  }

  return done(200, {
    ok: true,
    customOrigin: next.customOrigin,
    trustedHostsChanged: trusted.changed === true,
    trustedError,
    note: trustedError
      ? `已保存入口，但写入插件 trustedHosts 失败（${trustedError.slice(0, 120)}）。首次配对可能被 Host 白名单拦下，请检查 DSH 配置文件后重启 DSH。`
      : '已保存。网关约 5 秒内改用这个入口；该域名已登记进插件 trustedHosts，手机扫码即可配对。',
  });
}

/** 读当前自定义入口（只读，供面板显示）。 */
export async function readCustomOrigin({ directory = remoteAccessDirectory() } = {}) {
  if (!directory) return { customOrigin: null, publicOrigin: null, effectiveOrigin: null };
  try {
    const raw = JSON.parse(await fs.readFile(path.join(directory, 'config.json'), 'utf8'));
    const custom = typeof raw.customOrigin === 'string' && raw.customOrigin ? raw.customOrigin : null;
    const base = typeof raw.publicOrigin === 'string' ? raw.publicOrigin : null;
    return { customOrigin: custom, publicOrigin: base, effectiveOrigin: custom || base };
  } catch {
    return { customOrigin: null, publicOrigin: null, effectiveOrigin: null };
  }
}

function done(status, body) {
  return { status, body };
}