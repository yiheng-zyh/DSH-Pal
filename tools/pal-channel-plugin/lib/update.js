/**
 * 插件更新检查 —— 只回答「我装的是最新版吗」，**不下载、不覆盖、不重启**。
 *
 * 为什么只做检查不做自动更新：
 * 本插件以 symlink 形式挂在 DSH profile 的 node_modules 里（`link:` 依赖）。
 * 自动改写一个正在被 DSH 加载的包，一旦半途失败，用户看不到「更新失败」，
 * 只会看到**整个通道面板消失**——这比旧版本更糟。所以这里只把事实摆出来：
 * 本地版本、远端最新版本、有没有新版、去哪里更新。
 *
 * 版本源按「国内可达性」排序，逐个降级：
 *   1. jsDelivr CDN 读公开仓的 package.json（国内基本可达，无 API 限流）；
 *   2. GitHub Releases API（本机可直连 api.github.com，但国内用户常不通）。
 * 两条都不通时如实报「检查失败」，绝不把旧数据当成结论。
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
 * 更新公告的来源：仓库里的 CHANGELOG.md，经 jsDelivr 取。
 * 不用 GitHub Release API 的 body 当公告，因为国内用户常打不开 api.github.com；
 * CHANGELOG 跟着仓库走，jsDelivr 在国内基本可达，且不受 API 限流影响。
 */
const CHANGELOG_URL =
  'https://cdn.jsdelivr.net/gh/yiheng-zyh/DSH-Pal@main/tools/pal-channel-plugin/CHANGELOG.md';

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
 * 比较两个 x.y.z 版本（允许 `v` 前缀与 `-alpha.1` 这类预发布后缀）。
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
    const dash = text.indexOf('-');
    const core = dash >= 0 ? text.slice(0, dash) : text;
    const pre = dash >= 0 ? text.slice(dash + 1) : '';
    return {
      parts: core.split('.').map((part) => {
        const n = Number.parseInt(part, 10);
        return Number.isFinite(n) ? n : 0;
      }),
      pre,
    };
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

/** 版本源：每个源负责把它的响应体变成版本号字符串，不合法返回 null。 */
const SOURCES = [
  {
    id: 'jsdelivr',
    url: 'https://cdn.jsdelivr.net/gh/yiheng-zyh/DSH-Pal@main/tools/pal-channel-plugin/package.json',
    pick: (body) => (typeof body?.version === 'string' ? body.version : null),
  },
  {
    id: 'github',
    url: 'https://api.github.com/repos/yiheng-zyh/DSH-Pal/releases/latest',
    pick: (body) => {
      const tag = typeof body?.tag_name === 'string' ? body.tag_name : '';
      return tag ? tag.replace(/^v/i, '') : null;
    },
  },
];

/** 版本号粗校验：至少 x.y.z 三段数字。 */
const VERSION_PATTERN = /^\d+\.\d+\.\d+/;

/** 拉取 CHANGELOG 并解析出目标版本的公告；任何失败都降级成空公告，不影响版本结论。 */
async function fetchChangelog(fetchImpl, version, timeoutMs) {
  try {
    const response = await fetchImpl(CHANGELOG_URL, {
      signal: AbortSignal.timeout(timeoutMs),
      headers: { accept: 'text/plain', 'user-agent': 'dsh-pal-channel' },
    });
    if (!response.ok) return { lines: [] };
    const text = await response.text();
    return { lines: parseChangelog(text, version) };
  } catch {
    return { lines: [] };
  }
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
 * @param options.fetchImpl - 测试注入；默认全局 fetch
 * @param options.local - 覆盖本地版本（测试用）
 * @param options.timeoutMs - 单个源超时
 */
export async function checkUpdate({ fetchImpl = fetch, local, timeoutMs = TIMEOUT_MS } = {}) {
  const current = local ?? (await localVersion());
  const failures = [];
  for (const source of SOURCES) {
    try {
      const response = await fetchImpl(source.url, {
        signal: AbortSignal.timeout(timeoutMs),
        headers: { accept: 'application/json', 'user-agent': 'dsh-pal-channel' },
      });
      if (!response.ok) {
        failures.push(`${source.id} HTTP ${response.status}`);
        continue;
      }
      const latest = source.pick(await response.json());
      if (typeof latest !== 'string' || !VERSION_PATTERN.test(latest)) {
        failures.push(`${source.id} 版本号不合法`);
        continue;
      }
      const notes = await fetchChangelog(fetchImpl, latest, timeoutMs);
      return {
        ok: true,
        local: current,
        latest,
        updateAvailable: current === null ? null : compareVersions(latest, current) > 0,
        source: source.id,
        // 公告取不到不影响「有没有新版」的结论：分开报，界面可以只显示版本。
        notes: notes.lines,
        notesAvailable: notes.lines.length > 0,
        releaseUrl: RELEASE_URL,
        checkedAt: new Date().toISOString(),
      };
    } catch (error) {
      // 网络不可达、超时、JSON 解析失败都只算这个源失败，继续下一个。
      failures.push(`${source.id} ${String(error?.message ?? error).slice(0, 80)}`);
    }
  }
  return {
    ok: false,
    local: current,
    error: failures.join('；') || '没有可用的版本源',
    releaseUrl: RELEASE_URL,
    checkedAt: new Date().toISOString(),
  };
}