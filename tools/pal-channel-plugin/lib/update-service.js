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
 *   2. 没有远程地址（本地开发仓）→ 拒绝并说明。
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

const execute = promisify(execFile);

/** 插件包根（lib/ 的上一级）。 */
export const PLUGIN_ROOT = fileURLToPath(new URL('..', import.meta.url));

/** 与插件共享代码、必须一起更新的目录（相对仓库根）。 */
const SHARED_PATHS = ['tools/remote-access'];

/** git 网络操作超时：国内拉 GitHub 可能慢，但不能无限等。 */
const GIT_TIMEOUT_MS = 90000;

/** 从 start 向上找含 .git 的目录，最多 8 层。 */
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

/** 把仓库内的相对路径统一成 git 认的斜杠形式。 */
function relative(repo, absolute) {
  return path.relative(repo, absolute).replace(/\\/g, '/');
}

/**
 * 应用更新。
 * @param options.executeImpl - 测试注入（替换 execFile）
 * @returns {{status:number, body:object}}
 */
export async function applyUpdate({ executeImpl = execute } = {}) {
  const repo = await findRepositoryRoot();
  if (!repo) {
    return done(409, {
      ok: false,
      code: 'not-a-repo',
      error: '当前插件目录不在 git 仓库里，无法自动更新。请按 README 重新下载新版。',
    });
  }

  // 有远程地址才谈得上"更新"；本地开发仓（无 remote）没有可拉取的源。
  let remote;
  try {
    remote = (await executeImpl('git', ['-C', repo, 'remote', 'get-url', 'origin'])).stdout.trim();
  } catch {
    return done(409, {
      ok: false,
      code: 'no-remote',
      error: '这个仓库没有配置远程地址，无法自动更新。',
    });
  }
  if (!remote) {
    return done(409, { ok: false, code: 'no-remote', error: '这个仓库没有配置远程地址，无法自动更新。' });
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