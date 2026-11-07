/**
 * 应用更新 —— 「检查 → 看公告 → 点更新 → 真正更新完」链条的最后一环。
 *
 * 为什么用 `git pull --ff-only` 而不是下载文件覆盖：
 * 本插件与 `tools/remote-access` **共享代码**（本插件 import 了它的 config.mjs /
 * direct.mjs / windows.mjs）。只覆盖插件目录会让「新版插件 + 旧版工具」拼在一起，
 * 直接加载失败，而且用户看到的是"更新成功"之后插件消失。整仓更新才是一致的。
 *
 * 为什么是 `--ff-only`：
 * 它只做快进合并，本地有提交分叉时**直接失败**而不是偷偷合并。更新功能最怕的
 * 不是"更新失败"，而是"把用户没提交的代码吞掉"。所以再加两道闸：
 *   1. 插件目录与 remote-access 有未提交改动 → 直接拒绝，告诉用户哪些文件；
 *   2. 没有远程地址（本地开发仓）→ 不再是「报错」，而是如实判定
 *      本地版本是否已经 >= 远端最新（见 resolveLocalOnly）。
 *
 * 更新只改工作区文件，不重启任何进程：DSH 里的旧代码继续跑，用户重启 DSH 后才生效。
 * 这是刻意的 —— 在插件自己的请求里重启 DSH，请求会断在半路，用户看不到结果。
 *
 * @module dsh-pal-channel/update-service
 */
import path from 'node:path';
import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { checkUpdate, compareVersions, localVersion } from './update.js';

const execute = promisify(execFile);

/** 插件包根（lib/ 的上一级）。 */
export const PLUGIN_ROOT = fileURLToPath(new URL('..', import.meta.url));

/** 与插件共享代码、必须一起更新的目录（相对仓库根）。 */
const SHARED_PATHS = ['tools/remote-access'];

/** git 网络操作超时：国内拉 GitHub 可能慢，但不能无限等。 */
const GIT_TIMEOUT_MS = 90000;

/**
 * 从 start 向上找含 .git 的目录，最多 8 层。
 */
export async function findRepositoryRoot(start = PLUGIN_ROOT) {
  let current = path.resolve(start);
  for (let i = 0; i < 8; i += 1) {
    try {
      const fs = await import('node:fs/promises');
      await fs.access(path.join(current, '.git'));
      return current;
    } catch {
      // 继续向上。
    }
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return null;
}

/**
 * 没有远程地址时的结论：本地版本 vs 远端最新。
 *
 * 为什么不能直接报错：
 * 作者本机是「link: 指向私有开发区」的装法（`tools/pal-channel-plugin` 就在源码库里），
 * 那个库**故意不配 remote**（R-03：私有库只出不进，不允许 pull 回来覆盖）。
 * 所以「点立即更新 → 红字 无法自动更新」对开发者是**必然且正常**的，
 * 不是故障，更不是发布没弄好。如实说清楚，别让人去查错误的方向。
 *
 * 判定分三种，都是 200：本地开发装法不是"失败"。
 *   - local-ahead：本地已等于或新于远端 → 根本不需要更新（作者本机的常态）；
 *   - local-behind：本地比远端旧 → 需要手动同步（源码库落后于公开发布）；
 *   - unknown：版本读不到 → 如实说不确定，给手动路径。
 */
async function resolveLocalOnly(repo, { checkUpdateImpl = checkUpdate } = {}) {
  const local = await localVersion();
  const check = await checkUpdateImpl({ local: local ?? undefined }).catch(() => ({ ok: false }));
  const latest = check.ok ? check.latest : null;

  const detail = {
    repository: repo,
    local,
    latest,
    localOnly: true,
    // 手动路径永远给出：自动判定不确定时，用户至少知道下一步干什么。
    manual: '源码在本地开发区时不需要走自动更新；要装公开发布版请按 README 从 GitHub 重新下载。',
  };

  if (!latest || !local) {
    return done(200, {
      ok: true,
      code: 'no-remote',
      changed: false,
      ...detail,
      note: '当前插件来自本地开发区（没有配置远程地址），无法自动更新；本地版本与远端版本未能比对，请按 README 处理。',
    });
  }

  const diff = compareVersions(local, latest);
  if (diff >= 0) {
    return done(200, {
      ok: true,
      code: 'local-ahead',
      changed: false,
      ...detail,
      note: `当前是本地开发装法：本地 ${local}，公开发布 ${latest}，本地已是最新或更新，无需更新。`,
    });
  }
  return done(200, {
    ok: true,
    code: 'local-behind',
    changed: false,
    ...detail,
    note: `本地开发区（${local}）比公开发布（${latest}）旧，自动更新不会去覆盖你的源码库；请先同步公开仓到本地。`,
  });
}

/** 把仓库内的相对路径统一成 git 认的斜杠形式。 */
function relative(repo, absolute) {
  return path.relative(repo, absolute).replace(/\\/g, '/');
}

/**
 * 应用更新。
 * @param options.executeImpl - 测试注入（替换 execFile）
 * @param options.checkUpdateImpl - 测试注入（替换 checkUpdate，供无 remote 分支判定版本）
 * @returns {{status:number, body:object}}
 */
export async function applyUpdate({ executeImpl = execute, checkUpdateImpl = checkUpdate } = {}) {
  const repo = await findRepositoryRoot();
  if (!repo) {
    return done(409, {
      ok: false,
      code: 'not-a-repo',
      error: '当前插件目录不在 git 仓库里，无法自动更新。请按 README 重新下载新版。',
    });
  }

  // 有远程地址才谈得上"更新"。没有 remote 时不再当错误处理 —— 作者本机就是
  // link: 指向私有开发区（故意不配 remote，见 R-03），那是正常装法。
  let remote;
  try {
    remote = (await executeImpl('git', ['-C', repo, 'remote', 'get-url', 'origin'])).stdout.trim();
  } catch {
    return resolveLocalOnly(repo, { checkUpdateImpl });
  }
  if (!remote) {
    return resolveLocalOnly(repo, { checkUpdateImpl });
  }

  // 只看与插件相关的路径：App 代码的本地改动不该阻止插件更新。
  const scope = [relative(repo, PLUGIN_ROOT), ...SHARED_PATHS];
  let dirty = '';
  try {
    dirty = (await executeImpl('git', ['-C', repo, 'status', '--porcelain', '--', ...scope])).stdout.trim();
  } catch (error) {
    return done(500, { ok: false, code: 'status-failed', error: describe(error) });
  }
  if (dirty) {
    return done(409, {
      ok: false,
      code: 'dirty-worktree',
      error: '插件相关目录有未提交的改动，为免覆盖你的代码，本次不更新。请先提交或暂存后重试。',
      files: dirty.split(/\r?\n/).slice(0, 12),
    });
  }

  const before = await revParse(executeImpl, repo);
  let pull;
  try {
    pull = await executeImpl('git', ['-C', repo, 'pull', '--ff-only'], { timeout: GIT_TIMEOUT_MS });
  } catch (error) {
    // --ff-only 在本地分叉时拒绝；这种失败是"保护"，不是"故障"，如实说明。
    return done(409, {
      ok: false,
      code: 'pull-failed',
      error: `无法快进更新（可能有本地提交与远端分叉，或网络不可达）：${describe(error).slice(0, 300)}`,
    });
  }
  const after = await revParse(executeImpl, repo);

  return done(200, {
    ok: true,
    repository: repo,
    before,
    after,
    changed: before !== after,
    detail: String(pull?.stdout ?? '').trim().slice(0, 400),
    // 关键：更新已完成但**当前进程仍是旧代码**，必须重启 DSH 才生效。
    note: before === after
      ? '已是最新，没有文件变化；无需重启。'
      : '已更新到最新代码。请重启 DSH 后生效（旧代码仍在当前进程里运行）。',
  });
}

/** 读短 commit；失败返回 null（不影响主流程结论）。 */
async function revParse(executeImpl, repo) {
  try {
    return (await executeImpl('git', ['-C', repo, 'rev-parse', '--short', 'HEAD'])).stdout.trim();
  } catch {
    return null;
  }
}

function describe(error) {
  if (!error) return 'unknown error';
  const stderr = typeof error.stderr === 'string' ? error.stderr.trim() : '';
  return (stderr || error.message || String(error)).slice(0, 600);
}

function done(status, body) {
  return { status, body };
}