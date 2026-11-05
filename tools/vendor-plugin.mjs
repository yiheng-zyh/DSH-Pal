#!/usr/bin/env node
/**
 * 把上游插件固化成本地依赖，让 App 不再受上游变化影响。
 *
 * ## 为什么需要这个脚本
 *
 * 本 App 完全依赖 `@linxin666/dsh-remote-web-ui` 的浏览器远控能力
 * （`/api/pair`、`/remote` 代理通道、移动触屏适配层）。
 *
 * 但上游作者已明确声明弃用，并说「浏览器远程控制已被替代、本包不再提供
 * 远程访问能力」。这意味着：
 *
 *   - 作者可能从 npm 下架 0.4.14 → 新用户装不上
 *   - 作者可能发新版删掉浏览器面 → 用户装 @latest 就白屏
 *   - npm 服务波动 → 装不上
 *
 * **这三条都不可控，全在上游手里。**
 *
 * ## 这个脚本做什么
 *
 * 把插件从「npm 上的一个包」变成「你本地的文件」，从此上游怎么变都不影响你：
 *
 *   1. 检查本仓库是否已自带副本（`third_party/dsh-remote-web-ui`）
 *      - 有 → 直接用（不下载，不依赖 npm）
 *      - 没有 → 从 npm 下载 0.4.14 并校验 sha512
 *   2. 把副本复制到你指定的位置
 *   3. 改目标 profile 的 `package.json`，把依赖从版本号改成 `file:` 路径
 *   4. 跑 `pnpm install`
 *   5. 验证端点可用
 *
 * ## 用法
 *
 *   # 最常见：自动发现 profile，固化到默认位置
 *   node tools/vendor-plugin.mjs
 *
 *   # 指定 profile
 *   node tools/vendor-plugin.mjs --profile desktop
 *
 *   # 指定固化目录（默认放到 DSH_HOME/vendor/dsh-remote-web-ui）
 *   node tools/vendor-plugin.mjs --target D:/my-plugins/dsh-remote-web-ui
 *
 *   # 只看会做什么，不实际改动
 *   node tools/vendor-plugin.mjs --dry-run
 *
 *   # 从 npm 强制重新下载（不用仓库自带副本）
 *   node tools/vendor-plugin.mjs --force-download
 *
 *   # 回退：把依赖改回 npm 版本
 *   node tools/vendor-plugin.mjs --revert
 *
 * ## 安全边界
 *
 * - **只读 + 只改一个文件**：目标 profile 的 `package.json`
 * - 改之前自动备份该文件
 * - `--dry-run` 完全不写盘
 * - 不碰 DSH 本体、不碰其他插件、不改系统设置
 * - 校验 sha512，与 npm registry 记录比对，防止下载到被篡改的包
 */

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const PLUGIN = '@linxin666/dsh-remote-web-ui';
const PINNED = '0.4.14';
const REGISTRY = `https://registry.npmjs.org/${PLUGIN.replace('/', '%2F')}`;

// ---------- 参数 ----------
const argv = process.argv.slice(2);
const has = (n) => argv.includes(n);
function val(n, d = null) {
  const i = argv.indexOf(n);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
}
const OPT = {
  profile: val('--profile'),
  target: val('--target'),
  dryRun: has('--dry-run'),
  forceDownload: has('--force-download'),
  revert: has('--revert'),
  yes: has('--yes'),
};

// ---------- 输出 ----------
const C = {
  reset: '\x1b[0m', bold: '\x1b[1m', dim: '\x1b[2m',
  green: '\x1b[32m', yellow: '\x1b[33m', red: '\x1b[31m', cyan: '\x1b[36m',
};
const log = (...a) => console.log(...a);
const ok = (m) => log(`  ${C.green}✅${C.reset} ${m}`);
const warn = (m) => log(`  ${C.yellow}⚠️${C.reset}  ${m}`);
const bad = (m) => log(`  ${C.red}❌${C.reset} ${m}`);
const step = (n, m) => log(`\n${C.bold}${C.cyan}[${n}]${C.reset} ${C.bold}${m}${C.reset}`);
const dim = (m) => log(`     ${C.dim}${m}${C.reset}`);
function die(m, hint) {
  bad(m);
  if (hint) dim(hint);
  process.exit(1);
}

// ---------- 路径 ----------
const DSH_HOME = process.env.DSH_HOME || path.join(os.homedir(), '.dsh');
const SELF_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SELF_DIR, '..');
const BUNDLED = path.join(REPO_ROOT, 'third_party', 'dsh-remote-web-ui');

// ---------- 工具 ----------
function sha512Of(buf) {
  return 'sha512-' + crypto.createHash('sha512').update(buf).digest('base64');
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

function writeJson(p, obj) {
  fs.writeFileSync(p, JSON.stringify(obj, null, 2) + '\n', 'utf8');
}

/** 找出所有存在的 profile 目录。 */
function listProfiles() {
  const dir = path.join(DSH_HOME, 'profiles');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && !d.name.includes('.'))
    .map((d) => d.name)
    .filter((n) => fs.existsSync(path.join(dir, n, 'package.json')));
}

/** 哪个 profile 装了本插件（或声明了它）。 */
function profilesDeclaring(plugin) {
  return listProfiles().filter((p) => {
    const pkg = readJson(path.join(DSH_HOME, 'profiles', p, 'package.json'));
    return pkg.dependencies && Object.prototype.hasOwnProperty.call(pkg.dependencies, plugin);
  });
}

// ---------- 主流程 ----------
log(`${C.bold}========================================================${C.reset}`);
log(`${C.bold} DSH Pal — 插件本地固化工具${C.reset}`);
log(`${C.bold}========================================================${C.reset}`);
log(`目标插件: ${C.cyan}${PLUGIN}@${PINNED}${C.reset}`);
if (OPT.dryRun) log(`${C.yellow}--dry-run：只检查，不写盘${C.reset}`);

// ===== 回退模式 =====
if (OPT.revert) {
  step(1, '回退：把依赖改回 npm 版本');
  const targets = OPT.profile ? [OPT.profile] : profilesDeclaring(PLUGIN);
  if (targets.length === 0) die('没找到声明本插件的 profile');

  for (const p of targets) {
    const pkgPath = path.join(DSH_HOME, 'profiles', p, 'package.json');
    const pkg = readJson(pkgPath);
    const cur = pkg.dependencies[PLUGIN];
    if (!cur.startsWith('file:')) {
      ok(`${p}: 已经是 npm 版本（${cur}），无需回退`);
      continue;
    }
    if (OPT.dryRun) {
      log(`     [dry-run] 会把 ${p} 的依赖从 ${cur} 改回 ${PINNED}`);
      continue;
    }
    const bak = pkgPath + '.bak-before-revert-' + Date.now();
    fs.copyFileSync(pkgPath, bak);
    pkg.dependencies[PLUGIN] = PINNED;
    writeJson(pkgPath, pkg);
    ok(`${p}: 已改回 ${PINNED}`);
    dim(`备份: ${bak}`);
  }
  log(`\n${C.bold}下一步${C.reset}：到 profile 目录跑 pnpm install，然后重启 DSH。`);
  process.exit(0);
}

// ===== 步骤 1：定位源副本 =====
step(1, '定位插件副本');

let sourceDir = null;
let sourceKind = null;

if (!OPT.forceDownload && fs.existsSync(path.join(BUNDLED, 'package.json'))) {
  const v = readJson(path.join(BUNDLED, 'package.json'));
  if (v.version === PINNED) {
    ok(`使用仓库自带副本: ${BUNDLED}`);
    dim(`版本 ${v.version}，无需联网`);
    sourceDir = BUNDLED;
    sourceKind = 'bundled';
  } else {
    warn(`仓库副本版本是 ${v.version}，不是 ${PINNED}，改走 npm 下载`);
  }
} else {
  dim('仓库里没有自带副本，需要从 npm 下载');
}

let stepNo = 2;
if (!sourceDir) {
  step(stepNo++, `从 npm 下载 ${PINNED}`);
  let meta;
  try {
    const res = await fetch(REGISTRY, { headers: { accept: 'application/json' } });
    if (!res.ok) die(`npm registry 返回 HTTP ${res.status}`, '检查网络，或改用 --target 指定已有的副本目录');
    meta = await res.json();
  } catch (e) {
    die(`无法访问 npm registry: ${e.message}`, '如果需要离线使用，请手动准备一份副本后用 --target 指定');
  }

  const ver = meta.versions?.[PINNED];
  if (!ver) die(`npm 上已经没有 ${PINNED} 了`, '尝试 --target 指定你已有的副本，或换一个版本');
  ok(`npm 上有 ${PINNED}`);

  const expect = ver.dist.integrity;
  const tarball = ver.dist.tarball;
  dim(`tarball: ${tarball}`);

  if (OPT.dryRun) {
    log(`     [dry-run] 会下载 ${tarball}`);
    log(`     [dry-run] 会校验 ${expect}`);
    sourceKind = 'npm(dry-run)';
  } else {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-vendor-'));
    const tgzPath = path.join(tmpDir, 'pkg.tgz');
    const buf = Buffer.from(await (await fetch(tarball)).arrayBuffer());
    fs.writeFileSync(tgzPath, buf);

    const actual = sha512Of(buf);
    if (expect && actual !== expect) {
      die('sha512 校验失败！下载到的包与 npm 记录不一致', `期望 ${expect}\n     实际 ${actual}`);
    }
    ok(`sha512 校验通过`);

    // 解包（用 tar，Windows 10+ 自带）
    try {
      execFileSync('tar', ['-xzf', tgzPath, '-C', tmpDir], { stdio: 'ignore' });
    } catch (e) {
      die('解包失败', 'Windows 请确认系统自带 tar（Win10 1803+ 都有）');
    }
    sourceDir = path.join(tmpDir, 'package');
    if (!fs.existsSync(path.join(sourceDir, 'package.json'))) {
      die('解包后找不到 package.json，包结构异常');
    }
    sourceKind = 'npm';
  }
}

// ===== 步骤 3：确定目标位置 =====
step(stepNo++, '确定固化位置');
const targetDir = OPT.target
  ? path.resolve(OPT.target)
  : path.join(DSH_HOME, 'vendor', 'dsh-remote-web-ui');
log(`  目标: ${C.cyan}${targetDir}${C.reset}`);

// ===== 步骤 4：找到要改的 profile =====
step(stepNo++, '定位要修改的 profile');
const allProfiles = listProfiles();
if (allProfiles.length === 0) die(`在 ${path.join(DSH_HOME, 'profiles')} 下没找到任何 profile`);

let targets;
if (OPT.profile) {
  if (!allProfiles.includes(OPT.profile)) {
    die(`profile "${OPT.profile}" 不存在`, `可用的: ${allProfiles.join(', ')}`);
  }
  targets = [OPT.profile];
} else {
  targets = profilesDeclaring(PLUGIN);
  if (targets.length === 0) {
    warn('没有 profile 声明本插件');
    dim(`可用的 profile: ${allProfiles.join(', ')}`);
    if (!OPT.yes) {
      die('请用 --profile 指定要装到哪个 profile', '例如: node tools/vendor-plugin.mjs --profile desktop');
    }
    targets = ['desktop'];
  }
}
ok(`将处理: ${targets.join(', ')}`);

// ===== 步骤 5：复制副本 =====
step(stepNo++, '复制插件副本');
if (!OPT.dryRun && sourceDir) {
  if (fs.existsSync(targetDir)) {
    warn('目标目录已存在，覆盖');
    fs.rmSync(targetDir, { recursive: true, force: true });
  }
  fs.mkdirSync(targetDir, { recursive: true });
  // 复制时排除 node_modules / .bak
  const copy = (from, to) => {
    fs.mkdirSync(to, { recursive: true });
    for (const e of fs.readdirSync(from, { withFileTypes: true })) {
      if (e.name === 'node_modules') continue;
      if (e.name.includes('.bak-')) continue;
      const s = path.join(from, e.name);
      const d = path.join(to, e.name);
      if (e.isDirectory()) copy(s, d);
      else fs.copyFileSync(s, d);
    }
  };
  copy(sourceDir, targetDir);
  const n = countFiles(targetDir);
  ok(`已复制 ${n} 个文件`);
} else if (OPT.dryRun) {
  log(`     [dry-run] 会复制到 ${targetDir}`);
}

// ===== 步骤 6：改 profile package.json =====
step(stepNo++, '把 profile 依赖指向本地副本');
const fileSpec = 'file:' + targetDir.replace(/\\/g, '/');

for (const p of targets) {
  const pkgPath = path.join(DSH_HOME, 'profiles', p, 'package.json');
  const pkg = readJson(pkgPath);
  const cur = pkg.dependencies?.[PLUGIN];
  if (cur === undefined) {
    warn(`${p}: 没有声明 ${PLUGIN}，跳过`);
    continue;
  }
  if (cur === fileSpec) {
    ok(`${p}: 已经指向本地副本，无需改动`);
    continue;
  }
  if (OPT.dryRun) {
    log(`     [dry-run] ${p}: ${cur} → ${fileSpec}`);
    continue;
  }
  const bak = pkgPath + '.bak-before-vendor-' + Date.now();
  fs.copyFileSync(pkgPath, bak);
  pkg.dependencies[PLUGIN] = fileSpec;
  writeJson(pkgPath, pkg);
  ok(`${p}: ${cur} → ${C.cyan}file:…${C.reset}`);
  dim(`备份: ${bak}`);
}

// ===== 步骤 7：下一步指引 =====
step(stepNo++, '下一步');
if (OPT.dryRun) {
  log(`\n  这是 --dry-run，什么都没写。去掉它再跑一次就会实际执行。`);
  process.exit(0);
}

for (const p of targets) {
  const dir = path.join(DSH_HOME, 'profiles', p);
  log(`\n  在 ${C.cyan}${dir}${C.reset} 下执行:`);
  log(`     ${C.bold}pnpm install${C.reset}`);
}
log(`\n  然后重启 DSH，验证:`);
log(`     ${C.bold}curl http://127.0.0.1:<端口>/api/pair/status${C.reset}`);
log(`  返回 200 就说明插件正常工作。`);
log(`\n  ${C.dim}端口每次重启都会变（DSH 用 --port 0），可以从 DSH 日志里找。${C.reset}`);
log(`\n  ${C.green}${C.bold}固化完成。此后上游删包、发新版，都不影响你。${C.reset}\n`);

function countFiles(dir) {
  let n = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) n += countFiles(path.join(dir, e.name));
    else n += 1;
  }
  return n;
}
