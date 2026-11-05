#!/usr/bin/env node
/**
 * 部署状态自检 —— 一条命令告诉 AI / 用户「电脑端还差什么没配」。
 *
 * 设计原则（重要，改这个文件前先读）：
 * 1. **只读**。本脚本不写配置、不改计划任务、不动隧道、不发写请求。
 *    唯一的网络动作是读取本机回环的 /api/pair/status，用于指纹识别 DSH。
 * 2. **不抛错退出**。任何一项检测失败都归为该项目的「未配置/未知」，
 *    而不是让整个脚本崩掉 —— AI 需要的是全局快照，不是第一个错误。
 * 3. **todo 数组是第一等输出**。它直接回答「还差什么、缺的是谁的责任」，
 *    actor=user 的项必须由用户本人完成，actor=ai 的项 AI 可自行执行。
 * 4. **不猜测、不伪造**。检测不到就写 unknown / false，绝不因为「看起来应该配了」
 *    就报 true。特别是手机端状态本脚本完全无法得知，永远写 unknown。
 *
 * 用法：
 *   node tools/deploy-status.mjs            # 人能看懂的摘要
 *   node tools/deploy-status.mjs --json     # 纯 JSON，给 AI / 程序解析
 *   node tools/deploy-status.mjs --verbose  # 附每项判定依据
 *   node tools/deploy-status.mjs --share    # 脱敏版，可安全贴进公开 Issue
 *
 * 关于 --share：默认输出含真实公网地址、本机路径（可能含用户名）和局域网 IP。
 * 这些属于「本机参数」，按项目脱敏红线不得进入公开 Issue。--share 会把它们
 * 替换成占位符，同时保留布尔状态与计数 —— 排查需要的正是后者。
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import net from 'node:net';
import {fileURLToPath} from 'node:url';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';

const execute = promisify(execFile);
const jsonOnly = process.argv.includes('--json');
const verbose = process.argv.includes('--verbose');
const share = process.argv.includes('--share');

const TOOL_DIR = path.dirname(fileURLToPath(import.meta.url));
const TASK_NAME = 'DSH-Pal-Remote-Access';

/** 每项检测的说明，--verbose 时输出，便于人工核对判定依据。 */
const reasons = {};
const note = (key, text) => { reasons[key] = text; };

// ─────────────────────────────────────────────────────────────
// 1. DSH 本体
// ─────────────────────────────────────────────────────────────

/**
 * 复用 discovery.mjs 的指纹判定，避免两套标准。
 * 找不到 DSH 不算错误：用户可能还没启动 DSH，这本身就是要报告的状态。
 */
async function detectDsh() {
  try {
    const {findDshPort} = await import('./remote-access/discovery.mjs');
    const port = await findDshPort({});
    if (port === null) {
      note('dsh', 'deepseek 进程没有监听可识别的 /api/pair/status 端口');
      return {running: false, port: null};
    }
    note('dsh', `回环端口 ${port} 通过 /api/pair/status 协议指纹`);
    return {running: true, port};
  } catch (error) {
    note('dsh', `检测失败：${error?.message ?? error}`);
    return {running: false, port: null, error: String(error?.message ?? error)};
  }
}

// ─────────────────────────────────────────────────────────────
// 2. 配置文件（config.json / state.json）
// ─────────────────────────────────────────────────────────────

async function dataDir() {
  try {
    const {dataDirectory} = await import('./remote-access/windows.mjs');
    return dataDirectory();
  } catch (error) {
    // 非 Windows 或没有 LOCALAPPDATA：dataDirectory() 直接 throw（windows.mjs:8）。
    // 这不是缺陷，是平台限制，如实上报而不是掩盖成"未配置"。
    note('dataDir', `无法确定数据目录：${error?.message ?? error}`);
    return null;
  }
}

async function readJsonSafe(file) {
  try {
    return JSON.parse(await fs.readFile(file, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * 用 validateConfig 校验配置，而不是自己 if 字段。
 * 原因：校验规则只能有一份，否则脚本说"已配置"、run.mjs 却启动失败。
 */
async function detectConfig(dir) {
  if (!dir) return {configured: false, reason: 'no-windows-data-dir'};
  const file = path.join(dir, 'config.json');
  const raw = await readJsonSafe(file);
  if (raw === null) {
    note('config', `未找到 ${file}`);
    return {configured: false, file, reason: 'missing'};
  }
  try {
    const {validateConfig} = await import('./remote-access/config.mjs');
    const config = validateConfig(raw);
    note('config', `通过 validateConfig：provider=${config.provider} gatewayPort=${config.gatewayPort}`);
    return {
      configured: true,
      file,
      provider: config.provider,
      gatewayPort: config.gatewayPort,
      publicOrigin: config.publicOrigin,
      dshConfigPath: config.dshConfigPath ?? null,
      direct: config.direct ?? {enabled: false, ipv6: false, lan: false},
    };
  } catch (error) {
    note('config', `文件存在但校验不通过：${error?.message ?? error}`);
    return {configured: false, file, reason: 'invalid', error: String(error?.message ?? error)};
  }
}

/** state.json 由 run.mjs 每 5s 重写，是「网关进程是否活着」的唯一可信来源。 */
async function detectState(dir) {
  if (!dir) return {running: false};
  const value = await readJsonSafe(path.join(dir, 'state.json'));
  if (value === null) {
    note('state', '未找到 state.json（网关从未成功启动过，或数据目录不对）');
    return {running: false, reason: 'missing'};
  }
  // 超过 30s 未更新的 state 视为网关已停：run.mjs 每 5s 写一次。
  const updated = Date.parse(value.updatedAt ?? '');
  const ageMs = Number.isFinite(updated) ? Date.now() - updated : null;
  const fresh = ageMs !== null && ageMs < 30_000;
  note('state', `updatedAt=${value.updatedAt} age=${ageMs === null ? '未知' : Math.round(ageMs / 1000) + 's'} fresh=${fresh}`);
  return {
    running: fresh,
    stale: !fresh,
    ageSeconds: ageMs === null ? null : Math.round(ageMs / 1000),
    targetAvailable: value.targetAvailable === true,
    targetPort: value.targetPort ?? null,
    clientState: value.clientState ?? null,
    // 关键：businessVerified 是唯一能代表"手机真能用"的字段，但本工具不产生它。
    businessVerified: value.businessVerified === true,
    publicOrigin: value.publicOrigin ?? null,
    direct: Array.isArray(value.direct) ? value.direct : [],
  };
}

// ─────────────────────────────────────────────────────────────
// 3. Sakura 凭据与 frpc
// ─────────────────────────────────────────────────────────────

async function detectSakura(dir) {
  if (!dir) return {configured: false};
  const tokenFile = path.join(dir, 'sakura-token.dpapi');
  let hasToken = true;
  try {
    await fs.access(tokenFile);
  } catch {
    hasToken = false;
  }
  note('sakuraToken', hasToken ? `存在 ${tokenFile}（DPAPI 加密，本脚本不解密）` : '未找到 sakura-token.dpapi');

  // setup-sakura.mjs 成功后会写 sakura-provisioned.json（mode 0600）。
  // 它的 domain/remote 就是 publicOrigin 的来源，用它交叉校验配置是否自洽。
  const provisioned = await readJsonSafe(path.join(dir, 'sakura-provisioned.json'));
  let tunnelReady = false;
  let expectedOrigin = null;
  if (provisioned && typeof provisioned.domain === 'string' && /^[a-z0-9.-]+\.nyat\.app$/.test(provisioned.domain)
      && /^\d+$/.test(String(provisioned.remote))) {
    tunnelReady = true;
    expectedOrigin = `https://${provisioned.domain}:${provisioned.remote}`;
  }
  note('sakuraTunnel', tunnelReady
    ? `sakura-provisioned.json 就绪，推导 origin=${expectedOrigin}`
    : '未找到或不合法的 sakura-provisioned.json（隧道可能从未创建成功）');

  return {
    configured: hasToken,
    tokenStored: hasToken,
    tunnelReady,
    expectedOrigin,
    reason: hasToken ? undefined : 'no-sakura-token',
  };
}

async function detectFrpc(config, dir) {
  if (!dir || !config?.configured || config.provider !== 'sakura') {
    return {configured: false, applicable: config?.provider === 'sakura'};
  }
  const file = path.join(dir, 'config.json');
  const raw = await readJsonSafe(file);
  const frpcPath = raw?.frpcPath;
  if (typeof frpcPath !== 'string') return {configured: false, reason: 'no-path'};
  try {
    await fs.access(frpcPath);
    note('frpc', `存在 ${frpcPath}（SHA-256 由 run.mjs 启动时比对，本脚本不重复校验）`);
    return {configured: true, path: frpcPath};
  } catch {
    note('frpc', `配置指向 ${frpcPath} 但文件不存在`);
    return {configured: false, reason: 'missing-binary', path: frpcPath};
  }
}

// ─────────────────────────────────────────────────────────────
// 3.5 插件与 DSH 配置红线检查
// ─────────────────────────────────────────────────────────────

/**
 * 远程访问插件是否装了。复用 trust.mjs 的 remotePluginConfig()，
 * 它要求「恰好一个」远程插件，否则抛错 —— 正好能区分「没装」和「装重了」。
 */
/**
 * 检查远程访问插件是否已「固化到本地」。
 *
 * 为什么单独查这件事：App 依赖的 @linxin666/dsh-remote-web-ui 上游已弃用，
 * npm 上的 0.4.14 随时可能消失。如果用户的依赖还是纯版本号（比如 "0.4.14"），
 * 那今天就还能装、明天重装就装不上 —— 这是唯一一项「现在不做、将来无法补救」
 * 的配置，所以必须主动提示。
 *
 * @param {string} dshConfigPath cordis.patch.yml 的路径（同目录向上找 profile）
 * @returns {Promise<{known:boolean, spec?:string, isFile:boolean, profilePath?:string}>}
 */
async function detectVendored(dshConfigPath) {
  try {
    // dshConfigPath 形如 <profile>/cordis.patch.yml，向上一级就是 profile 目录
    const profileDir = path.dirname(dshConfigPath);
    const pkgPath = path.join(profileDir, 'package.json');
    const raw = await fs.readFile(pkgPath, 'utf8');
    const pkg = JSON.parse(raw);
    const spec = pkg?.dependencies?.['@linxin666/dsh-remote-web-ui'];
    if (spec === undefined) return {known: true, isFile: false, profilePath: profileDir};
    return {
      known: true,
      spec: String(spec),
      isFile: String(spec).startsWith('file:'),
      profilePath: profileDir,
    };
  } catch {
    // 读不到就是不知道，不瞎猜
    return {known: false, isFile: false};
  }
}

async function detectPlugin(config) {
  const dshConfigPath = config?.dshConfigPath;
  if (!dshConfigPath) {
    note('plugin', '配置里没有 dshConfigPath，无法定位 DSH profile');
    return {checked: false, reason: 'no-dsh-config-path'};
  }
  // 顺便查一下是否已「固化到本地」—— 这决定上游删包后用户还能不能重装。
  const vendored = await detectVendored(dshConfigPath);
  let source;
  try {
    source = await fs.readFile(dshConfigPath, 'utf8');
  } catch {
    note('plugin', `读不到 ${dshConfigPath}`);
    return {checked: false, reason: 'config-unreadable', vendored};
  }
  // 先把 yaml 准备好，再决定走精确解析还是文本近似。
  // 全新 clone 没有 node_modules（要先 npm ci），此时不能让整个检测失败 ——
  // 这是新用户的默认起点，必须优雅降级并给出明确指引。
  let yaml = null;
  try {
    yaml = await import('./remote-access/node_modules/yaml/dist/index.js');
  } catch {
    try {
      yaml = await import('yaml');
    } catch {
      yaml = null;
    }
  }
  if (!yaml?.parseDocument) {
    // 退化为文本检查：插件包名出现过就算装了。
    // 明确标注 approximate，不假装做了唯一性校验。
    const count = (source.match(/@linxin666\/dsh-remote-web-ui/g) ?? []).length;
    note('plugin', `yaml 不可用（未装依赖），退化为文本检查：包名出现 ${count} 次`);
    return {
      checked: true,
      installed: count > 0,
      unique: count === 1 ? null : false,
      approximate: true,
      needsNpmInstall: true,
      vendored,
    };
  }
  try {
    const {remotePluginConfig} = await import('./remote-access/trust.mjs');
    // 必须用 parseDocument 而不是 parse：remotePluginConfig() 内部遍历的是
    // YAML AST（doc.contents / isMap / node.get），普通 JS 对象上没有这些方法，
    // 传错会走进「找到 0 个插件」的误报（2026-11-03 踩到过）。
    // cordis.patch.yml 含 !!js 自定义标签，标准 YAML 会刷警告，silent 压掉。
    const parsed = yaml.parseDocument(source, {logLevel: 'silent'});
    remotePluginConfig(parsed);
    note('plugin', '恰好一个远程插件，已启用');
    note('plugin', vendored.isFile
      ? '已固化到本地（上游删包也不影响）'
      : '未固化：依赖是 npm 版本号，上游删包后无法重装');
    return {checked: true, installed: true, unique: true, vendored};
  } catch (error) {
    const message = String(error?.message ?? error);
    const ambiguous = /Expected exactly one remote-web-ui plugin/i.test(message);
    note('plugin', ambiguous ? '插件数量不是恰好一个（没装或装重了）' : `检测失败：${message}`);
    return {checked: true, installed: !ambiguous, unique: false, error: message, vendored};
  }
}

/**
 * 红线检查：DSH webserver 必须保持 127.0.0.1 + 端口 0。
 * 改过会导致 DSH 桌面端起不来（历史上真实事故）。
 * 这里只读不写 —— 发现问题只报告，绝不自动改回去。
 */
async function detectWebserverRedline(config) {
  const dshConfigPath = config?.dshConfigPath;
  if (!dshConfigPath) return {checked: false};
  let source;
  try {
    source = await fs.readFile(dshConfigPath, 'utf8');
  } catch {
    return {checked: false, reason: 'unreadable'};
  }
  // 粗粒度文本检查：找 webserver 块里的 host/port。
  // 不用完整 YAML 解析 —— 即使语法异常也要能给出线索，宁可粗糙也不要漏报。
  const hostMatch = source.match(/webserver[\s\S]{0,400}?host:\s*['"]?([^'"\n\r]+)/);
  const portMatch = source.match(/webserver[\s\S]{0,400}?port:\s*['"]?([^'"\n\r]+)/);
  const host = hostMatch ? hostMatch[1].trim() : null;
  const port = portMatch ? portMatch[1].trim() : null;
  const hostOk = host === null || host === '127.0.0.1';
  const portOk = port === null || port === '0';
  note('webserver', `host=${host ?? '(未显式设置，默认 127.0.0.1)'} port=${port ?? '(未显式设置，默认 0)'}`);
  return {checked: true, host, port, compliant: hostOk && portOk};
}

// ─────────────────────────────────────────────────────────────
// 4. 计划任务（登录自启）
// ─────────────────────────────────────────────────────────────

async function detectTask() {
  if (process.platform !== 'win32') return {registered: null, reason: 'not-windows'};
  try {
    const {stdout} = await execute('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-Command',
      `$t=Get-ScheduledTask -TaskName '${TASK_NAME}' -ErrorAction SilentlyContinue; if($t){$i=Get-ScheduledTaskInfo -TaskName '${TASK_NAME}' -ErrorAction SilentlyContinue; [pscustomobject]@{state=[string]$t.State;lastRun=[string]$i.LastRunTime}|ConvertTo-Json -Compress}`,
    ], {windowsHide: true, timeout: 15_000, maxBuffer: 65536});
    if (!stdout.trim()) {
      note('task', `未找到计划任务 ${TASK_NAME}`);
      return {registered: false, reason: 'not-registered'};
    }
    const value = JSON.parse(stdout);
    note('task', `计划任务 ${TASK_NAME} state=${value.state} lastRun=${value.lastRun}`);
    return {registered: true, state: value.state ?? null, lastRun: value.lastRun ?? null};
  } catch (error) {
    note('task', `查询计划任务失败：${error?.message ?? error}`);
    return {registered: null, reason: 'query-failed'};
  }
}

// ─────────────────────────────────────────────────────────────
// 5. 插件与本机直连端口
// ─────────────────────────────────────────────────────────────

/**
 * 直连监听器是否真的在监听。用 TCP connect 判断，不靠读配置 ——
 * 「配置说开了」和「真的绑上了」是两件事（换网络后 IPv6 会消失）。
 */
async function detectDirectListeners(config) {
  const direct = config?.direct ?? {enabled: false};
  if (direct.enabled !== true) {
    note('direct', 'direct 段未启用（只绑 127.0.0.1）');
    return {enabled: false, bindings: []};
  }
  const port = config.gatewayPort;
  const bindings = [];
  let missingDependencies = false;
  for (const kind of ['ipv6', 'lan']) {
    if (direct[kind] !== true) continue;
    let listening = false;
    try {
      const {advertisableAddresses, planDirectEntries} = await import('./remote-access/direct.mjs');
      const addresses = advertisableAddresses();
      const plan = planDirectEntries({
        enableIPv6: kind === 'ipv6',
        enableLAN: kind === 'lan',
        port,
        ipv6: addresses.ipv6,
        ipv4: addresses.ipv4,
      });
      const candidate = plan.entries.find((entry) => entry.kind === kind);
      if (candidate) listening = await tcpConnect(candidate.address, port);
      bindings.push({kind, address: candidate?.address ?? null, expectedListening: Boolean(candidate), listening});
    } catch (error) {
      // 依赖文件缺失（如 direct.mjs / 旧版 config.mjs 不支持 direct）会让这里抛错。
      // 曾经静默压成 error 字段而不上报，表现为「配置写着 enabled 但脚本报未启用」，
      // 用户会以为是自己没配对，反复折腾。现在明确区分「依赖缺失」并计入 todo。
      const missing = /Cannot find module|is not a function|does not provide an export/i.test(String(error?.message));
      bindings.push({
        kind,
        error: String(error?.message ?? error),
        reason: missing ? 'dependency-missing' : 'detect-failed',
      });
      if (missing) missingDependencies = true;
    }
  }
  note('direct', `direct.enabled=true，端口 ${port}，实测 ${bindings.filter((b) => b.listening).length}/${bindings.length} 在监听`);
  return {enabled: true, port, bindings, missingDependencies};
}

function tcpConnect(host, port, timeoutMs = 1500) {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    const done = (value) => {
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => done(true));
    socket.once('timeout', () => done(false));
    socket.once('error', () => done(false));
    socket.connect(port, host);
  });
}

// ─────────────────────────────────────────────────────────────
// 6. 组装 todo —— 这是 AI 最需要的部分
// ─────────────────────────────────────────────────────────────

function buildTodo({dsh, config, state, sakura, frpc, task, direct, plugin, webserver}) {
  const todo = [];
  const add = (id, actor, message, action) => todo.push({id, actor, message, action});

  if (!dsh.running) {
    add('dsh-not-running', 'user', '电脑上 DSH 没在运行，或没监听可识别的端口',
      '先启动 DSH 桌面端（或 dsh web），确认 profile 后重跑本脚本');
  }

  // 红线优先：webserver 被改过会让 DSH 桌面端起不来，必须先报。
  if (webserver?.checked && webserver.compliant === false) {
    add('webserver-redline', 'ai',
      `DSH webserver 配置违反红线（host=${webserver.host ?? '默认'} port=${webserver.port ?? '默认'}）`,
      '必须恢复为 host 127.0.0.1 + port 0，改 LAN 地址会导致 DSH 桌面端无法启动；改前先备份 cordis.patch.yml');
  }

  if (plugin?.needsNpmInstall) {
    add('npm-install', 'ai', '还没装 Node 依赖，插件检测退化为近似判断',
      '在 tools/remote-access 下执行 npm ci --ignore-scripts --no-audit --no-fund 后重跑本脚本，才能精确检测插件与 trustedHosts');
  }
  if (plugin?.checked && plugin.installed === false) {
    add('plugin-missing', 'ai', '远程访问插件未安装或未启用',
      '优先用固化方式装（上游已弃用、随时会从 npm 消失）：在仓库根目录执行 '
      +'node tools/vendor-plugin.mjs --profile <desktop|web>，然后进 profile 目录 pnpm install。'
      +'退而求其次才用 dsh plugin --profile <名> add @linxin666/dsh-remote-web-ui@0.4.14，'
      +'且必须固定 0.4.14 而非 @latest（新版移除了本 App 依赖的 /api/pair 与 /remote 接口）。'
      +'详见 docs/PLUGIN-VENDOR-GUIDE.md');
  }
  // 插件装上了但没固化：现在能用，但上游删包后就装不回来了。
  // 这是唯一一项「现在不做、将来无法补救」的配置，必须主动提。
  if (plugin?.checked && plugin.installed === true && plugin.vendored?.known === true
      && plugin.vendored.isFile === false) {
    add('plugin-not-vendored', 'ai', '远程访问插件未固化到本地，上游删包后将无法重装',
      '当前依赖是 npm 版本号（' + (plugin.vendored.spec ?? '未知') + '），'
      +'上游作者已弃用该插件并声明不再保留浏览器远控能力，npm 上的 0.4.14 随时可能消失。'
      +'在仓库根目录执行 node tools/vendor-plugin.mjs --profile <desktop|web> 固化到本地，'
      +'然后进 profile 目录 pnpm install。详见 docs/PLUGIN-VENDOR-GUIDE.md');
  }
  if (plugin?.checked && plugin.unique === false && plugin.installed !== false) {
    add('plugin-ambiguous', 'ai', '远程插件装了多个或配置不唯一，工具无法确定用哪个',
      '检查 cordis.patch.yml，只保留一个 @linxin666/dsh-remote-web-ui 条目');
  }

  if (!config.configured) {
    // 全新机器：不能只报「去配置」就短路返回。
    // 曾经这里直接 return，结果新用户只看到 1 条 todo，且 actor 标成 ai，
    // 内容是「去注册 Sakura 并实名」—— 那实际是用户的事。AI 拿到这样的输出
    // 会误判「只有一件事要干」。现在给出完整的待办全貌，责任分明。
    //
    // 注意顺序纪律：公网优先，局域网放到第二阶段。局域网最容易配成功，
    // 先弄它会给人虚假的完成感 —— 用户出门才发现用不了。
    add('phase1-public', 'ai',
      '第一阶段（公网）未开始：remote-access 还没配置（config.json 缺失或不合法）',
      '按 tools/remote-access/README.md 的「准备」章节执行 npm ci，然后配国内 IPv4 中转（P0）。先不要配局域网');
    if (!sakura.configured) {
      add('sakura-token', 'user',
        '配国内通道需要 Sakura 访问密钥（需要你本人注册并实名，AI 做不了）',
        'https://www.natfrp.com/ 注册并实名 → 运行 tools/remote-access/save-sakura-token.cmd，在弹窗里粘贴密钥（别发聊天里）');
    }
    add('gate-verify', 'user',
      '阶段闸门：手机关掉 Wi-Fi、只用移动数据，确认能进工作区并收到回复',
      '这是判断"公网修好了"的唯一标准。没过这一步之前，配局域网只会掩盖问题');
    add('phase2-lan', 'ai',
      '第二阶段（局域网）排在公网之后，不要跳过',
      '公网闸门过了就配 Wi-Fi 直连：让在家更快、不消耗隧道 5 GiB/月额度。它是必配项，不是可选项——除非用户明确说不要');
    add('phone-unknown', 'user', '手机端状态本工具无法检测（装没装、配没配对、能不能看到工作区）',
      '按 docs/features-setup.md 检查手机端功能开关，并按 DoD 逐层验收');
    return todo;
  }

  // 国内通道是本次最容易被漏掉的一项，单独判定。
  if (!sakura.configured && config.provider === 'sakura') {
    add('sakura-token', 'user',
      'Sakura 访问密钥还没保存（provider=sakura 但没有凭据）',
      '运行 tools/remote-access/save-sakura-token.cmd，在弹出的窗口粘贴密钥；需要你先注册并实名');
  }
  if (config.provider === 'sakura' && sakura.configured && !sakura.tunnelReady) {
    add('sakura-tunnel', 'ai',
      '有 Sakura 密钥，但隧道没创建成功（缺 sakura-provisioned.json）',
      '在 tools/remote-access 下执行 node setup-sakura.mjs <节点ID>；先列出免费节点挑一个');
  }
  // 配置自洽性：publicOrigin 应与隧道实际推导的 origin 一致，否则说明换过隧道/改过配置。
  if (sakura.expectedOrigin && config.publicOrigin && sakura.expectedOrigin !== config.publicOrigin) {
    add('origin-mismatch', 'ai',
      `配置的公网地址与隧道实际地址不一致（配置=${config.publicOrigin}，隧道=${sakura.expectedOrigin}）`,
      '重跑 node configure.mjs 让它按当前隧道重建配置；不要手改 config.json');
  }
  if (config.provider === 'sakura' && sakura.configured && !frpc.configured) {
    add('frpc-missing', 'ai', 'frpc 可执行文件不存在',
      '在 tools/remote-access 下执行 node configure.mjs');
  }

  if (!state.running) {
    add('gateway-not-running', 'ai', '转发网关没有在运行（state.json 缺失或超过 30s 未更新）',
      task.registered
        ? `计划任务已注册，执行 Start-ScheduledTask -TaskName ${TASK_NAME}`
        : '先在 tools/remote-access 下前台运行 node run.mjs 验证，再装登录自启任务');
  } else if (state.stale) {
    add('gateway-stale', 'ai', 'state.json 已过期，网关可能已停止',
      '检查计划任务状态与网关日志');
  }

  if (state.running && !state.targetAvailable) {
    add('dsh-target-lost', 'user', '网关在跑但找不到 DSH 回环端口',
      '确认 DSH 正在运行；若换过 profile，重跑 configure.mjs 指定正确路径');
  }

  // clientState 是 run.mjs 解析 frpc 输出得到的真实运行态。
  // ⚠️ 陷阱一：provider='external' 时 run.mjs 的 startClient() 立刻 return，
  // clientState 永远是 'not-started' —— 那是正常态，不能报成故障。
  // （依据：run.mjs:39 `if(config.provider!=='sakura'||...)return;`）
  // ⚠️ 陷阱二：'tunnel-degraded' 表示「曾经 start proxy success，之后又刷出 error」。
  // 那是抖动重连，不等于不可用，列为警告而不是错误。
  const badClientStates = new Set([
    'client-reported-error', 'spawn-failed', 'waiting-to-retry',
  ]);
  if (config.provider === 'sakura' && state.clientState === 'not-started') {
    badClientStates.add('not-started');
  }
  if (state.running && state.clientState === 'tunnel-degraded') {
    add('tunnel-degraded', 'user',
      '隧道启动过，但之后上报过错误（可能正在重连，也可能已经不稳）',
      '用手机实际连一次确认；若连不上，看网关日志里的 frpc 报错摘要');
  }
  if (state.running && badClientStates.has(state.clientState)) {
    const hint = {
      'client-reported-error': 'frpc 报错退出或被拒；常见是隧道被停用、节点维护、密钥失效或免费额度用尽',
      'spawn-failed': 'frpc 无法启动；检查 config.json 的 frpcPath 与实际文件、是否被杀软拦截',
      'waiting-to-retry': 'frpc 正在退避重试；上一次启动失败，看网关日志的 stderr 摘要',
      'not-started': 'provider=sakura 但 frpc 还没开始启动；通常是 DSH 端口尚未发现',
    }[state.clientState];
    add('tunnel-not-working', 'ai',
      `网关进程在跑，但隧道没工作（clientState=${state.clientState}）—— 这不代表通道可用`,
      `${hint}；确认后再让手机扫码，否则会给用户一个连不上的入口`);
  }

  if (state.running && !state.businessVerified) {
    add('business-unverified', 'user',
      '通道进程在跑，但手机端业务可用性从未验证（running-unverified ≠ 可用）',
      '用手机完成：首次资源下载 → 进入工作区 → 读取已有会话 → 确认实时通道；这是唯一可信的验收');
  }

  if (direct.enabled) {
    const dead = direct.bindings.filter((b) => b.expectedListening && !b.listening);
    if (dead.length) {
      add('direct-not-listening', 'ai',
        `直连已启用但 ${dead.map((b) => b.kind).join('/')} 没有在监听`,
        '地址可能已变化或绑定失败；网关每 5s 自动重规划，若持续失败检查本机地址与防火墙');
    }
    // 依赖缺失是仓库不完整，不是用户配置问题 —— 必须单独且醒目地报出来。
    if (direct.missingDependencies) {
      add('direct-deps-missing', 'ai',
        '直连检测依赖的模块缺失或版本过旧（tools/remote-access/direct.mjs 不存在，或 config.mjs 不支持 direct 段）',
        '仓库文件不完整：确认 tools/remote-access 下有 direct.mjs，且 config.mjs 含 direct 相关导出；从完整版本重新获取仓库');
    }
  }

  if (!task.registered && state.running) {
    add('autostart-missing', 'ai', '网关在手动运行，还没装登录自启',
      '验证手机可用后执行 install-autostart.ps1 -DryRun 确认，再正式安装');
  }

  // 手机侧本脚本永远无法得知，必须显式说明，避免 AI 误以为"没报错就是配好了"。
  add('phone-unknown', 'user', '手机端状态本工具无法检测（装没装、配没配对、能不能看到工作区）',
    '按 docs/features-setup.md 检查手机端功能开关，并按 DoD 逐层验收');

  return todo;
}

// ─────────────────────────────────────────────────────────────
// 脱敏（--share）
// ─────────────────────────────────────────────────────────────

/**
 * 把本机参数换成占位符，保留布尔状态与计数。
 * 排查需要的是「配没配、跑没跑、几条在监听」，不是具体地址。
 * 这与 docs/ 里的脱敏规则同源：公网域名、本机路径、内网 IP 都属本机参数。
 */
function redact(value) {
  if (typeof value === 'string') {
    // Windows 用户目录：C:\Users\<真名>\...
    let text = value.replace(/([A-Za-z]:\\Users\\)[^\\]+/g, '$1<user>');
    // 公网 origin：https://host:port
    text = text.replace(/https?:\/\/[^\s"',]+/g, '<origin>');
    // IPv4 / IPv6 字面量
    text = text.replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '<ip>');
    text = text.replace(/\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b/g, '<ipv6>');
    return text;
  }
  if (Array.isArray(value)) return value.map(redact);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redact(v)]));
  }
  return value;
}

// ─────────────────────────────────────────────────────────────
// 主流程
// ─────────────────────────────────────────────────────────────

async function main() {
  const dir = await dataDir();
  const dsh = await detectDsh();
  const config = await detectConfig(dir);
  const state = await detectState(dir);
  const sakura = await detectSakura(dir);
  const frpc = await detectFrpc(config, dir);
  const task = await detectTask();
  const direct = await detectDirectListeners(config);
  const plugin = await detectPlugin(config);
  const webserver = await detectWebserverRedline(config);

  const todo = buildTodo({dsh, config, state, sakura, frpc, task, direct, plugin, webserver});

  const result = {
    generatedAt: new Date().toISOString(),
    platform: process.platform,
    dataDirectory: dir,
    dsh,
    config: {
      configured: config.configured,
      provider: config.provider ?? null,
      gatewayPort: config.gatewayPort ?? null,
      publicOrigin: config.publicOrigin ?? null,
      dshConfigPath: config.dshConfigPath ?? null,
    },
    gateway: {
      running: state.running,
      stale: state.stale === true,
      targetAvailable: state.targetAvailable === true,
      targetPort: state.targetPort ?? null,
      clientState: state.clientState ?? null,
      businessVerified: state.businessVerified === true,
    },
    channels: {
      // 局域网 / Relay / 国内 的"配置存在"由 config 与插件共同决定，
      // 本脚本只报它能证明的部分，不推断。
      domestic: {
        provider: config.provider ?? null,
        tokenStored: sakura.tokenStored === true,
        tunnelProvisioned: sakura.tunnelReady === true,
        frpcReady: frpc.configured === true,
      },
      direct,
    },
    autostart: task,
    plugin,
    webserver,
    app: {
      // 手机端完全超出本脚本能力范围，永远 unknown。
      installedOnPhone: 'unknown',
      version: 'unknown',
      note: '手机端需人工验收，见 docs/features-setup.md',
    },
    todo,
  };

  if (verbose) result.reasons = reasons;

  // --share 时整体脱敏，包括 reasons 里可能夹带的真实地址。
  const output = share ? redact(result) : result;

  if (jsonOnly) {
    process.stdout.write(JSON.stringify(output, null, 2) + '\n');
    return;
  }

  // 人看的摘要。--share 时同样脱敏：路径与地址走 redact，布尔与计数保持原样。
  const mark = (v) => (v === true ? '✅' : v === false ? '❌' : '❔');
  const safe = (text) => (share ? redact(String(text)) : String(text));
  // 网关进程「活着」和「隧道能用」是两件事，摘要里必须分开显示，
  // 否则用户看到 ✅ 会以为通道通了（2026-11-03 实测踩到：进程在跑但 frpc 报错）。
  // tunnel-degraded 显示为警告态，不直接判死。
  const tunnelHealthy = state.clientState === 'tunnel-started-unverified' || state.clientState === 'certificate-pending';
  const tunnelDegraded = state.clientState === 'tunnel-degraded';
  const tunnelMark = tunnelHealthy ? '✅' : tunnelDegraded ? '⚠️' : '❌';
  const tunnelText = tunnelHealthy ? state.clientState
    : tunnelDegraded ? `降级重连中：${state.clientState}` : `未工作：${state.clientState}`;
  const lines = [
    `DSH Pal 部署自检  ${result.generatedAt}${share ? '（已脱敏，可公开）' : ''}`,
    `数据目录：${safe(dir ?? '（非 Windows 或无法确定）')}`,
    '',
    `DSH 本体        ${mark(dsh.running)}  ${dsh.running ? `端口 ${dsh.port}` : '未运行'}`,
    `通道配置        ${mark(config.configured)}  ${config.configured ? `provider=${config.provider} 网关端口=${config.gatewayPort}` : '未配置'}`,
    `网关进程        ${mark(state.running)}  ${state.running ? `clientState=${state.clientState}` : '未运行'}`,
    `隧道状态        ${state.running ? tunnelMark : '❌'}  ${state.running ? tunnelText : '网关未运行'}`,
    `DSH 目标可达    ${mark(state.targetAvailable)}${state.targetPort ? `  端口 ${state.targetPort}` : ''}`,
    `业务已验收      ${mark(state.businessVerified)}  ${state.businessVerified ? '' : '（running-unverified 不等于可用）'}`,
    `Sakura 凭据     ${mark(sakura.tokenStored)}`,
    `frpc 就绪       ${mark(frpc.configured)}`,
    `登录自启任务    ${mark(task.registered)}  ${task.registered ? task.state ?? '' : ''}`,
    `远程访问插件    ${mark(plugin?.checked ? plugin.installed === true : null)}  ${plugin?.checked ? (plugin.installed ? (plugin.approximate ? '已安装（近似，未装依赖）' : '已安装') : '未找到') : '未检测（无 dshConfigPath）'}`,
    `webserver红线   ${mark(webserver?.checked ? webserver.compliant === true : null)}  ${webserver?.checked ? (webserver.compliant ? '127.0.0.1:0 合规' : `违规 ${webserver.host}:${webserver.port}`) : '未检测'}`,
    `直连监听        ${mark(direct.enabled ? direct.bindings.some((b) => b.listening) : null)}  ${direct.enabled ? `${direct.bindings.filter((b) => b.listening).length}/${direct.bindings.length} 在监听` : '未启用'}`,
    '',
  ];

  if (todo.length === 0) {
    lines.push('todo 为空：电脑端配置齐全。仍需按 DoD 完成手机端真机验收。');
  } else {
    lines.push(`还差 ${todo.length} 项：`);
    for (const item of todo) {
      lines.push(`  [${item.actor}] ${safe(item.message)}`);
      lines.push(`         → ${safe(item.action)}`);
    }
  }

  if (verbose) {
    lines.push('', '判定依据：');
    for (const [key, text] of Object.entries(reasons)) lines.push(`  ${key}: ${safe(text)}`);
  }

  process.stdout.write(lines.join('\n') + '\n');
}

main().catch((error) => {
  // 顶层兜底：即使自检脚本自身出问题，也要给出可解析的 JSON，不让 AI 卡住。
  if (jsonOnly) {
    process.stdout.write(JSON.stringify({error: String(error?.message ?? error), todo: []}, null, 2) + '\n');
  } else {
    process.stderr.write(`自检失败：${error?.message ?? error}\n`);
  }
  process.exitCode = 1;
});
