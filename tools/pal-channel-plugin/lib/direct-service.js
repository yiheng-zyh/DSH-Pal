/**
 * 直连入口（IPv6 / 局域网）的开关 —— 插件面板那一侧的写路径。
 *
 * 与 `tools/remote-access/configure-direct.mjs` **完全同语义**：
 *   - 命令行给出的是「完整期望集合」，未提及的关闭；这里一样。
 *   - 写前拿到原文件字节，写临时文件后用字节比对确认期间没人改过，再 rename。
 *   - 只改 `direct` 段，其余字段（provider / publicOrigin / frpc…）原样保留。
 *   - 写完不重启网关：`run.mjs` 每 5 秒热加载 direct 段（run.mjs:156-165）。
 *
 * 为什么不让插件自己起监听器：直连监听器由网关进程持有（run.mjs:58），
 * 插件与网关是两个进程；插件去绑同一个端口只会 bind 失败，或者和网关抢状态。
 * 插件的职责只是「改配置 + 读结果」，这一点不越界。
 *
 * @module dsh-pal-channel/direct-service
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import { validateConfig } from '../../remote-access/config.mjs';
import { remoteAccessDirectory } from './overview.js';

/**
 * 写入新的直连期望集合。
 * @param {{ipv6?:boolean, lan?:boolean}} desired - 完整期望；两者都为 false 等价于关闭
 * @returns {{status:number, body:object}}
 */
export async function setDirect(desired, { directory = remoteAccessDirectory() } = {}) {
  if (!directory) {
    return done(409, {
      ok: false,
      code: 'not-installed',
      error: '这台电脑没有安装 DSH Pal 的远程访问工具，无法开启直连入口',
    });
  }
  const file = path.join(directory, 'config.json');
  let backup;
  try {
    backup = Buffer.from(await fs.readFile(file));
  } catch (error) {
    if (error?.code === 'ENOENT') {
      return done(409, {
        ok: false,
        code: 'no-config',
        error: '还没有 remote-access 配置（config.json 不存在）。请先运行一次隧道配置流程，或改用工具目录里的命令。',
      });
    }
    return done(500, { ok: false, code: 'read-failed', error: String(error?.message ?? error) });
  }

  let raw;
  try {
    raw = JSON.parse(backup.toString('utf8'));
  } catch {
    return done(409, { ok: false, code: 'bad-config', error: '现有 config.json 不是合法 JSON，未做任何改动' });
  }

  const ipv6 = desired?.ipv6 === true;
  const lan = desired?.lan === true;
  if (ipv6 || lan) raw.direct = { enabled: true, ipv6, lan };
  else delete raw.direct;

  let next;
  try {
    next = validateConfig(raw);
  } catch (error) {
    return done(400, { ok: false, code: 'invalid-config', error: String(error?.message ?? error) });
  }

  const temporary = `${file}.dshpal-direct.tmp`;
  try {
    await fs.writeFile(temporary, JSON.stringify(next, null, 2));
    // 写前校验：网关也在读这个文件，期间被别的程序改过就放弃，避免互相覆盖。
    const after = Buffer.from(await fs.readFile(file));
    if (!after.equals(backup)) {
      return done(409, {
        ok: false,
        code: 'conflict',
        error: '配置文件在写入期间被其它程序改动，本次未写入，请重试',
      });
    }
    await fs.rename(temporary, file);
  } catch (error) {
    await fs.unlink(temporary).catch(() => {});
    return done(500, { ok: false, code: 'write-failed', error: String(error?.message ?? error) });
  }

  const effective = next.direct ?? { enabled: false, ipv6: false, lan: false };
  return done(200, {
    ok: true,
    direct: effective,
    note: effective.enabled
      ? '已写入，网关约 5 秒内热生效；稍后看本面板的状态是否变成「已开启」'
      : '已关闭直连入口，网关约 5 秒内解绑',
  });
}

function done(status, body) {
  return { status, body };
}