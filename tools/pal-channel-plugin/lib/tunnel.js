/**
 * 隧道进程联动 —— 切换通道时同步启停「另一套」隧道。
 *
 * 为什么需要这个模块（2026-11-04 用户实测发现的问题）：
 *
 * 本机同时存在**两套彼此独立**的公网隧道：
 *
 *   a) `tools/remote-access`  —— 自建：run.mjs 网关（绑 18772）+ Sakura frpc。
 *      这是 DSH Pal App 一直用的那条，固定域名 <你的-frp-域名>:<端口>。
 *   b) `@linxin666/dsh-remote-web-ui` —— 插件自带：切到 autoTunnel=true 时
 *      由它启动 cloudflared，域名是 trycloudflare 的临时地址。
 *
 * 切换插件只改 (b) 的配置，**不会**停掉 (a)。结果是用户切到 Cloudflare 之后，
 * frpc 仍在后台传数据（任务管理器里能看到上传带宽来自 frpc），
 * 看起来就像「切了没用」。
 *
 * 本模块负责：
 *   切到 CLOUDFLARE  → 停掉 run.mjs 与 frpc（省带宽、避免两条路同时占上行）
 *   切到 DOMESTIC    → 通过计划任务把 run.mjs + frpc 重新拉起来
 *
 * 边界与安全：
 *   - 只操作**我们能识别为自己启动的**进程（按可执行文件名 / 命令行特征匹配），
 *     绝不按 PID 乱杀。
 *   - 拉起一律走既有计划任务 `DSH-Pal-Remote-Access`（它内部用 wscript 隐藏窗口），
 *     不自己 spawn 控制台进程 —— 用户有「不许弹任何控制台窗口」的硬性要求。
 *   - 任何一步失败都**不抛**给上层去回滚配置：配置本身是对的，
 *     只是进程没停干净；如实返回 detail 让界面显示即可。
 *
 * @module dsh-pal-channel/tunnel
 */

import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);

/** 计划任务名：remote-access 栈的既有入口（内部用 wscript 隐藏窗口拉起）。 */
export const STACK_TASK = 'DSH-Pal-Remote-Access';

/**
 * 列出匹配的进程 PID。
 *
 * 用 PowerShell 的 CIM 查询而不是 `tasklist`：需要按命令行特征匹配 run.mjs，
 * tasklist 拿不到命令行。命令本身在隐藏的 PowerShell 里跑（无窗口）。
 */
async function findPids(filter) {
  // 注意：filter 本身可能以 `| Where-Object {...}` 结尾，
  // 所以这里只能拼一个空格再接管道，不能加分号（加了会变成 `}; |` 语法错误）。
  const script = `$ErrorActionPreference='SilentlyContinue'; ${filter} | ForEach-Object { $_.ProcessId }`;
  const { stdout } = await run(
    'powershell.exe',
    ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-Command', script],
    { timeout: 20000, windowsHide: true },
  );
  return String(stdout)
    .split(/\r?\n/)
    .map((line) => Number(line.trim()))
    .filter((value) => Number.isInteger(value) && value > 0);
}

/** 找 frpc（Sakura 客户端）。按可执行文件名前缀匹配，避免误杀其他 frp。 */
function frpcPids() {
  return findPids(
    "Get-CimInstance Win32_Process -Filter \"Name LIKE 'frpc%'\"",
  );
}

/** 找 run.mjs 网关（node 进程 + 命令行含 remote-access\\run.mjs）。 */
function gatewayPids() {
  return findPids(
    "Get-CimInstance Win32_Process -Filter \"Name='node.exe'\" | Where-Object { $_.CommandLine -like '*remote-access*run.mjs*' }",
  );
}

/** 停掉一组 PID；已经退出的跳过。 */
async function killPids(pids) {
  if (pids.length === 0) return { killed: [], failed: [] };
  const killed = [];
  const failed = [];
  for (const pid of pids) {
    try {
      await run('taskkill.exe', ['/PID', String(pid), '/F'], {
        timeout: 10000,
        windowsHide: true,
      });
      killed.push(pid);
    } catch {
      // 进程可能已经自己退了，这不算失败。
      failed.push(pid);
    }
  }
  return { killed, failed };
}

/** 通过既有计划任务拉起 remote-access 栈（内部 wscript 隐藏窗口）。 */
async function startStack() {
  await run('schtasks.exe', ['/run', '/tn', `\\${STACK_TASK}`], {
    timeout: 20000,
    windowsHide: true,
  });
  return true;
}

/**
 * 停掉 remote-web-ui 启动的 cloudflared。
 *
 * 为什么由我们来停：cloudflared 是**上游插件的子进程**，停掉父 fiber 不一定会
 * 连带杀掉它（上游未必在 dispose 里 kill）。切回国内后它还连着 Cloudflare，
 * 继续占上行带宽，用户会看到「切回来了但上传仍是 Cloudflare」。
 *
 * 只按可执行名 cloudflared 匹配 —— 本机只有这一个 tunnel 客户端，
 * 且它的存在与否完全由通道选择决定，杀掉是安全的。
 * @returns 实际停掉的进程数。
 */
async function stopCloudflared() {
  const pids = await findPids("Get-CimInstance Win32_Process -Filter \"Name='cloudflared.exe'\"");
  if (pids.length === 0) return 0;
  const { killed } = await killPids(pids);
  return killed.length;
}

/**
 * 切换后让隧道进程与实际通道一致。
 *
 * @param channel - 'cloudflare' | 'domestic'
 * @returns 结构化的联动结果，供界面如实展示。
 */
export async function reconcileTunnel(channel) {
  if (channel === 'cloudflare') {
    const [gateway, frpc] = await Promise.all([gatewayPids(), frpcPids()]);
    const all = [...new Set([...gateway, ...frpc])];
    if (all.length === 0) {
      return { ok: true, action: 'none', detail: '国内隧道本来就没在运行' };
    }
    const { killed } = await killPids(all);
    return {
      ok: true,
      action: 'stopped',
      stopped: killed.length,
      detail:
        `已停掉国内隧道（网关 ${gateway.length} 个、frpc ${frpc.length} 个进程），` +
        '现在只有 Cloudflare 在跑',
    };
  }

  if (channel === 'domestic') {
    // 切回国内时也要把 Cloudflare 停掉，否则它继续占着上行带宽
    // （2026-11-04 用户实测：切到 Cloudflare 后看到上传带宽仍来自 frpc，
    // 反过来切回国内时 cloudflared 同样会继续跑）。
    const stoppedCloudflared = await stopCloudflared();

    // 已经在跑就不重复拉起（计划任务拉起是幂等的，但少一次是一次的干净）。
    const [gateway, frpc] = await Promise.all([gatewayPids(), frpcPids()]);
    const cfNote = stoppedCloudflared > 0 ? `；已停掉 ${stoppedCloudflared} 个 cloudflared 进程` : '';
    if (gateway.length > 0 && frpc.length > 0) {
      return { ok: true, action: 'none', detail: `国内隧道已在运行${cfNote}` };
    }
    await startStack();
    return {
      ok: true,
      action: 'started',
      detail: `已通过计划任务 ${STACK_TASK} 拉起国内隧道（约 10-20 秒后 frpc 连上）${cfNote}`,
    };
  }

  // 未知通道名：什么都不做，也不谎报成功。
  return { ok: false, action: 'skipped', detail: `unknown channel: ${String(channel)}` };
}
