/**
 * 插件更新检查 —— 只回答「我装的是最新版吗」，**不下载、不覆盖、不重启**。
 *
 * 为什么只做检查不做自动更新：
 * 本插件以 symlink 形式挂在 DSH profile 的 node_modules 里（`link:` 依赖）。
 * 自动改写一个正在被 DSH 加载的包，一旦半途失败，用户看不到「更新失败」，
 * 只会看到**整个通道面板消失**——这比旧版本更糟。所以这里只把事实摆出来：
 * 本地版本、远端最新版本、有没有新版、去哪里更新。
 *
 * 版本源：三个镜像**并发**去读公开仓里的插件 `package.json`，谁先回谁赢
 * （jsDelivr / raw.githubusercontent / gh-proxy）。全部不通时如实报
 * 「检查失败」，绝不把旧数据当成结论。
 *
 * ⚠️ 版本号只有一个真相源：**插件自己的 `package.json`**。
 * 绝不能拿 `api.github.com/.../releases/latest` 的 tag —— 那是 **App** 的发布号
 * （`v0.1.1`），和插件版本（`0.1.0`）是两套独立编号。混用的后果是插件永远显示
 * 「有新版本」，用户点更新后版本号纹丝不动，看起来像更新功能坏了（2026-11-07 修）。
 *
 * @module dsh-pal-channel/update
 */
import fs from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

/** 插件包根（lib/ 的上一级）里的 package.json。 */
const PACKAGE_FILE = fileURLToPath(new URL('../package.json', import.meta.url));

/** 公开仓库与更新说明地址（与项目其它处的 GitHub 地址保持一致）。 */
const RELEASE_URL = 'https://github.com/yiheng-zyh/DSH-Pal/releases/latest';

/**
 * 插件在公开仓里的目录，按「国内可达性」顺序列出镜像前缀。
 *
 * 每个前缀下面必须同时有 `package.json`（版本）和 `CHANGELOG.md`（公告），
 * 所以版本和公告共用一个源列表 —— 否则会出现「版本从 A 源拿、公告从 B 源拿」
 * 的两套降级逻辑，改了源只改一边，表现为「有新版但公告永远空白」。
 *
 * 实测（2026-11-07，本机）：
 *   cdn.jsdelivr.net          8 秒超时（本机不可用，但不能删——其它地区可达）
 *   raw.githubusercontent.com 715ms
 *   gh-proxy.com 镜像         2.9s
 */
const MIRRORS = [
  { id: 'jsdelivr', base: 'https://cdn.jsdelivr.net/gh/yiheng-zyh/DSH-Pal@main/tools/pal-channel-plugin' },
  { id: 'raw', base: 'https://raw.githubusercontent.com/yiheng-zyh/DSH-Pal/main/tools/pal-channel-plugin' },
  {
    id: 'gh-proxy',
    base: 'https://gh-proxy.com/https://raw.githubusercontent.com/yiheng-zyh/DSH-Pal/main/tools/pal-channel-plugin',
  },
];

/** 公告最多展示多少行：面板是一个设置区块，不是发布页。 */
const NOTES_MAX_LINES = 40;

/** 检查超时：国内网络下 6 秒拿不到就换下一个源，不让面板卡住。 */
const TIMEOUT_MS = 6000;

/** 读本地插件版本；读不到返回 null（不是 0.0.0，避免把「读失败」显示成「很旧」）。 */
export async function localVersion() {
  try {
    const parsed = JSON.parse(await fs.readFile(PACKAGE_FILE, 'utf8'));
    return typeof parsed?.version === 'string' ? parsed.version : null;
  } catch {
    return null;
  }
}

/**
 * 比较两个版本号。支持三种形态：
 *   1. 纯 semver：`1.2.3`、`v1.2.3`、`1.2.3-alpha.7`
 *   2. **带前缀**：`pal-plugin-1.2.3`（2026-11-07 起插件用这套编号，
 *      前缀把它和 App 的版本号彻底区分开，见 CHANGELOG）
 *   3. 两者混比：`0.1.0` vs `pal-plugin-0.1.0`
 *
 * ⚠️ 解析必须用「找数字串」而不是「按 `-` 切分」：
 * 旧实现按第一个 `-` 切，`pal-plugin-0.1.10` 会变成 core=`pal`（解析成 0）、
 * pre=`plugin-0.1.10`，于是 `0.1.10` 被判成**比 0.1.9 更旧**——版本号一加前缀
 * 更新判定就整体失效，而且是静默失效（界面照常显示，结论是错的）。
 *
 * 预发布按 semver 规则处理：`0.1.0-alpha.2 < 0.1.0`。这条不能省 ——
 * 项目里真实存在 `0.1.0-alpha.7` 与 `0.1.0` 两种版本，判错会把「已发布正式版」
 * 说成「有新版」，用户按提示更新后发现什么都没变。
 *
 * @returns 正数表示 a 比 b 新；负数表示更旧；0 相同。
 */
export function compareVersions(a, b) {
  const parse = (value) => {
    const text = String(value ?? '').trim().replace(/^v/i, '');
    // 抓第一段 `数字.数字[.数字…]`；前缀（pal-plugin-）自然被跳过。
    const match = /(\d+)(?:\.(\d+))?(?:\.(\d+))?(.*)$/.exec(text);
    if (!match) return { parts: [0, 0, 0], pre: '' };
    const parts = [match[1], match[2], match[3]].map((raw) => {
      const n = Number.parseInt(raw ?? '0', 10);
      return Number.isFinite(n) ? n : 0;
    });
    // 主版本之后剩下的部分：`-alpha.7` 去掉前导 `-`；`+build` 这类构建元数据忽略。
    let rest = String(match[4] ?? '');
    const plus = rest.indexOf('+');
    if (plus >= 0) rest = rest.slice(0, plus);
    const pre = rest.replace(/^-/, '');
    return { parts, pre };
  };
  const left = parse(a);
  const right = parse(b);
  for (let i = 0; i < Math.max(left.parts.length, right.parts.length); i += 1) {
    const diff = (left.parts[i] ?? 0) - (right.parts[i] ?? 0);
    if (diff !== 0) return diff;
  }
  // 主版本相同：没有预发布后缀的那个更新（semver 规则）。
  if (left.pre === right.pre) return 0;
  if (!left.pre) return 1;
  if (!right.pre) return -1;
  return left.pre.localeCompare(right.pre);
}

/**
 * 版本源：每个源负责把它的响应体变成版本号字符串，不合法返回 null。
 *
 * **必须只读插件自己的 `package.json`。**
 * 曾经把 GitHub Releases 的 tag 当成插件版本 —— 那是 **App** 的发布号（`v0.1.1`），
 * 和插件版本（`package.json` 的 `0.1.0`）是两套独立编号。
 * 结果：插件永远显示"有新版本"，用户点更新后版本号纹丝不动，看起来像更新坏了。
 * 2026-11-07 修：App 的 Release 号不再参与插件版本判定，只用于「打开发布页」。
 */
const SOURCES = MIRRORS.map((mirror) => ({
  id: mirror.id,
  base: mirror.base,
  url: `${mirror.base}/package.json`,
  pick: (body) => (typeof body?.version === 'string' ? body.version : null),
}));

/**
 * 版本号粗校验：允许可选前缀 + 至少 x.y.z 三段数字。
 *
 * 不能写成 `/^\d+\.\d+\.\d+/` —— 那样 `pal-plugin-0.1.0` 会被判成「版本号不合法」，
 * 所有版本源全部失败，更新检查直接瘫痪（而且表现为「检查失败」，很难联想到前缀）。
 */
const VERSION_PATTERN = /^(?:[A-Za-z][\w.-]*-)?v?\d+\.\d+\.\d+/;

/** 拉取某个镜像下的 CHANGELOG 并解析出目标版本的公告；失败降级成空公告。 */
async function fetchChangelog(fetchImpl, base, version, timeoutMs) {
  for (const file of ['CHANGELOG.md']) {
    try {
      const response = await fetchImpl(`${base}/${file}`, {
        signal: AbortSignal.timeout(timeoutMs),
        headers: { accept: 'text/plain', 'user-agent': 'dsh-pal-channel' },
      });
      if (!response.ok) continue;
      const text = await response.text();
      const lines = parseChangelog(text, version);
      if (lines.length > 0) return { lines };
    } catch {
      // 换下一个文件（目前只有一个，保留循环是为了以后加 README 片段）。
    }
  }
  return { lines: [] };
}

/**
 * 从 CHANGELOG 里取某个版本的段落，转成面板能直接显示的行。
 *
 * 只做最小转换（标题→【】，列表项→·），不引 markdown 渲染：
 * 面板是 React.createElement 手写的，为一段公告引一个 markdown 依赖不划算，
 * 而且公告内容是我们自己写的、格式可控。
 *
 * @param markdown - CHANGELOG 原文
 * @param version - 目标版本；找不到精确版本时退回第一个版本段落（即最新）
 * @returns 纯文本行数组
 */
export function parseChangelog(markdown, version) {
  const lines = String(markdown ?? '').split(/\r?\n/);
  const headings = [];
  lines.forEach((line, index) => {
    if (/^##\s+/.test(line)) headings.push({ line, index });
  });
  if (headings.length === 0) return [];

  let chosen = headings[0];
  if (version) {
    const exact = headings.find((heading) => heading.line.includes(version));
    if (exact) chosen = exact;
  }
  const next = headings.find((heading) => heading.index > chosen.index);
  const end = next ? next.index : lines.length;

  const out = [];
  for (let i = chosen.index + 1; i < end && out.length < NOTES_MAX_LINES; i += 1) {
    const raw = lines[i];
    if (/^###\s+/.test(raw)) {
      out.push(`【${raw.replace(/^###\s+/, '').trim()}】`);
      continue;
    }
    if (/^\s*[-*]\s+/.test(raw)) {
      out.push(`· ${raw.replace(/^\s*[-*]\s+/, '').trim()}`);
      continue;
    }
    const text = raw.trim();
    if (text) out.push(text);
  }
  return out;
}

/**
 * 检查更新。
 *
 * **三个源并发打，谁先回谁赢**，输掉的那几个立刻 abort。
 * 曾经是顺序降级（先 jsDelivr，超时才下一个），实测在 jsDelivr 不通的网络上
 * 每次检查都要干等满 6 秒超时才降级 —— 心跳每 10 分钟来一次，面板一直转圈。
 * 并发之后最坏情况仍是 6 秒，但常见情况等于最快的那个源（本机 0.7 秒）。
 *
 * @param options.fetchImpl - 测试注入；默认全局 fetch
 * @param options.local - 覆盖本地版本（测试用）
 * @param options.timeoutMs - 单个源超时
 */
export async function checkUpdate({ fetchImpl = fetch, local, timeoutMs = TIMEOUT_MS } = {}) {
  const current = local ?? (await localVersion());
  const failures = [];
  const controllers = [];

  /** 单个源：成功返回 { source, latest }，失败抛出（原因记进 failures）。 */
  const attempt = (source) => {
    const controller = new AbortController();
    controllers.push(controller);
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    return (async () => {
      try {
        const response = await fetchImpl(source.url, {
          signal: controller.signal,
          headers: { accept: 'application/json', 'user-agent': 'dsh-pal-channel' },
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const latest = source.pick(await response.json());
        if (typeof latest !== 'string' || !VERSION_PATTERN.test(latest)) {
          throw new Error('版本号不合法');
        }
        return { source, latest };
      } catch (error) {
        failures.push(`${source.id} ${String(error?.message ?? error).slice(0, 80)}`);
        throw error;
      } finally {
        clearTimeout(timer);
      }
    })();
  };

  try {
    const winner = await Promise.any(SOURCES.map(attempt));
    // 冠军已出，其余请求没有价值，立刻断掉省电省流量。
    controllers.forEach((controller) => controller.abort());
    const notes = await fetchChangelog(fetchImpl, winner.source.base, winner.latest, timeoutMs);
    return {
      ok: true,
      local: current,
      latest: winner.latest,
      updateAvailable: current === null ? null : compareVersions(winner.latest, current) > 0,
      source: winner.source.id,
      // 公告取不到不影响「有没有新版」的结论：分开报，界面可以只显示版本。
      notes: notes.lines,
      notesAvailable: notes.lines.length > 0,
      releaseUrl: RELEASE_URL,
      checkedAt: new Date().toISOString(),
    };
  } catch {
    // Promise.any 在所有源都失败时 reject；真实原因已经记进 failures。
    return {
      ok: false,
      local: current,
      error: failures.join('；') || '没有可用的版本源',
      releaseUrl: RELEASE_URL,
      checkedAt: new Date().toISOString(),
    };
  }
}