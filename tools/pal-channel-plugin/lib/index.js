/**
 * DSH Pal 公网通道切换 —— Cordis host 插件入口。
 *
 * 解决什么问题：
 * `@linxin666/dsh-remote-web-ui` 提供两条公网通道，靠两个字段决定走哪条：
 *   autoTunnel=false + publicBaseUrl=https://xxx  → 自建国内 frp 隧道
 *   autoTunnel=true  + publicBaseUrl=""           → 插件自带的 Cloudflare Quick 隧道
 * **插件规则是「publicBaseUrl 填了就用它，优先于 autoTunnel」**，所以切到 Cloudflare
 * 时必须把 publicBaseUrl 清空，否则二维码永远指向旧入口。这套判断由
 * `tools/remote-access/channel-plan.mjs` 的纯函数负责，本插件不重复实现。
 *
 * 本插件只做三件事：
 *   1. 把「当前走哪条通道」读出来（GET /api/pal-channel/status）；
 *   2. 把「切到另一条」这件事安全地做完（POST /api/pal-channel/switch）；
 *   3. 用安全边界把写接口锁死在本机桌面，远程配对设备一律拒绝。
 *
 * 为什么不用 `ctx.on("api/gate", ...)` 当主挂载点：
 * 那个事件在官方 cohort 里**没有任何 emitter**（上游插件自己的 lib/types/gate.js 里
 * 明写 "Cohort reality (0.1.2-alpha.2): NOTHING emits api/gate in the official runtime,
 * so this listener never fires there"）。把功能挂在一条永不触发的事件上，等于交付一个
 * 永远不工作的按钮。所以这里按官方认可的方式挂：`ctx.connection.fetch.register`
 * —— 这是 `@deepseek-ai/dsh-client-connection` 真实的 Fetch 路由注册表，
 * 请求会经过它的 Host/Origin 围栏与浏览器认证。`api/gate` 监听器同时保留，
 * 以便在将来真的启用该 seam 的 cohort 上仍然生效（见 registerApiGate）。
 *
 * @module dsh-pal-channel
 */
import fs from 'node:fs/promises';
import { backupEncrypted } from '../../remote-access/windows.mjs';
import { isDesktopDirectRequest, isLocalStatusRequest } from './fence.js';
import { preflightDomesticOrigin } from './preflight.js';
import { reconcileTunnel } from './tunnel.js';
import {
  channelCatalog, findRemoteEntry, messageOf, readStatus, switchChannel,
} from './channel-service.js';
import {
  buildChannelOverview, collectDirect, collectMesh, globalIpv6Addresses,
} from './overview.js';
import { setDirect } from './direct-service.js';
import { readCustomOrigin, setCustomOrigin } from './custom-origin-service.js';
import { checkUpdate, localVersion } from './update.js';
import { applyUpdate } from './update-service.js';

/** Cordis 插件名。 */
export const name = 'pal-channel';

/**
 * 硬依赖。
 * - `connection`：Fetch 路由注册表（走它的 /api 通道，不自己注册裸 HTTP 路由）。
 * - `profileContext`：拿到本次启动的 profile patch 路径（`patchPath`）。
 *
 * 刻意**不**依赖 `configEditor`：本插件的写路径不走它（理由见 channel-service.js
 * 文件头：它会把 config 块整体重建成新 YAML 节点，丢掉 `# DSH Pal remote-access
 * managed` 这类语义性注释标记）。把它写成硬依赖会让本插件在缺少它的运行时里
 * 连只读状态都不可用，得不偿失。
 */
export const inject = ['connection', 'profileContext'];

/** 两条 API 路径。 */
export const STATUS_PATH = '/api/pal-channel/status';
export const SWITCH_PATH = '/api/pal-channel/switch';
/** 浏览器半上报失败用的只写端点（见下方 client-log 注册处）。 */
export const CLIENT_LOG_PATH = '/api/pal-channel/client-log';
/**
 * 连接通道总览。用户要回答的问题只有一句：「我到底有哪几条路能到电脑，
 * 每条现在是什么状态」。数据源见 lib/overview.js。
 */
export const OVERVIEW_PATH = '/api/pal-channel/overview';
/** 直连入口开关（写）。语义与 configure-direct.mjs 一致：请求体是完整期望集合。 */
export const DIRECT_PATH = '/api/pal-channel/direct';
/** 自定义 HTTPS 入口（读写）。用户有自己的域名/反代时走这条，不依赖 Sakura。 */
export const CUSTOM_ORIGIN_PATH = '/api/pal-channel/custom-origin';
/** 插件更新检查（只读，不下载不覆盖）。 */
export const UPDATE_PATH = '/api/pal-channel/update';
/** 应用更新（写）：git pull --ff-only。与检查分开，避免"打开面板就在改东西"。 */
export const UPDATE_APPLY_PATH = '/api/pal-channel/update/apply';

/**
 * 插件自己的诊断日志。
 *
 * DSH 加载插件失败是**静默的**（重启后照常启动、不报错），浏览器半的
 * console.error 用户也看不到。所以把加载事实与前端失败都写到独立文件里，
 * 排查时不必依赖 DSH 的输出。
 */
const LOG_PATH = '<工作区>/sessions/pal-channel-load.log';

/** 响应头：这些接口只该给同一个页面用，禁止缓存、禁止被当作别处引用。 */
const JSON_HEADERS = {
  'content-type': 'application/json; charset=utf-8',
  'cache-control': 'no-store',
  'referrer-policy': 'no-referrer',
};

/** 构造 JSON 响应。 */
function json(status, body) {
  return new Response(JSON.stringify(body), { status, headers: JSON_HEADERS });
}

/** 只接受 application/json，挡掉表单式跨站提交。 */
function isJsonRequest(request) {
  const type = request.headers.get('content-type');
  return typeof type === 'string' && type.split(';', 1)[0].trim().toLowerCase() === 'application/json';
}

/** 有上限地读 JSON body（这个接口只需要一个字符串字段）。 */
async function readJsonBody(request, maxBytes = 4096) {
  const text = await request.text();
  if (Buffer.byteLength(text, 'utf8') > maxBytes) return undefined;
  try {
    const value = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

/**
 * 把 Host 侧的 node:http 请求事实补进 fetch 请求。
 *
 * `connection.fetch.register` 回调拿到的是 WHATWG Request —— 它**没有 socket**，
 * 而「是不是本机」必须看 socket 对端地址。所以这里从注入的 ProfileContext
 * 之外再借一层：`ctx.connection` 的 fetch 路由在 `createSharedFetchHandler` 里
 * 是**先**由 `admit(request)` 判定过 Host 围栏与浏览器认证才调用的
 * （见 client-connection 的 `createSharedFetchHandler`：非 owner peer 直接 403）。
 * 也就是说走到这里时身份已经是 owner（本机浏览器），我们在此基础上再叠加
 * 「不是经 /remote 转发进来的」这一条。
 */

/**
 * 从 WHATWG Request 里取出头（小写化，与 node 的 req.headers 同形）。
 *
 * 为什么用头而不用 socket：`connection.fetch.register` 的回调只给 WHATWG Request，
 * 它**没有 socket**。所幸本插件要判断的那条边界（`/remote` 转发的头特征，
 * 见 fence.js 文件头）完全落在请求头上，不依赖 socket 地址。
 * @param request - WHATWG Request
 * @returns 小写键的普通对象
 */
function headersObject(request) {
  const out = {};
  for (const [key, value] of request.headers.entries()) out[key.toLowerCase()] = value;
  return out;
}

/**
 * 插件主体。
 * @param ctx - Cordis host 上下文。
 * @param config - 插件配置（当前无字段，保留以兼容 Loader）。
 */
/**
 * 插件主体（内部实现）。
 *
 * 外层 `apply` 只是包一层异常捕获：DSH / Cordis 会把插件 apply 抛出的异常
 * 静默吞掉，既不报错也不影响启动 —— 表现就是「插件加载了但功能全无」。
 * 2026-11-04 实测：apply 里某一步抛了异常，日志只留下 "apply() called"，
 * 后面的 "routes registered" 永远不出现，而 DSH 一切正常。
 *
 * 包一层之后：任何失败都会写进 pal-channel-load.log（含错误信息与栈），
 * 下次重启就能直接看到根因，不用再靠猜。
 */
export function apply(ctx, config = {}) {
  try {
    applyUnsafe(ctx, config);
  } catch (error) {
    import('node:fs').then(({ appendFileSync }) => {
      try {
        appendFileSync(
          '<工作区>/sessions/pal-channel-load.log',
          `${new Date().toISOString()} APPLY FAILED ${error?.name || 'Error'}: ${error?.message || error}\n` +
            `${String(error?.stack || '').split('\n').slice(0, 6).join('\n')}\n`,
        );
      } catch {}
    }).catch(() => {});
    throw error;
  }
}

function applyUnsafe(ctx, config = {}) {
  void config;

  // 落地一行加载证据：DSH 加载插件失败是**静默的**（重启后照常启动、不报错），
  // 所以必须有独立于 DSH 的痕迹才能判断"到底加载了没有"。
  // 这是排查手段，不是业务逻辑；写失败绝不能影响插件工作。
  try {
    // 动态 import 更稳：本文件是 ESM，顶层 require 不存在。
    import('node:fs').then(({ appendFileSync }) => {
      try {
        appendFileSync(
          '<工作区>/sessions/pal-channel-load.log',
          `${new Date().toISOString()} apply() called\n`,
        );
      } catch {
        // 日志写不了就算了。
      }
    }).catch(() => {});
  } catch {
    // 同上。
  }

  const profile = () => ctx.get('profileContext');
  const patchPath = () => {
    const fact = profile();
    return typeof fact?.patchPath === 'string' ? fact.patchPath : '';
  };

  /**
   * 国内入口地址来自 remote-access 的 config.json（publicOrigin），
   * 那是同一条功能的既有配置源 —— 不另存一份，避免两处不一致。
   */
  const domesticOriginOf = async () => {
    const directory = process.env.LOCALAPPDATA
      ? `${process.env.LOCALAPPDATA}\\DSHPal\\remote-access`
      : '';
    if (directory === '') return '';
    try {
      const raw = await fs.readFile(`${directory}\\config.json`, 'utf8');
      const parsed = JSON.parse(raw);
      return typeof parsed?.publicOrigin === 'string' ? parsed.publicOrigin : '';
    } catch {
      // 没配 remote-access 时就是空：国内通道显示为不可用，而不是猜一个地址。
      return '';
    }
  };

  const entryOf = () => {
    try {
      return findRemoteEntry(ctx.loader?.entries?.() ?? []);
    } catch {
      return undefined;
    }
  };

  /** 组装 channel-service 需要的依赖。 */
  const depsFor = async () => {
    const domesticOrigin = await domesticOriginOf();
    return {
      patchPath: patchPath(),
      domesticOrigin,
      entry: entryOf,
      backupEncrypted,
      preflight: preflightDomesticOrigin,
      // 切换后同步启停「另一套」隧道：本机有两套独立的公网隧道
      // （自建 run.mjs+frpc、插件自带 cloudflared），只改配置不会停掉另一条，
      // 用户会看到「切了但上传带宽仍是国内通道」。详见 lib/tunnel.js 文件头。
      reconcileTunnel,
    };
  };

  // ---- 路由注册 ------------------------------------------------------------
  const disposers = [];

  // 先探一下依赖到底在不在。DSH 的不同 cohort 暴露的服务名不完全一样，
  // 猜错就会在下面 register 时抛异常，而 Cordis 会静默吞掉 ——
  // 表现是「插件加载了（apply 被调）但路由没注册」，极难定位（2026-11-04 实测踩到）。
  // 这段把事实写进日志，让下一次排查不用再猜。
  import('node:fs').then(({ appendFileSync }) => {
    try {
      const facts = {
        hasConnection: Boolean(ctx.connection),
        hasFetch: Boolean(ctx.connection?.fetch),
        hasRegister: typeof ctx.connection?.fetch?.register === 'function',
        hasProfileContext: Boolean(ctx.get?.('profileContext')),
        hasLoader: Boolean(ctx.loader),
        at: new Date().toISOString(),
      };
      appendFileSync('<工作区>/sessions/pal-channel-load.log', `deps ${JSON.stringify(facts)}\n`);
    } catch {}
  }).catch(() => {});

  disposers.push(ctx.connection.fetch.register({
    path: STATUS_PATH,
    methods: ['GET'],
    requestBody: 'buffered',
    fetch: async (request) => {
      const headers = headersObject(request);
      // 只读接口：本机（无 /remote 转发痕迹）才给。远程配对设备读不到通道状态，
      // 因为它本来也改不了——少给一条信息就少一条被社工利用的线索。
      if (!isLocalStatusRequest(headers)) {
        return json(403, { ok: false, code: 'local-only', error: '通道状态只对本机桌面开放' });
      }
      const deps = await depsFor();
      try {
        const status = await readStatus(deps);
        return json(200, { ok: true, ...status });
      } catch (error) {
        return json(500, { ok: false, code: error?.code ?? 'status-failed', error: messageOf(error) });
      }
    },
  }));

  disposers.push(ctx.connection.fetch.register({
    path: SWITCH_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: async (request) => {
      // 写接口：必须本机桌面直连。远程配对设备经 /remote 转发进来的请求
      // 会被 fence 挡掉（判据见 fence.js 文件头）。
      if (!isDesktopDirectRequest(headersObject(request))) {
        // 被拦时把真实请求头写进日志：桌面版 DSH 用的是 dsh:// 自定义协议，
        // Host / Origin / sec-fetch-* 的实际形态可能与浏览器不同，
        // 不看真实头就只能在"猜判据"。脱敏后再落盘（去掉 cookie / token）。
        try {
          const raw = headersObject(request);
          const safe = {};
          for (const [k, v] of Object.entries(raw)) {
            if (/cookie|authorization|token|secret/i.test(k)) continue;
            safe[k] = String(v).slice(0, 120);
          }
          await fs.appendFile(
            LOG_PATH,
            `${new Date().toISOString()} FENCE-REJECT ${JSON.stringify(safe)}\n`,
          );
        } catch {
          // 诊断写不了不能影响响应。
        }
        return json(403, {
          ok: false,
          code: 'desktop-only',
          error: '通道切换只允许在本机桌面操作（远程设备没有改对外入口的权限）',
        });
      }
      if (!isJsonRequest(request)) {
        return json(415, { ok: false, code: 'bad-content-type', error: 'content-type 必须是 application/json' });
      }
      const body = await readJsonBody(request);
      if (body === undefined) {
        return json(400, { ok: false, code: 'bad-body', error: '请求体必须是不超过 4KB 的 JSON 对象' });
      }
      const deps = await depsFor();
      try {
        const result = await switchChannel(deps, body.channel);
        return json(result.status, result.body);
      } catch (error) {
        // 兜底：任何未预料的异常都必须变成失败响应，绝不允许静默吞掉后报成功。
        return json(500, { ok: false, code: 'internal', error: messageOf(error) });
      }
    },
  }));

  // ---- 浏览器半的失败落盘端点 ----------------------------------------------
  /**
   * 浏览器半的 console.error 用户在界面上看不到，面板"静默消失"时就无从排查。
   * 这个只写端点接收前端上报的失败阶段与栈，追加到同一个加载日志里。
   *
   * 安全：只接受本机桌面直连（复用与 switch 相同的 fence），且只写日志、不读配置，
   * 所以即使被滥用也改不了任何状态。payload 也做了长度截断。
   */
  disposers.push(ctx.connection.fetch.register({
    path: CLIENT_LOG_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isDesktopDirectRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'desktop-only' });
      }
      try {
        const raw = await request.text();
        const body = JSON.parse(raw || '{}');
        const stage = String(body?.stage ?? '').slice(0, 80);
        const detail = String(body?.detail ?? '').slice(0, 800);
        await fs.appendFile(
          LOG_PATH,
          `${new Date().toISOString()} CLIENT ${stage || 'unknown'}: ${detail}\n`,
        );
        return json(200, { ok: true });
      } catch (error) {
        return json(500, { ok: false, error: messageOf(error) });
      }
    },
  }));

  // 组网探测要起子进程查 tailscale / sc，40~500ms 一次；面板每 20 秒轮询，
// 60 秒缓存既够及时也不至于把进程表刷爆。
  const MESH_CACHE_MS = 60 * 1000;
  let meshCache = { at: 0, value: null };
  const meshSnapshotCached = async () => {
    if (meshCache.value && Date.now() - meshCache.at < MESH_CACHE_MS) return meshCache.value;
    const value = await collectMesh();
    meshCache = { at: Date.now(), value };
    return value;
  };

  // ---- 连接通道总览（只读）--------------------------------------------------
  /**
   * 为什么单独一条端点而不是塞进 /status：
   * - `/status` 回答的是「当前走哪条隧道」，是切换动作的输入，字段少、要快；
   * - 总览要把直连开关/监听、网关健康、本机 IPv6 体检一起纳入，读的是另一批
   *   数据源（文件 + 网卡枚举），失败模式也不同。
   * 合并成一条的后果是：任一诊断数据源卡住或报错，整块设置面板跟着不显示。
   * 这里分开，总览读失败也只影响它自己。
   */
  disposers.push(ctx.connection.fetch.register({
    path: OVERVIEW_PATH,
    methods: ['GET'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isLocalStatusRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'local-only', error: '通道总览只对本机桌面开放' });
      }
      // 隧道侧读不到（patch 路径缺失、YAML 改了语法等）不该让总览整块消失：
      // 那条通道显示为不可用，其余通道照常展示。
      let tunnel = { channel: null, channels: [] };
      let tunnelError;
      try {
        tunnel = await readStatus(await depsFor());
      } catch (error) {
        tunnelError = messageOf(error);
      }
      try {
        const directSnapshot = await collectDirect();
        // 组网探测要跑两条命令（tailscale status / sc query），60 秒缓存足够：
        // 用户不会一分钟内反复登录/退出 Tailscale，但面板每 20 秒就轮询一次。
        const meshSnapshot = await meshSnapshotCached();
        // 自定义入口也要进总览：用户配了自己的域名就该在这里看到它生效了。
        const custom = await readCustomOrigin();
        const overview = buildChannelOverview({
          tunnel,
          directSnapshot,
          addresses: globalIpv6Addresses(),
          meshSnapshot,
          custom,
        });
        // 版本号一并给出：面板要在同一个响应里同时回答「通道状态」和「插件版本」。
        // 读不到版本就 null，界面显示「—」，不编造。
        return json(200, { ok: true, ...overview, pluginVersion: await localVersion(), tunnelError });
      } catch (error) {
        return json(500, { ok: false, code: 'overview-failed', error: messageOf(error) });
      }
    },
  }));

  // ---- 直连入口开关（写）----------------------------------------------------
  /**
   * 写路径必须过 `isDesktopDirectRequest`（与切换隧道同一道围栏）：
   * 远程配对设备本来就不该有能力改动这台电脑的对外监听面。
   */
  disposers.push(ctx.connection.fetch.register({
    path: DIRECT_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isDesktopDirectRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'desktop-only', error: '直连开关只允许在本机桌面操作' });
      }
      if (!isJsonRequest(request)) {
        return json(415, { ok: false, code: 'bad-content-type', error: 'content-type 必须是 application/json' });
      }
      const body = await readJsonBody(request);
      if (body === undefined) {
        return json(400, { ok: false, code: 'bad-body', error: '请求体必须是不超过 4KB 的 JSON 对象' });
      }
      try {
        const result = await setDirect(body);
        return json(result.status, result.body);
      } catch (error) {
        return json(500, { ok: false, code: 'internal', error: messageOf(error) });
      }
    },
  }));

  // ---- 插件更新检查（只读）--------------------------------------------------
  /**
   * 心跳检查会每隔几分钟就调一次这个端点，所以结果必须缓存：
   * 不缓存的话每台装机每 10 分钟就要去打一次 jsDelivr/GitHub，既没必要也容易被限流。
   * 缓存 30 分钟；带 `?fresh=1` 可以绕过（面板上「检查更新」按钮用）。
   */
  const UPDATE_CACHE_MS = 30 * 60 * 1000;
  let updateCache = { at: 0, value: null };
  disposers.push(ctx.connection.fetch.register({
    path: UPDATE_PATH,
    methods: ['GET'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isLocalStatusRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'local-only', error: '更新检查只对本机桌面开放' });
      }
      try {
        let fresh = false;
        try {
          fresh = new URL(request.url).searchParams.get('fresh') === '1';
        } catch {
          // URL 解析失败就当普通缓存请求，不影响结论。
        }
        if (!fresh && updateCache.value && Date.now() - updateCache.at < UPDATE_CACHE_MS) {
          return json(200, { ...updateCache.value, cached: true });
        }
        const value = await checkUpdate();
        updateCache = { at: Date.now(), value };
        return json(200, { ...value, cached: false });
      } catch (error) {
        return json(500, { ok: false, code: 'update-failed', error: messageOf(error) });
      }
    },
  }));

  // ---- 自定义 HTTPS 入口（读写）----------------------------------------------
  /**
   * 用户已经有自己的 HTTPS 域名/反代时，用这条代替 Sakura。
   * 写路径必须过桌面围栏：改对外入口等于改"手机从哪里连进来"，不能让远程设备做。
   */
  disposers.push(ctx.connection.fetch.register({
    path: CUSTOM_ORIGIN_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isDesktopDirectRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'desktop-only', error: '自定义入口只允许在本机桌面设置' });
      }
      if (!isJsonRequest(request)) {
        return json(415, { ok: false, code: 'bad-content-type', error: 'content-type 必须是 application/json' });
      }
      const body = await readJsonBody(request);
      if (body === undefined) {
        return json(400, { ok: false, code: 'bad-body', error: '请求体必须是不超过 4KB 的 JSON 对象' });
      }
      try {
        const result = await setCustomOrigin(body);
        return json(result.status, result.body);
      } catch (error) {
        return json(500, { ok: false, code: 'internal', error: messageOf(error) });
      }
    },
  }));

  // ---- 应用更新（写）--------------------------------------------------------
  /**
   * 更新是写操作，必须本机桌面直连。更新方式与安全边界见 lib/update-service.js：
   * 整仓 `git pull --ff-only`，脏工作区直接拒绝。更新完成后**不重启 DSH** ——
   * 在插件自己的请求里重启，响应会断在半路，用户看不到结果。
   */
  disposers.push(ctx.connection.fetch.register({
    path: UPDATE_APPLY_PATH,
    methods: ['POST'],
    requestBody: 'buffered',
    fetch: async (request) => {
      if (!isDesktopDirectRequest(headersObject(request))) {
        return json(403, { ok: false, code: 'desktop-only', error: '应用更新只允许在本机桌面操作' });
      }
      try {
        const result = await applyUpdate();
        return json(result.status, result.body);
      } catch (error) {
        return json(500, { ok: false, code: 'internal', error: messageOf(error) });
      }
    },
  }));

  // ---- api/gate：为将来启用该 seam 的 cohort 保留 --------------------------
  /**
   * 上游把 `api/gate` 当作「配对/吊销的官方挂载点」，但当前官方运行时没有 emitter。
   * 这里按同样形态挂监听器：对**本插件自己那两条路径**额外把远程请求拒掉。
   * 即便将来官方真的开始 emit，本插件的安全属性也不会退化。
   */
  const gate = (request, _method, next) => {
    const path = typeof request?.url === 'string' ? request.url.split('?', 1)[0] : '';
    const ours =
      path === STATUS_PATH ||
      path === SWITCH_PATH ||
      path === OVERVIEW_PATH ||
      path === DIRECT_PATH ||
      path === CUSTOM_ORIGIN_PATH ||
      path === UPDATE_PATH ||
      path === UPDATE_APPLY_PATH;
    if (!ours) return next();
    const headers = request?.headers ?? {};
    // 写接口（switch / direct / 自定义入口 / 应用更新）要桌面直连；只读接口本机即可。
    const write = path === SWITCH_PATH || path === DIRECT_PATH || path === CUSTOM_ORIGIN_PATH || path === UPDATE_APPLY_PATH;
    const local = write ? isDesktopDirectRequest(headers) : isLocalStatusRequest(headers);
    return local ? next() : false;
  };
  ctx.effect(() => ctx.on('api/gate', gate), 'pal-channel: api gate');

  ctx.effect(() => () => {
    for (const dispose of disposers.splice(0)) {
      try {
        dispose();
      } catch (error) {
        // 卸载时单个路由取消失败不该影响其余清理。
        ctx.logger?.warn?.(`pal-channel: 取消失败：${messageOf(error)}`);
      }
    }
  }, 'pal-channel: api routes');

  // 注册成功的落盘证据：DSH 的插件 stdout 不一定拿得到，
  // 写到文件才能在重启后确认「apply 走完了、两条路由都注册了」。
  // 用动态 import().then 而不是 await：apply 是同步函数，
  // 加 async 会改变 Cordis 对它的调用时机（可能被当成 Promise 处理），不值得。
  import('node:fs').then(({ appendFileSync }) => {
    try {
      appendFileSync(
        '<工作区>/sessions/pal-channel-load.log',
        `${new Date().toISOString()} routes registered ${STATUS_PATH} ${SWITCH_PATH}\n`,
      );
    } catch {
      // 日志写不了不影响功能。
    }
  }).catch(() => {});

  ctx.logger?.info?.('pal-channel: 通道切换 API 已挂载（%s, %s）', STATUS_PATH, SWITCH_PATH);
}

export { channelCatalog };
export default { name, inject, apply };
