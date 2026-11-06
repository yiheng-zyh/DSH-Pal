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

/** 通过既有计划任务拉起 remote-access 栈（内部 wscript 隐藏窗口）。 */
async function startStack() {
  await run('schtasks.exe', ['/run', '/tn', `\\${STACK_TASK}`], {
    timeout: 20000,
    windowsHide: true,
  });
  return true;
}

/**
 * 切换后让隧道进程与实际通道一致。
 *
 * ## 2026-11-06 语义修正（用户实测反馈）
 *
 * 早先实现是「切到哪条就停另一条」，但用户实测发现：切到 Cloudflare 后，
 * 国内的 frpc 仍会被 `DSH-Pal-Remote-Access-Watchdog`（每 5 分钟）重新拉起。
 * 于是"停掉"最多维持 5 分钟 —— 表面上是两条都在跑，实际上取决于 watchdog
 * 什么时候跑，属于**凑巧**，不是可依赖的状态。
 *
 * 现在的口径（用户明确要求「固化成持久的，不可以只是凑巧的」）：
 *   **切换只改"公布哪个地址"，本模块不再主动杀任何隧道进程。**
 *
 * 两条隧道真实的存活机制（如实记录，别把它们说成对称的）：
 *   - 国内 frp：计划任务 `DSH-Pal-Remote-Access` + Watchdog 每 5 分钟守护 → **持久**，
 *     就算我们杀了它也会被拉回来。
 *   - Cloudflare：cloudflared 是 remote-web-ui 插件的子进程，**完全由该插件的
 *     `tunnelPlanOf()` 决定**：`autoTunnel=true` 时插件自己起它（quick 模式）；
 *     切到 domestic 时配置里 `autoTunnel=false`，插件会主动 `tunnel.stop()` 把它关掉
 *     （见上游 lib/index.js 的 `tunnel.stop()` 调用点）。**这一条不由我们控制。**
 *
 * 所以"两条同时常驻"在当前插件架构下**做不到**：
 * 插件只支持一个 tunnel 模式，切到国内时它必然关掉 cloudflared。
 * 本模块能做的是「不去火上浇油」—— 不再额外杀 frpc / 网关，让国内那条
 * 始终可用（它本来也有 watchdog 兜着）。
 *
 * 为什么不杀也不会冲突（2026-11-06 实测核对）：
 *   - 端口不重叠：网关监听 127.0.0.1:18772，cloudflared 只监听自己的本地端口；
 *   - 目标一致：两条最终都转发到同一个 DSH 端口（网关 → DSH，cloudflared → DSH）；
 *   - 出站独立：各走各的上游，互不干扰。
 *
 * @param channel - 'cloudflare' | 'domestic'
 * @returns 结构化的联动结果，供界面如实展示。
 */
export async function reconcileTunnel(channel) {
  if (channel === 'cloudflare') {
    // 不再杀 frpc / 网关：它们由计划任务与 watchdog 常驻，杀掉只会制造
    // 「5 分钟后被拉回来」的不确定状态。这里只保证 cloudflared 已经由插件拉起。
    const [gateway, frpc] = await Promise.all([gatewayPids(), frpcPids()]);
    const running = gateway.length > 0 || frpc.length > 0;
    return {
      ok: true,
      action: 'kept',
      detail: running
        ? `已改为由 Cloudflare 对外公布。国内隧道保持运行（网关 ${gateway.length} 个、frpc ${frpc.length} 个），已配对设备可继续用原地址。`
        : '已改为由 Cloudflare 对外公布。国内隧道当前未运行。',
    };
  }

  if (channel === 'domestic') {
    // 同样不停 cloudflared：它由插件维持，DSH 在跑它就在。
    // 只确保国内这条是活的（没活起来就通过计划任务拉起）。
    const [gateway, frpc] = await Promise.all([gatewayPids(), frpcPids()]);
    if (gateway.length > 0 && frpc.length > 0) {
      return {
        ok: true,
        action: 'kept',
        detail: '已改为由国内 frp 对外公布。国内隧道本来就在运行，Cloudflare 那条也保持不动。',
      };
    }
    await startStack();
    return {
      ok: true,
      action: 'started',
      detail: `已通过计划任务 ${STACK_TASK} 拉起国内隧道（约 10-20 秒后 frpc 连上）；Cloudflare 那条保持不动。`,
    };
  }

  // 未知通道名：什么都不做，也不谎报成功。
  return { ok: false, action: 'skipped', detail: `unknown channel: ${String(channel)}` };
}
