/**
 * DSH Pal 通道切换 —— 浏览器半边。
 *
 * 本文件对齐**本机已正常工作**的 dsh-plugin-workbuddy 的写法（2026-11-04）：
 *   1. `window.__ModuleLoader__.load({ id, factory })`
 *   2. factory 里 `var module = { exports: {} }`
 *   3. **exports.apply / exports.inject 挂 exports 上**，然后 `return module.exports`
 *   4. 注册必须包在 `ctx.slots.inject('<槽位>', () => ctx.slots.register(...))` 里 ——
 *      workbuddy 注释原话：「注册一个还没声明的槽会直接抛错、整个纤维失败，
 *      面板就静默消失了」。这正是前几版"注册了但界面没东西"的根因。
 *   5. 槽位绑定键是 `name`，不是 `key`。
 *
 * 浏览器半的 console.error 用户在界面上看不到，所以所有失败都通过
 * /api/pal-channel/client-log 落到 host 的日志文件里，避免再次静默失败。
 */
window.__ModuleLoader__.load({
  id: "dsh-pal-channel",
  factory: function (require) {
    "use strict";
    var module = { exports: {} };
    var exports = module.exports;

    var React;
    try {
      React = require("react");
    } catch (error) {
      React = globalThis.React;
      if (!React) throw new Error("dsh-pal-channel: react unavailable");
    }

    var NS = "pal-channel";
    var zh = {
      nav: "公网入口",
      hint:
        "公网入口决定手机从外面怎么连到这台电脑。两条通道由同一个远程访问插件提供，" +
        "切换只改它公布的地址，不影响已配对的设备。",
      loading: "读取中…",
      unavailable: "未获取到通道状态（host 半可能未就绪）",
      domestic: "国内 frp（推荐）",
      domesticHint: "走自建隧道，国内速度快，出门也能连",
      cloudflare: "Cloudflare 隧道",
      cloudflareHint: "走 Cloudflare，域名固定但延迟较高",
      current: "当前",
      switching: "切换中…",
      switched: "已切换，重启 DSH 后生效",
      failed: "切换失败",
      address: "当前公布地址",
      channelsNav: "连接通道总览",
      channelsHint:
        "这台电脑目前有哪几条路能让手机连上、各自现在什么状态。状态来自网关实测与文件事实，" +
        "不是猜的：直连看「网关有没有真的绑上监听」，隧道看「当前配置走哪条」。",
      channelsLoading: "读取通道状态…",
      channelsUnavailable: "暂时读不到通道状态（网关或插件未就绪）",
      phaseCurrent: "当前使用",
      phaseReady: "已开启",
      phaseAvailable: "可切换",
      phaseUnconfigured: "未配置",
      phasePending: "已开启但未监听",
      phaseOff: "未开启",
      phaseAppManaged: "手机端管理",
      groupPublic: "公网",
      groupDirect: "直连",
      groupMesh: "组网",
      turnOn: "开启",
      turnOff: "关闭",
      working: "处理中…",
      ipv6Health: "本机公网 IPv6",
      gatewayHealth: "remote-access 网关",
      gatewayRunning: "运行中",
      gatewayStopped: "未运行",
      gatewayMissing: "未安装/未配置",
      ipv6Count: "个公网地址",
      ipv6None: "没有公网 IPv6",
      versionLabel: "插件版本",
      checkUpdate: "检查更新",
      checkingUpdate: "检查中…",
      updateLatest: "已是最新",
      updateAvailable: "有新版本",
      updateFailed: "检查失败（网络不可达，可稍后重试）",
      openRelease: "打开发布页",
      directToggleHint: "开关会写 remote-access 配置，网关 5 秒内热生效，不需要重启 DSH。",
      notesTitle: "更新内容",
      updateNow: "立即更新",
      confirmUpdate: "确认更新（会整仓拉取代码）",
      updating: "更新中…",
      updateDone: "已更新，重启 DSH 后生效",
      updateNoChange: "已是最新，没有文件变化",
      updateCancelled: "已取消（有未提交改动，或不是 git 安装）",
      cancel: "取消",
      noticeTitle: "DSH Pal 通道插件有更新",
      noticeMeta: "当前版本",
      noticeLater: "稍后",
      noticeUpdate: "立即更新",
      noticeUpdating: "更新中…",
      noticeDone: "已更新，重启 DSH 后生效",
      noticeUpToDate: "已是最新版本",
      switchTo: "切到这条",
    };
    var en = {
      nav: "Public entry",
      hint:
        "The public entry decides how your phone reaches this computer from outside. " +
        "Switching only changes the published address and keeps paired devices.",
      loading: "Reading…",
      unavailable: "Channel status unavailable",
      domestic: "Domestic frp (recommended)",
      domesticHint: "Self-hosted tunnel, fast in China",
      cloudflare: "Cloudflare tunnel",
      cloudflareHint: "Cloudflare relay, higher latency",
      current: "Current",
      switching: "Switching…",
      switched: "Switched. Restart DSH to take effect",
      failed: "Switch failed",
      address: "Published address",
      channelsNav: "Connection channels",
      channelsHint:
        "Which routes can reach this computer right now, and what state each one is in. " +
        "Direct links report what the gateway actually bound; tunnels report the configured route.",
      channelsLoading: "Reading channels…",
      channelsUnavailable: "Channel status unavailable (gateway or plugin not ready)",
      phaseCurrent: "In use",
      phaseReady: "On",
      phaseAvailable: "Available",
      phaseUnconfigured: "Not configured",
      phasePending: "On but not listening",
      phaseOff: "Off",
      phaseAppManaged: "Managed on phone",
      groupPublic: "Public",
      groupDirect: "Direct",
      groupMesh: "Mesh",
      turnOn: "Turn on",
      turnOff: "Turn off",
      working: "Working…",
      ipv6Health: "Public IPv6 on this PC",
      gatewayHealth: "remote-access gateway",
      gatewayRunning: "Running",
      gatewayStopped: "Not running",
      gatewayMissing: "Not installed/configured",
      ipv6Count: "public addresses",
      ipv6None: "no public IPv6",
      versionLabel: "Plugin version",
      checkUpdate: "Check for updates",
      checkingUpdate: "Checking…",
      updateLatest: "Up to date",
      updateAvailable: "Update available",
      updateFailed: "Check failed (network unreachable, retry later)",
      openRelease: "Open release page",
      directToggleHint:
        "Toggling writes the remote-access config; the gateway hot-reloads within 5s, no DSH restart needed.",
      notesTitle: "What's new",
      updateNow: "Update now",
      confirmUpdate: "Confirm update (pulls the repo)",
      updating: "Updating…",
      updateDone: "Updated. Restart DSH to take effect",
      updateNoChange: "Already up to date",
      updateCancelled: "Cancelled (uncommitted changes or not a git install)",
      cancel: "Cancel",
      noticeTitle: "DSH Pal channel plugin update available",
      noticeMeta: "Current version",
      noticeLater: "Later",
      noticeUpdate: "Update now",
      noticeUpdating: "Updating…",
      noticeDone: "Updated. Restart DSH to take effect",
      noticeUpToDate: "Already up to date",
      switchTo: "Switch to this",
    };

    var t = function (k) {
      return k;
    };
    var setT = function (fn) {
      t = fn;
    };

    // 样式一律走 --dsw-alias-* 令牌（官方样式契约），字面量只作老宿主兜底。
    var S = {
      box: { padding: "4px 2px", color: "var(--dsw-alias-label-primary, #c9d1d9)" },
      hint: {
        fontSize: "12.5px",
        lineHeight: "1.65",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
        margin: "0 0 12px",
      },
      option: {
        display: "block",
        width: "100%",
        textAlign: "left",
        padding: "11px 14px",
        marginBottom: "8px",
        borderRadius: "8px",
        border: "1px solid var(--dsw-alias-border-l1, #30363d)",
        background: "transparent",
        color: "inherit",
        font: "inherit",
        cursor: "pointer",
      },
      optionActive: {
        border: "1px solid var(--dsw-alias-button-primary-fill, #4d6bfe)",
        background: "var(--dsw-alias-bg-layer-1, #0d1117)",
      },
      title: { display: "block", fontSize: "14px" },
      sub: {
        display: "block",
        fontSize: "12px",
        marginTop: "2px",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
      },
      meta: {
        fontSize: "12px",
        marginTop: "6px",
        wordBreak: "break-all",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
      },
      message: {
        fontSize: "12px",
        marginTop: "8px",
        color: "var(--dsw-alias-label-primary, #c9d1d9)",
      },
      error: { color: "var(--dsw-alias-state-error-primary, #f85149)" },
      rowBox: {
        padding: "9px 0",
        borderBottom: "1px solid var(--dsw-alias-border-l1, #21262d)",
      },
      noteLine: {
        fontSize: "12px",
        lineHeight: "1.7",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
        paddingLeft: "2px",
      },
      rowHead: {
        display: "flex",
        alignItems: "baseline",
        justifyContent: "space-between",
        gap: "8px",
      },
      rowName: { fontSize: "13.5px" },
      phase: {
        fontSize: "12px",
        whiteSpace: "nowrap",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
      },
      url: {
        fontSize: "11.5px",
        marginTop: "2px",
        wordBreak: "break-all",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
      },
      btn: {
        fontSize: "12px",
        padding: "3px 10px",
        marginTop: "6px",
        borderRadius: "6px",
        border: "1px solid var(--dsw-alias-border-l1, #30363d)",
        background: "transparent",
        color: "inherit",
        cursor: "pointer",
      },
      sectionTitle: {
        fontSize: "13px",
        fontWeight: "600",
        margin: "14px 0 4px",
      },
      // ---- 更新提醒弹层（shell.overlay）-----------------------------------
      // 宿主的 overlay 是定位容器，必须自己 fixed 居中。dsh-connect-workbuddy 的
      // 注释里记过一次教训：画在 overlay 里不定位，会停在左上角成一个"没人认识的框"。
      noticeLayer: {
        position: "fixed",
        inset: "0",
        background: "rgba(0,0,0,0.45)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        zIndex: 9999,
      },
      noticeCard: {
        width: "min(460px, calc(100vw - 32px))",
        maxHeight: "calc(100vh - 64px)",
        overflow: "auto",
        background: "var(--dsw-alias-bg-layer-1, #161b22)",
        border: "1px solid var(--dsw-alias-border-l1, #30363d)",
        borderRadius: "12px",
        padding: "18px",
        boxShadow: "0 20px 60px rgba(0,0,0,0.45)",
        color: "var(--dsw-alias-label-primary, #c9d1d9)",
      },
      noticeTitle: { fontSize: "15px", fontWeight: "600", margin: "0 0 6px" },
      noticeMeta: {
        fontSize: "12.5px",
        color: "var(--dsw-alias-label-secondary, #8b949e)",
        marginBottom: "10px",
      },
      noticeNotes: {
        maxHeight: "220px",
        overflowY: "auto",
        margin: "8px 0 12px",
        padding: "10px 12px",
        borderRadius: "8px",
        background: "var(--dsw-alias-bg-layer-2, rgba(255,255,255,0.03))",
        border: "1px solid var(--dsw-alias-border-l1, #21262d)",
      },
      noticeActions: {
        display: "flex",
        justifyContent: "flex-end",
        gap: "8px",
        marginTop: "12px",
      },
      primaryBtn: {
        fontSize: "13px",
        padding: "6px 14px",
        borderRadius: "7px",
        border: "1px solid var(--dsw-alias-button-primary-fill, #4d6bfe)",
        background: "var(--dsw-alias-button-primary-fill, #4d6bfe)",
        color: "#fff",
        cursor: "pointer",
      },
      linkBtn: {
        fontSize: "13px",
        padding: "6px 14px",
        borderRadius: "7px",
        border: "1px solid var(--dsw-alias-border-l1, #30363d)",
        background: "transparent",
        color: "inherit",
        cursor: "pointer",
      },
    };

    function readStatus() {
      return fetch("/api/pal-channel/status", {
        headers: { accept: "application/json" },
        credentials: "same-origin",
      })
        .then(function (r) {
          if (!r.ok) return null;
          return r.json().then(function (v) {
            return v && typeof v === "object" ? v : null;
          });
        })
        .catch(function () {
          return null;
        });
    }

    function requestSwitch(channel) {
      return fetch("/api/pal-channel/switch", {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ channel: channel }),
      })
        .then(function (r) {
          return r.json().then(
            function (v) {
              return { ok: r.ok, status: r.status, body: v };
            },
            function () {
              return { ok: r.ok, status: r.status, body: null };
            },
          );
        })
        .catch(function (e) {
          return { ok: false, status: 0, body: { error: String(e) } };
        });
    }

    /** 读连接通道总览（只读）。 */
    function readOverview() {
      return fetch("/api/pal-channel/overview", {
        headers: { accept: "application/json" },
        credentials: "same-origin",
      })
        .then(function (r) {
          if (!r.ok) return null;
          return r.json().then(function (v) {
            return v && typeof v === "object" ? v : null;
          });
        })
        .catch(function () {
          return null;
        });
    }

    /** 直连开关：body 是「完整期望集合」而不是增量 —— 与 configure-direct.mjs 同语义。 */
    function requestDirect(payload) {
      return fetch("/api/pal-channel/direct", {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        credentials: "same-origin",
        body: JSON.stringify(payload),
      })
        .then(function (r) {
          return r.json().then(
            function (v) {
              return { ok: r.ok, status: r.status, body: v };
            },
            function () {
              return { ok: r.ok, status: r.status, body: null };
            },
          );
        })
        .catch(function (e) {
          return { ok: false, status: 0, body: { error: String(e) } };
        });
    }

    /** 检查插件更新：只读远端版本号，绝不自动下载或覆盖本机文件。 */
    function requestUpdateCheck() {
      return fetch("/api/pal-channel/update", {
        headers: { accept: "application/json" },
        credentials: "same-origin",
      })
        .then(function (r) {
          return r.json().then(
            function (v) {
              return { ok: r.ok, status: r.status, body: v };
            },
            function () {
              return { ok: r.ok, status: r.status, body: null };
            },
          );
        })
        .catch(function (e) {
          return { ok: false, status: 0, body: { error: String(e) } };
        });
    }

    /** 应用更新：只在用户明确点了「立即更新」后调用。 */
    function requestUpdateApply() {
      return fetch("/api/pal-channel/update/apply", {
        method: "POST",
        headers: { "content-type": "application/json", accept: "application/json" },
        credentials: "same-origin",
        body: "{}",
      })
        .then(function (r) {
          return r.json().then(
            function (v) {
              return { ok: r.ok, status: r.status, body: v };
            },
            function () {
              return { ok: r.ok, status: r.status, body: null };
            },
          );
        })
        .catch(function (e) {
          return { ok: false, status: 0, body: { error: String(e) } };
        });
    }

    /** 状态点颜色只表达「真的不同」，不做装饰。 */
    var PHASE_COLOR = {
      current: "#3fb950",
      ready: "#3fb950",
      available: "#8b949e",
      unconfigured: "#6e7681",
      pending: "#d29922",
      off: "#6e7681",
      "app-managed": "#8b949e",
    };
    var PHASE_KEY = {
      current: "phaseCurrent",
      ready: "phaseReady",
      available: "phaseAvailable",
      unconfigured: "phaseUnconfigured",
      pending: "phasePending",
      off: "phaseOff",
      "app-managed": "phaseAppManaged",
    };

    /**
     * 连接通道总览面板。
     *
     * 为什么它和「公网入口」是两个区块而不是一个：
     * 公网入口回答「我现在走哪条隧道、要不要切」；总览回答「我到底有哪几条路、
     * 每条什么状态」。前者的动作是切换（会动配置），后者大部分只是看（只有直连
     * 开关会写配置）。混在一个区块里会让用户以为自己每次打开设置都在改东西。
     */
    // ---- 更新提醒：心跳检查 + 前台弹层 ------------------------------------
    /** 客户端心跳间隔。服务端缓存 30 分钟，所以实际外网请求频率远低于此。 */
    var UPDATE_HEARTBEAT_MS = 10 * 60 * 1000;
    /** 「稍后」只压住这一个版本；下次发新版会再弹，不会永久静默。 */
    var DISMISS_KEY = "dsh-pal-channel:dismissed-version";

    function readDismissed() {
      try {
        return window.localStorage.getItem(DISMISS_KEY) || "";
      } catch (error) {
        return "";
      }
    }
    function writeDismissed(version) {
      try {
        if (version) window.localStorage.setItem(DISMISS_KEY, String(version));
      } catch (error) {
        // 隐私模式 / 壳内禁用 storage 时退化为仅本会话有效，不报错也不重复弹窗。
      }
    }

    /**
     * 更新提醒弹层（挂 `shell.overlay`，覆盖在对话之上）。
     *
     * 为什么必须有它：设置页里的「检查更新」要靠用户主动去点，而绝大多数用户
     * 不会点 —— 结果就是「装了旧版永远不知道」。所以这里做三件事：
     *   1. 打开 DSH 就自动检查一次，之后每 10 分钟一次（服务端缓存 30 分钟）；
     *   2. 有新版本时在前台弹层提示，用户可「立即更新」或「稍后」；
     *   3. 「稍后」只压住当前这个版本，下一个版本会重新提醒 —— 不能让用户不知情。
     */
    function UpdateNotice() {
      var state = React.useState({ data: null, dismissed: readDismissed() });
      var current = state[0];
      var setState = state[1];
      var applyingState = React.useState(false);
      var applying = applyingState[0];
      var setApplying = applyingState[1];
      var resultState = React.useState(null);
      var result = resultState[0];
      var setResult = resultState[1];

      React.useEffect(function () {
        var alive = true;
        var tick = function () {
          fetch("/api/pal-channel/update", {
            headers: { accept: "application/json" },
            credentials: "same-origin",
          })
            .then(function (r) {
              return r.ok ? r.json() : null;
            })
            .then(function (data) {
              if (!alive || !data) return;
              setState({ data: data, dismissed: readDismissed() });
            })
            .catch(function () {
              // 检查失败不打扰用户：网络不可达是常态，静默等下个心跳。
            });
        };
        tick();
        var timer = setInterval(tick, UPDATE_HEARTBEAT_MS);
        return function () {
          alive = false;
          clearInterval(timer);
        };
      }, []);

      var data = current.data;
      if (!data || data.ok !== true || data.updateAvailable !== true) return null;
      var latest = String(data.latest || "");
      if (!latest || latest === current.dismissed) return null;

      var notes = Array.isArray(data.notes) ? data.notes : [];

      var onLater = function () {
        writeDismissed(latest);
        setState({ data: data, dismissed: latest });
      };

      var onUpdate = function () {
        if (applying) return;
        setApplying(true);
        setResult(null);
        fetch("/api/pal-channel/update/apply", {
          method: "POST",
          headers: { "content-type": "application/json", accept: "application/json" },
          credentials: "same-origin",
          body: "{}",
        })
          .then(function (r) {
            return r.json().then(
              function (v) {
                return { ok: r.ok, body: v };
              },
              function () {
                return { ok: r.ok, body: null };
              },
            );
          })
          .catch(function (e) {
            return { ok: false, body: { error: String(e) } };
          })
          .then(function (outcome) {
            setApplying(false);
            var body = outcome.body || {};
            if (outcome.ok) {
              setResult({ kind: "done", text: body.note || t("noticeDone") });
            } else {
              setResult({
                kind: "blocked",
                text: String(body.error || body.code || "HTTP error").slice(0, 200),
              });
            }
          });
      };

      var children = [
        React.createElement("div", { key: "title", style: S.noticeTitle }, t("noticeTitle")),
        React.createElement(
          "div",
          { key: "meta", style: S.noticeMeta },
          t("noticeMeta") + " " + String(data.local || "—") + " → " + latest,
        ),
      ];
      if (notes.length > 0) {
        children.push(
          React.createElement(
            "div",
            { key: "notes", style: S.noticeNotes },
            notes.map(function (line, index) {
              return React.createElement("div", { key: "n" + index, style: S.noteLine }, line);
            }),
          ),
        );
      }
      if (result) {
        children.push(
          React.createElement(
            "div",
            { key: "result", style: result.kind === "done" ? S.noticeMeta : S.error },
            result.text,
          ),
        );
      }
      children.push(
        React.createElement(
          "div",
          { key: "actions", style: S.noticeActions },
          React.createElement(
            "button",
            { type: "button", style: S.linkBtn, onClick: onLater },
            t("noticeLater"),
          ),
          React.createElement(
            "button",
            { type: "button", style: S.primaryBtn, disabled: applying, onClick: onUpdate },
            applying ? t("noticeUpdating") : t("noticeUpdate"),
          ),
        ),
      );

      return React.createElement(
        "div",
        {
          style: S.noticeLayer,
          "data-dsh-plugin": "dsh-pal-channel",
          "data-dsh-surface": "update-notice",
        },
        React.createElement(
          "div",
          { style: S.noticeCard, role: "dialog", "aria-modal": "true" },
          children,
        ),
      );
    }

    function ChannelsPanel() {
      var statusState = React.useState({ phase: "loading", data: null });
      var status = statusState[0];
      var setStatus = statusState[1];
      var updateState = React.useState({ phase: "idle", data: null });
      var update = updateState[0];
      var setUpdate = updateState[1];
      var busyState = React.useState("");
      var busy = busyState[0];
      var setBusy = busyState[1];
      var msgState = React.useState("");
      var message = msgState[0];
      var setMessage = msgState[1];
      // 更新是两步：第一次点变成「确认更新」，第二次点才真正执行。
      // 不用 window.confirm —— DSH 桌面壳里原生弹窗行为不可靠，两步按钮更稳。
      var confirmState = React.useState(false);
      var confirming = confirmState[0];
      var setConfirming = confirmState[1];
      var applyingState = React.useState(false);
      var applying = applyingState[0];
      var setApplying = applyingState[1];

      var reload = function () {
        return readOverview().then(function (data) {
          setStatus({ phase: data ? "ok" : "unavailable", data: data });
        });
      };

      React.useEffect(function () {
        var alive = true;
        var tick = function () {
          readOverview().then(function (data) {
            if (alive) setStatus({ phase: data ? "ok" : "unavailable", data: data });
          });
        };
        tick();
        var timer = setInterval(tick, 20000);
        return function () {
          alive = false;
          clearInterval(timer);
        };
      }, []);

      var data = status.data;
      var channels = (data && data.channels) || [];
      var checks = (data && data.checks) || {};
      var ipv6 = checks.ipv6 || { count: 0, addresses: [] };
      var gateway = checks.gateway || { running: false };
      var direct = checks.direct || { enabled: false, ipv6: false, lan: false };

      var onToggleDirect = function (kind, next) {
        if (busy) return;
        var payload = {
          ipv6: kind === "ipv6" ? next : direct.ipv6 === true,
          lan: kind === "lan" ? next : direct.lan === true,
        };
        setBusy(kind);
        setMessage(t("working"));
        requestDirect(payload).then(function (result) {
          setBusy("");
          if (result.ok) {
            var note = result.body && (result.body.note || result.body.next);
            setMessage(typeof note === "string" ? note : "");
            reload();
          } else {
            var reason =
              (result.body && (result.body.error || result.body.reason)) ||
              "HTTP " + result.status;
            setMessage(t("failed") + "：" + String(reason).slice(0, 160));
          }
        });
      };

      var onSwitchChannel = function (target) {
        if (busy) return;
        setBusy(target);
        setMessage(t("switching"));
        requestSwitch(target).then(function (result) {
          setBusy("");
          if (result.ok) {
            setMessage(t("switched"));
            reload();
          } else {
            var reason =
              (result.body && (result.body.error || result.body.reason)) ||
              "HTTP " + result.status;
            setMessage(t("failed") + "：" + String(reason).slice(0, 160));
          }
        });
      };

      var onCheckUpdate = function () {
        if (update.phase === "checking") return;
        if (confirming) setConfirming(false);
        setUpdate({ phase: "checking", data: update.data });
        requestUpdateCheck().then(function (result) {
          setUpdate({ phase: result.ok ? "done" : "failed", data: result.body });
        });
      };

      var onApplyUpdate = function () {
        if (applying) return;
        if (!confirming) {
          setConfirming(true);
          return;
        }
        setConfirming(false);
        setApplying(true);
        var previous = update.data;
        setUpdate({ phase: "applying", data: previous });
        requestUpdateApply().then(function (result) {
          setApplying(false);
          setUpdate({
            phase: result.ok ? "applied" : "blocked",
            data: previous,
            result: result.body || { error: "HTTP " + result.status },
          });
        });
      };

      var rows = [];
      if (status.phase === "loading") {
        rows.push(
          React.createElement("div", { key: "loading", style: S.meta }, t("channelsLoading")),
        );
      } else if (status.phase === "unavailable") {
        rows.push(
          React.createElement("div", { key: "nan", style: S.meta }, t("channelsUnavailable")),
        );
      } else {
        channels.forEach(function (ch, index) {
          var items = [
            React.createElement(
              "div",
              { key: "head", style: S.rowHead },
              React.createElement(
                "span",
                null,
                React.createElement(
                  "span",
                  { style: { color: PHASE_COLOR[ch.phase] || "#8b949e" } },
                  "● ",
                ),
                React.createElement("span", { style: S.rowName }, ch.name),
              ),
              React.createElement("span", { style: S.phase }, t(PHASE_KEY[ch.phase] || ch.phase)),
            ),
          ];
          if (ch.origin) {
            items.push(React.createElement("div", { key: "origin", style: S.url }, ch.origin));
          } else if (ch.detail) {
            items.push(React.createElement("div", { key: "detail", style: S.url }, ch.detail));
          }
          if (ch.id === "ipv6" || ch.id === "lan") {
            var on = direct[ch.id] === true;
            items.push(
              React.createElement(
                "button",
                {
                  key: "toggle",
                  type: "button",
                  disabled: busy === ch.id,
                  style: S.btn,
                  onClick: function () {
                    onToggleDirect(ch.id, !on);
                  },
                },
                busy === ch.id ? t("working") : on ? t("turnOff") : t("turnOn"),
              ),
            );
          }
          // 隧道通道的切换本来在另一个区块；合并后直接放在列表行里，
          // 用户在同一屏就能"看见 + 操作"，不用在两个设置页之间来回跳。
          if (ch.group === "public" && ch.configured === true && ch.phase !== "current") {
            items.push(
              React.createElement(
                "button",
                {
                  key: "switch-channel",
                  type: "button",
                  disabled: busy === ch.id,
                  style: S.btn,
                  onClick: function () {
                    onSwitchChannel(ch.id);
                  },
                },
                busy === ch.id ? t("switching") : t("switchTo"),
              ),
            );
          }
          rows.push(React.createElement("div", { key: ch.id || index, style: S.rowBox }, items));
        });
      }

      var updateText = "";
      if (update.phase === "checking") updateText = t("checkingUpdate");
      else if (update.phase === "applying") updateText = t("updating");
      else if (update.phase === "done" && update.data) {
        updateText = update.data.updateAvailable
          ? t("updateAvailable") + "：" + String(update.data.latest || "")
          : t("updateLatest");
      } else if (update.phase === "applied") {
        updateText = update.result && update.result.changed === false ? t("updateNoChange") : t("updateDone");
      } else if (update.phase === "blocked") {
        var blockedReason = (update.result && (update.result.error || update.result.code)) || "";
        updateText =
          t("updateCancelled") + (blockedReason ? "：" + String(blockedReason).slice(0, 180) : "");
      } else if (update.phase === "failed") updateText = t("updateFailed");

      var notes = update.data && Array.isArray(update.data.notes) ? update.data.notes : [];
      var canUpdate =
        update.phase === "done" && update.data && update.data.updateAvailable === true;
      var updateAction =
        canUpdate || confirming
          ? React.createElement(
              "div",
              { key: "ua", style: { marginTop: "6px" } },
              React.createElement(
                "button",
                {
                  type: "button",
                  disabled: applying,
                  style: S.btn,
                  onClick: onApplyUpdate,
                },
                applying ? t("updating") : confirming ? t("confirmUpdate") : t("updateNow"),
              ),
            )
          : null;

      return React.createElement(
        "div",
        {
          style: S.box,
          "data-dsh-plugin": "dsh-pal-channel",
          "data-dsh-surface": "settings-modal",
        },
        [
          React.createElement("p", { key: "hint", style: S.hint }, t("channelsHint")),
          React.createElement("div", { key: "rows" }, rows),
          React.createElement(
            "div",
            { key: "version", style: S.sectionTitle },
            t("versionLabel") + " " + String((data && data.pluginVersion) || "—"),
            React.createElement(
              "button",
              {
                type: "button",
                disabled: update.phase === "checking",
                style: Object.assign({}, S.btn, { marginLeft: "10px" }),
                onClick: onCheckUpdate,
              },
              update.phase === "checking" ? t("checkingUpdate") : t("checkUpdate"),
            ),
          ),
          updateText
            ? React.createElement("div", { key: "uv", style: S.meta }, updateText)
            : null,
          updateAction,
          notes.length > 0
            ? React.createElement(
                "div",
                { key: "notes", style: { marginTop: "10px" } },
                React.createElement("div", { style: S.sectionTitle }, t("notesTitle")),
                notes.map(function (line, index) {
                  return React.createElement(
                    "div",
                    { key: "note-" + index, style: S.noteLine },
                    line,
                  );
                }),
              )
            : null,
          React.createElement(
            "div",
            { key: "health", style: S.meta },
            t("ipv6Health") +
              "：" +
              (ipv6.count > 0 ? ipv6.count + " " + t("ipv6Count") : t("ipv6None")) +
              " · " +
              t("gatewayHealth") +
              "：" +
              (gateway.running
                ? t("gatewayRunning")
                : checks.installed
                  ? t("gatewayStopped")
                  : t("gatewayMissing")),
          ),
          message
            ? React.createElement("div", { key: "msg", style: S.message }, message)
            : null,
          React.createElement("div", { key: "dh", style: S.meta }, t("directToggleHint")),
        ],
      );
    }

    function apply(ctx) {
      try {
        var slots =
          ctx.slots || (typeof ctx.get === "function" ? ctx.get("slots") : undefined);
        if (!slots || typeof slots.register !== "function") {
          reportFailure("slots-unavailable", "ctx.slots 不可用");
          return;
        }

        var bound = t;
        try {
          if (ctx.locale && typeof ctx.locale.register === "function") {
            ctx.effect(function () {
              return ctx.locale.register(NS, { zh: zh, en: en });
            }, "pal-channel: dicts");
            if (typeof ctx.locale.bind === "function") bound = ctx.locale.bind(NS);
            setT(bound);
          }
        } catch (error) {
          reportFailure("locale", error);
        }

        // 关键：必须包在 slots.inject 里。workbuddy 注释原话：
        // 「注册一个还没声明的槽会直接抛错、整个纤维失败（面板就静默消失了）」。
        //
        // 只注册**一个** settings.section。早先拆成「公网入口」与「连接通道总览」两块，
        // 结果是设置左侧导航多出一个重复入口 —— 两个区块讲的是同一件事（这台电脑有
        // 哪几条路），用户得点两遍才看得到全貌。现在合并：总览列表里直接带隧道切换。
        ctx.effect(function () {
          var disposeSlot = slots.inject("settings.section", function () {
            return slots.register(
              {
                name: "settings.section",
                id: "pal-channel",
                order: 72,
                label: function () {
                  return bound("channelsNav");
                },
                locale: NS,
              },
              ChannelsPanel,
            );
          });
          return function () {
            disposeSlot();
          };
        }, "pal-channel.panel");

        // 更新提醒挂 shell.overlay：覆盖在对话之上，用户不点掉就一直可见。
        // 槽位在老 cohort 里可能不存在，注入失败只少一个提醒，绝不能影响设置面板。
        try {
          ctx.effect(function () {
            var disposeSlot = slots.inject("shell.overlay", function () {
              return slots.register(
                {
                  name: "shell.overlay",
                  id: "pal-channel-update-notice",
                  order: 20,
                  registrant: "dsh-pal-channel",
                  locale: NS,
                },
                UpdateNotice,
              );
            });
            return function () {
              disposeSlot();
            };
          }, "pal-channel.update-notice");
        } catch (error) {
          reportFailure("update-notice", error);
        }
      } catch (error) {
        reportFailure("apply", error);
      }
    }

    exports.name = "pal-channel-client";
    exports.apply = apply;
    exports.inject = ["slots", "locale"];
    exports.ChannelsPanel = ChannelsPanel;
    exports.UpdateNotice = UpdateNotice;
    return module.exports;
  },
});
