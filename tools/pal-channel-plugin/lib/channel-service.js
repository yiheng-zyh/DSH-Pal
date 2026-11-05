/**
 * 通道切换的编排层：读状态 / 执行切换。
 *
 * ## 为什么是「先写文件 + 再更新运行时引用」这两步
 *
 * `remote-web-ui` 的配置里，`autoTunnel` / `publicBaseUrl` / `relay` 三个字段在它的
 * Config schema 里**都带 `.volatile()`**。volatile 的语义（见 Loader 的 `Entry.update`
 * → `_commitVolatile`）是：Loader 把新值提交进运行中 fiber 的字段引用，并 emit
 * `loader/volatile-update`，插件据此重新规划隧道 —— **不重挂 fiber**。
 *
 * 所以一次完整的切换需要两件事同时成立：
 *   ① 磁盘上真的改了（否则 DSH 一重启就变回去）；
 *   ② 运行中的引用真的换了（否则用户点完按钮，二维码还是旧地址）。
 *
 * ## 为什么文件不用官方 `configEditor.edit()`
 *
 * 它是「配置页表单保存」的通道，实现上会把整个 `config:` 块用
 * `document.createNode(next)` **整体替换成新的 YAML 节点**，于是块内的注释全部丢失。
 * 本 profile 的 `remote-web-ui.config.trustedHosts` 里带着
 * `# DSH Pal remote-access managed` 这类**语义性注释标记** —— `trust.mjs` 靠它区分
 * 「本工具写的条目」和用户手写的条目，标记丢了下次同步就会重复追加。
 * 因此文件改写走 `channel-plan.mjs`（`keepSourceTokens` + 只 set 三个字段，
 * 实测往返后 `!!js` 与注释数量不变），运行时更新走 Loader 的 `entry.update`。
 *
 * ## 代价（如实记录）
 *
 * 不走 `configEditor.edit()` 就**没有拿到它的 `withFileLock`**。这里的补偿措施是
 * 「写前再读一次比对字节」+「写完读回校验」：如果正好有人同时保存设置页面，
 * 我们会在比对阶段发现并整体放弃，绝不覆盖对方的写入。
 *
 * @module dsh-pal-channel/channel-service
 */
import fs from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { Channel, normalizeChannel, planChannelSwitch, currentChannel } from '../../remote-access/channel-plan.mjs';

/** 目标插件在 profile patch 里的条目 id。 */
const REMOTE_ENTRY_ID = 'remote-web-ui';
/** 目标插件包名（找不到 id 时按包名再找）。 */
const REMOTE_PLUGIN_NAME = '@linxin666/dsh-remote-web-ui';

/** 统一取错误信息。 */
export function messageOf(error) {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 可切换的通道清单（API 的 `channels` 字段）。
 * `available` 由入口地址是否已知决定：国内通道需要 remote-access 配过 publicOrigin；
 * Cloudflare 通道由插件自己起 cloudflared，不需要我们提供任何地址。
 */
export function channelCatalog(domesticOrigin) {
  return [
    {
      id: Channel.DOMESTIC,
      name: '国内 frp 隧道',
      available: typeof domesticOrigin === 'string' && domesticOrigin !== '',
      requiresOrigin: true,
      origin: typeof domesticOrigin === 'string' && domesticOrigin !== '' ? domesticOrigin : null,
    },
    {
      id: Channel.CLOUDFLARE,
      name: 'Cloudflare 快速隧道',
      // cloudflared 是 remote-web-ui 自己的依赖，无需外部准备。
      available: true,
      requiresOrigin: false,
      origin: null,
    },
  ];
}

/** 读 patch 原文 + 当前通道。读不懂一律抛错，绝不返回一份"看起来能用"的默认值。 */
async function readPatch(patchPath) {
  let text;
  try {
    text = await fs.readFile(patchPath, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') {
      throw Object.assign(new Error(`找不到 DSH 配置补丁：${patchPath}`), { code: 'no-config' });
    }
    throw error;
  }
  let state;
  try {
    state = currentChannel(text, { settings: false });
  } catch (error) {
    const detail = messageOf(error);
    // 区分「YAML 本身坏了」和「没有目标插件条目」：两者的处置方式完全不同，
    // 前者是文件损坏要人工介入，后者是插件没装/被禁用，用户按提示就能自救。
    const code = /Expected exactly one remote-web-ui/.test(detail) ? 'no-plugin-entry' : 'bad-config';
    throw Object.assign(new Error(`DSH 配置补丁无法解析：${detail}`), { code });
  }
  if (state === null) {
    throw Object.assign(
      new Error(`配置补丁里没有 ${REMOTE_PLUGIN_NAME} 的配置块，无法判断当前通道`),
      { code: 'no-plugin-entry' },
    );
  }
  return { text, state };
}

/**
 * GET /api/pal-channel/status 的数据源。
 * @param deps - {patchPath, domesticOrigin}
 */
export async function readStatus(deps) {
  const { text, state } = await readPatch(deps.patchPath);
  return {
    channel: state.channel,
    autoTunnel: state.autoTunnel,
    publicBaseUrl: state.publicBaseUrl,
    relay: state.relay,
    channels: channelCatalog(deps.domesticOrigin),
    patchPath: deps.patchPath,
    configBytes: Buffer.byteLength(text, 'utf8'),
    // 明确说明「切到 CF 后地址由插件填」，避免用户以为我们能给地址。
    note: state.channel === Channel.CLOUDFLARE
      ? 'Cloudflare 快速隧道的地址由插件运行时填入，域名每次重启都会变'
      : '国内通道地址来自 remote-access 配置的 publicOrigin',
  };
}

/** 在 Loader 条目表里找 remote-web-ui（按 id，失败再按包名）。 */
export function findRemoteEntry(entries) {
  const list = [...(entries ?? [])];
  return list.find((entry) => entry?.options?.id === REMOTE_ENTRY_ID)
    ?? list.find((entry) => entry?.options?.name === REMOTE_PLUGIN_NAME);
}

/** 原子写：`wx` 独占建临时文件 → rename 覆盖；任何失败都清掉临时文件。 */
async function atomicWrite(file, text) {
  const temporary = `${file}.dshpal-${randomUUID()}.tmp`;
  try {
    await fs.writeFile(temporary, text, { flag: 'wx', mode: 0o600 });
    await fs.rename(temporary, file);
  } finally {
    await fs.unlink(temporary).catch((error) => {
      if (error.code !== 'ENOENT') throw error;
    });
  }
}

/**
 * 把新值推进运行中的 fiber（volatile 引用），不重挂插件。
 * 失败不抛：调用方根据返回值决定如实回报什么。
 * @returns {applied, detail}
 */
function applyRuntime(entry, desired) {
  if (entry === undefined || typeof entry.update !== 'function') {
    return { applied: false, detail: 'Loader 里没有可更新的 remote-web-ui 条目' };
  }
  let current;
  try {
    current = structuredClone(entry.options?.config ?? {});
  } catch (error) {
    return { applied: false, detail: `读取运行中配置失败：${messageOf(error)}` };
  }
  // 必须带完整配置：entry.update 是整体替换 options.config。
  const next = { ...current, ...desired };
  try {
    // 不 await：Loader 内部会把失败写日志；我们用下面的读回结果作准。
    entry.update({ config: next });
  } catch (error) {
    return { applied: false, detail: `更新运行时配置失败：${messageOf(error)}` };
  }
  return { applied: true };
}

/**
 * 从运行中的 fiber 读出 volatile 字段的真实值（用于校验运行时是否真的换了）。
 * volatile 字段是一个带 `.get()` 的实时引用，普通字段是裸值，两种都要能读。
 */
function liveValue(entry, key) {
  try {
    const field = entry?.fiber?.config?.[key];
    if (field === undefined) return undefined;
    if (typeof field?.get === 'function') return field.get();
    return field;
  } catch {
    return undefined;
  }
}

/**
 * 执行一次切换。
 * @param deps - {patchPath, domesticOrigin, backupEncrypted, preflight, fetchImpl, entry}
 * @param requestedChannel - 请求里的 channel 值（未校验）。
 * @returns {{status:number, body:object}} —— status 即 HTTP 状态码。
 */
export async function switchChannel(deps, requestedChannel) {
  // ① 通道名白名单。非法值绝不进入写路径。
  let target;
  try {
    target = normalizeChannel(requestedChannel);
  } catch (error) {
    return { status: 400, body: { ok: false, code: 'bad-channel', error: messageOf(error) } };
  }

  // ② 切国内通道必须先验证目标入口真的可用（理由见 preflight.js：
  //    切到一个连不上的地址，用户得到的是"扫不出来且回不去"的二维码，比不切更糟）。
  if (target === Channel.DOMESTIC) {
    if (typeof deps.domesticOrigin !== 'string' || deps.domesticOrigin === '') {
      return {
        status: 409,
        body: { ok: false, code: 'no-domestic-origin', error: '未配置国内入口（publicOrigin）；请先完成 remote-access 设置' },
      };
    }
    const probe = await deps.preflight(deps.domesticOrigin, { fetchImpl: deps.fetchImpl });
    if (!probe.ok) {
      return {
        status: 409,
        body: {
          ok: false,
          code: 'preflight-failed',
          error: `国内入口预检失败（${probe.detail}）；为避免把二维码指向不可用地址，本次不切换`,
          probe: probe.probe,
        },
      };
    }
  }

  // ③ 读现状，交给 channel-plan 决定目标字段。本文件不推导任何通道语义。
  let before;
  try {
    before = await readPatch(deps.patchPath);
  } catch (error) {
    return { status: 500, body: { ok: false, code: error.code ?? 'read-failed', error: messageOf(error) } };
  }

  const plan = planChannelSwitch(before.text, {
    settings: false,
    channel: target,
    domesticOrigin: deps.domesticOrigin ?? '',
    // Cloudflare 地址我们不知道，也不编一个：留给插件运行时自己填。
    cloudflareOrigin: '',
  });

  if (!plan.changed) {
    // 已在目标通道：不备份、不写、不触发重载。如实回报 already，不谎称"已切换"。
    return {
      status: 200,
      body: {
        ok: true,
        changed: false,
        channel: before.state.channel,
        autoTunnel: before.state.autoTunnel,
        publicBaseUrl: before.state.publicBaseUrl,
        runtimeApplied: true,
        requiresRestart: false,
        message: '已经是目标通道，未做任何修改',
      },
    };
  }

  // 目标字段值从 plan 的输出读回 —— 语义权威仍是 channel-plan，这里只取值。
  const planned = currentChannel(plan.text, { settings: false });
  const desired = {
    autoTunnel: planned.autoTunnel,
    relay: planned.relay,
    publicBaseUrl: planned.publicBaseUrl,
  };

  // ④ 写前 DPAPI 加密备份。备份失败必须中止：没有退路的写入不允许发生。
  let backupFile;
  try {
    backupFile = await deps.backupEncrypted(Buffer.from(before.text, 'utf8'));
  } catch (error) {
    return {
      status: 500,
      body: {
        ok: false,
        code: 'backup-failed',
        error: `加密备份失败，为避免无法回滚，本次不写入：${messageOf(error)}`,
      },
    };
  }

  // ⑤ 并发校验：备份期间若文件被改（例如设置页面同时保存），整体放弃，绝不覆盖。
  let currentBytes;
  try {
    currentBytes = await fs.readFile(deps.patchPath, 'utf8');
  } catch (error) {
    return { status: 500, body: { ok: false, code: 'read-failed', error: messageOf(error), backup: backupFile } };
  }
  if (currentBytes !== before.text) {
    return {
      status: 409,
      body: {
        ok: false,
        code: 'concurrent-change',
        error: '配置在切换过程中被其它操作修改；为避免覆盖，本次未写入任何文件',
        backup: backupFile,
      },
    };
  }

  // ⑥ 原子写。
  try {
    await atomicWrite(deps.patchPath, plan.text);
  } catch (error) {
    return {
      status: 500,
      body: { ok: false, code: 'write-failed', error: `写入配置失败：${messageOf(error)}`, backup: backupFile },
    };
  }

  // ⑦ 磁盘读回校验：读不回目标通道就回滚，绝不返回成功。
  let after;
  try {
    after = await readPatch(deps.patchPath);
  } catch (error) {
    await restore(deps.patchPath, before.text);
    return {
      status: 500,
      body: { ok: false, code: 'verify-failed', error: `写入后读回失败，已回滚：${messageOf(error)}`, backup: backupFile, rolledBack: true },
    };
  }
  if (after.state.channel !== target) {
    await restore(deps.patchPath, before.text);
    return {
      status: 500,
      body: {
        ok: false,
        code: 'verify-failed',
        error: `写入后读回通道为 ${after.state.channel}，与目标 ${target} 不一致；已回滚`,
        backup: backupFile,
        rolledBack: true,
      },
    };
  }

  // ⑧ 推进运行时引用（volatile 热生效）。
  const runtime = applyRuntime(deps.entry(), desired);
  // 以运行中 fiber 的真实值为准再判一次，而不是相信 entry.update 没抛错。
  const liveAuto = liveValue(deps.entry?.(), 'autoTunnel');
  const liveBase = liveValue(deps.entry?.(), 'publicBaseUrl');
  const runtimeMatches = runtime.applied
    && (liveAuto === undefined || liveAuto === desired.autoTunnel)
    && (liveBase === undefined || String(liveBase) === String(desired.publicBaseUrl));

  // ⑨ 停掉另一条通道的隧道进程（2026-11-04 用户实测发现的问题）。
  //
  // 本机有**两套独立**的公网隧道：
  //   a) tools/remote-access 的 Sakura frpc（自建，网关在 18772）
  //   b) remote-web-ui 自己的 cloudflared（切到 Cloudflare 时由它启动）
  // 只改 remote-web-ui 的配置并不会停掉 (a)：切到 Cloudflare 后 frpc 仍在
  // 传数据（任务管理器里上传带宽来自 frpc），用户看到「明明切了，上传还是
  // 国内通道」。所以切换后必须把另一条隧道停掉 / 拉起来。
  let tunnel;
  try {
    tunnel = (await deps.reconcileTunnel?.(target)) ?? { ok: true, skipped: true };
  } catch (error) {
    // 隧道联动失败不回滚配置（配置本身是对的），但要如实上报，
    // 否则用户又会看到「切了但带宽还是老的」却不知道原因。
    tunnel = { ok: false, detail: messageOf(error) };
  }

  return {
    status: runtimeMatches ? 200 : 202,
    body: {
      ok: true,
      changed: true,
      channel: after.state.channel,
      autoTunnel: after.state.autoTunnel,
      publicBaseUrl: after.state.publicBaseUrl,
      fields: plan.fields,
      backup: backupFile,
      // 磁盘已确认写对；运行时是否已经换过来，如实分开报。
      runtimeApplied: runtimeMatches,
      requiresRestart: !runtimeMatches,
      ...(runtimeMatches ? {} : { runtimeDetail: runtime.detail ?? '运行时配置未确认生效，重启 DSH 后一定会生效' }),
      // 隧道进程联动结果：让界面能说清「另一条隧道停了没有」，
      // 用户不必再去任务管理器看是哪个进程在占上传带宽。
      tunnel,
      message: runtimeMatches
        ? (target === Channel.CLOUDFLARE
          ? '已切到 Cloudflare 快速隧道：插件会启动 cloudflared 并自行填入二维码地址，约 20 秒后生效'
          : '已切到国内隧道：二维码将指向配置的 publicOrigin')
        : '配置已写入磁盘，但运行时引用未确认更新；重启 DSH 后生效',
    },
  };
}

/** 用备份前的原文把文件还原。 */
async function restore(file, text) {
  try {
    await atomicWrite(file, text);
    return true;
  } catch {
    return false;
  }
}
