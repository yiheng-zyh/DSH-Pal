#!/usr/bin/env node
/**
 * 通过 USB 把配对链接直接送进手机 App，跳过扫码。
 *
 * ## 为什么需要它
 *
 * 手机扫码要求人站在电脑前对着屏幕。当操作者不在设备旁（远程协作、
 * 手机放在别处、屏幕不可见）时，扫码这条路走不通。
 *
 * 而配对链接本质上只是一个 URL —— 只要能让 App 收到它，配对这个动作
 * 就不需要任何"扫"的环节。本项目 App 已经支持 `dshmobile://open?url=...`
 * 深链接（见 AndroidManifest.xml 的 intent-filter 与
 * LaunchRouting.apply 的解析逻辑），所以可以用 adb 直接投递。
 *
 * ## 做了哪三件事
 *
 *   1. 向本机 DSH 的 `/api/pair/issue` 要一张一次性配对链接
 *   2. 包成 `dshmobile://open?url=<链接>` 深链接
 *   3. 用 `am start -p <包名>` 投递，`-p` 是为了绕开系统的意图选择器
 *
 * ## 用法
 *
 *   node pair-via-usb.mjs                          # desktop 通道，默认端口自动探测
 *   node pair-via-usb.mjs --port 65188             # 指定 DSH 端口
 *   node pair-via-usb.mjs --package com.dsh.mobile # 指定包名
 *   node pair-via-usb.mjs --dry-run                # 只打印链接，不投递
 *
 * ## 安全边界
 *
 * - 配对链接是**一次性**的，30 秒过期，用掉即焚
 * - 只走 adb（USB 或已授权的无线调试），不经过任何网络
 * - 不打印完整 token（默认脱敏），`--show-token` 才显示
 */

import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// ---------- 参数解析 ----------
const argv = process.argv.slice(2);
function flag(name) { return argv.includes(name); }
function value(name, fallback) {
  const i = argv.indexOf(name);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
}

const ADB = value('--adb', 'D:\\AndroidSdk\\platform-tools\\adb.exe');
const PKG = value('--package', 'com.dsh.mobile');
const EXPLICIT_PORT = value('--port', null);
const DRY = flag('--dry-run');
const SHOW_TOKEN = flag('--show-token');

const log = (...a) => console.log(...a);
function die(msg) { console.error('错误: ' + msg); process.exit(1); }

// ---------- 1. 找 adb ----------
function adbPath() {
  if (fs.existsSync(ADB)) return ADB;
  // 退化到 PATH 里的 adb
  try { execFileSync('adb', ['version'], {stdio: 'ignore'}); return 'adb'; } catch { /* noop */ }
  die(`找不到 adb。用 --adb <路径> 指定，或确认 ${ADB} 存在`);
}

// ---------- 2. 找 DSH 端口 ----------
/**
 * 找本机正在跑的 DSH 端口。
 *
 * 不做全端口扫描 —— 只问两个地方：
 *   a) 用户显式给的 --port
 *   b) 从 DSH 进程自己持有的监听端口里挑
 *
 * 选中标准：`/api/pair/status` 能回一份合法配对状态。
 * 这个判据与 tools/remote-access/discovery.mjs 保持一致，不另造一套。
 */
async function findDshPort() {
  if (EXPLICIT_PORT) {
    if (!(await isDshPort(Number(EXPLICIT_PORT)))) die(`端口 ${EXPLICIT_PORT} 不是可用的 DSH 服务`);
    return Number(EXPLICIT_PORT);
  }
  for (const p of candidatePorts()) {
    if (await isDshPort(p)) return p;
  }
  die('没找到正在运行的 DSH。请先打开 DSH 桌面端，或用 --port 指定端口');
}

function candidatePorts() {
  if (process.platform !== 'win32') return [];
  const cmd = "$owners=@(Get-CimInstance Win32_Process -Filter \"Name='DeepSeek Harness.exe'\" | Select-Object -ExpandProperty ProcessId);" +
    "if($owners.Count){Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |" +
    "Where-Object {$owners -contains $_.OwningProcess -and $_.LocalAddress -in @('127.0.0.1','::1')} |" +
    "Select-Object -ExpandProperty LocalPort -Unique | ConvertTo-Json -Compress}";
  try {
    const out = execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', cmd],
      {windowsHide: true, timeout: 12000, encoding: 'utf8'}).trim();
    if (!out) return [];
    const v = JSON.parse(out);
    return (Array.isArray(v) ? v : [v]).filter(x => Number.isInteger(x) && x >= 1024 && x <= 65535);
  } catch { return []; }
}

async function isDshPort(port) {
  if (!Number.isInteger(port) || port < 1024 || port > 65535) return false;
  try {
    const res = await fetch(`http://127.0.0.1:${port}/api/pair/status`, {signal: AbortSignal.timeout(3000)});
    if (!res.ok) return false;
    const j = await res.json();
    return j && j.ok === true && typeof j.paired === 'boolean' && typeof j.phase === 'string';
  } catch { return false; }
}

// ---------- 3. 签发配对链接 ----------
async function issuePairLink(port) {
  const res = await fetch(`http://127.0.0.1:${port}/api/pair/issue`, {
    method: 'POST',
    headers: {'content-type': 'application/json'},
    body: '{}',
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) die(`/api/pair/issue 返回 HTTP ${res.status}`);
  const j = await res.json();
  if (!j || !j.url) die('/api/pair/issue 没有返回 url');
  return j;
}

// ---------- 4. 投递深链接 ----------
function deliver(adb, deepLink) {
  // -p 指定包名：不带它系统会弹出意图选择器（ResolverActivity），App 收不到。
  // 目标 Activity 已在前台时 adb 会往 stderr 打一条 "Activity not started ...
  // intent has been delivered to currently running top-most instance" ——
  // 那是投递成功的另一种说法，不是错误，所以这里只取 stdout。
  try {
    return execFileSync(adb, ['shell', 'am', 'start',
      '-a', 'android.intent.action.VIEW',
      '-d', deepLink,
      '-p', PKG,
    ], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']}).trim();
  } catch (e) {
    // 即便退出码非 0，intent 也可能已经送达，交给后续的在线数轮询判定。
    return String(e.stdout || e.message || '').trim();
  }
}

// ---------- 主流程 ----------
(async () => {
  const adb = adbPath();

  // 确认设备在线
  let devices = '';
  try { devices = execFileSync(adb, ['devices'], {encoding: 'utf8'}); } catch (e) { die('adb devices 失败: ' + e.message); }
  const online = devices.split('\n').slice(1).filter(l => /\sdevice\s*$/.test(l.trim()) || /\sdevice\b/.test(l));
  if (online.length === 0) die('没有已授权的设备。请确认手机已连接并允许 USB 调试');

  const port = await findDshPort();
  log(`DSH 端口: ${port}`);

  const grant = await issuePairLink(port);
  const shown = SHOW_TOKEN ? grant.url : grant.url.replace(/pair=[^&]+/, 'pair=<已脱敏>');
  log(`配对链接: ${shown}`);
  const expires = new Date(grant.expiresAt).toLocaleTimeString();
  log(`有效期至: ${expires}（一次性，用掉即焚）`);

  const deepLink = `dshmobile://open?url=${encodeURIComponent(grant.url)}`;
  if (DRY) {
    log('\n--dry-run：只打印，不投递');
    // 与上面一致地脱敏：深链接里也带着 token，不能明文留在终端回滚缓冲里。
    log(SHOW_TOKEN ? deepLink : deepLink.replace(/pair%3D[a-f0-9]+/i, 'pair%3D%3Credacted%3E'));
    return;
  }

  log(`\n投递到 ${PKG} ...`);
  const out = deliver(adb, deepLink);
  log(out || '（已投递）');

  // 投递后轮询配对状态，确认真的连上
  log('\n等待 App 建立连接 ...');
  const before = await statusOf(port);
  for (let i = 0; i < 10; i++) {
    await new Promise(r => setTimeout(r, 1500));
    const now = await statusOf(port);
    if (now && before && now.onlineCount > before.onlineCount) {
      log(`✅ 配对成功：在线设备 ${before.onlineCount} -> ${now.onlineCount}`);
      log(`   phase=${now.phase}`);
      return;
    }
  }
  const final = await statusOf(port);
  log(`未能确认在线数增加。当前: phase=${final?.phase} online=${final?.onlineCount}`);
  log('链接可能已过期，或 App 未处理该深链接。可重跑本脚本。');
})();

async function statusOf(port) {
  try {
    const res = await fetch(`http://127.0.0.1:${port}/api/pair/status`, {signal: AbortSignal.timeout(3000)});
    if (!res.ok) return null;
    return await res.json();
  } catch { return null; }
}
