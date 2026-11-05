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

    function PalChannelPanel() {
      var statusState = React.useState({ phase: "loading", data: null });
      var status = statusState[0];
      var setStatus = statusState[1];
      var busyState = React.useState(false);
      var busy = busyState[0];
      var setBusy = busyState[1];
      var msgState = React.useState("");
      var message = msgState[0];
      var setMessage = msgState[1];
      var failState = React.useState(false);
      var failed = failState[0];
      var setFailed = failState[1];

      React.useEffect(function () {
        var alive = true;
        var tick = function () {
          readStatus().then(function (data) {
            if (alive) setStatus({ phase: data ? "ok" : "unavailable", data: data });
          });
        };
        tick();
        var timer = setInterval(tick, 15000);
        return function () {
          alive = false;
          clearInterval(timer);
        };
      }, []);

      var data = status.data;
      var current = (data && data.channel) || "";
      var address = (data && data.publicBaseUrl) || "";

      var onSwitch = function (target) {
        if (busy || target === current) return;
        setBusy(true);
        setMessage(t("switching"));
        setFailed(false);
        requestSwitch(target).then(function (result) {
          setBusy(false);
          if (result.ok) {
            setMessage(t("switched"));
            readStatus().then(function (next) {
              setStatus({ phase: next ? "ok" : "unavailable", data: next });
            });
          } else {
            var reason =
              (result.body && (result.body.error || result.body.reason)) ||
              "HTTP " + result.status;
            setMessage(t("failed") + "：" + String(reason).slice(0, 160));
            setFailed(true);
          }
        });
      };

      var option = function (target, labelKey, hintKey) {
        var active = current === target;
        return React.createElement(
          "button",
          {
            key: target,
            type: "button",
            disabled: busy || active,
            onClick: function () {
              onSwitch(target);
            },
            style: active ? Object.assign({}, S.option, S.optionActive) : S.option,
          },
          React.createElement(
            "span",
            { style: S.title },
            (active ? "● " : "○ ") + t(labelKey),
            active
              ? React.createElement(
                  "span",
                  { style: { fontSize: "12px", opacity: 0.6, marginLeft: "8px" } },
                  t("current"),
                )
              : null,
          ),
          React.createElement("span", { style: S.sub }, t(hintKey)),
        );
      };

      var children = [React.createElement("p", { key: "hint", style: S.hint }, t("hint"))];

      if (status.phase === "loading") {
        children.push(React.createElement("div", { key: "loading", style: S.meta }, t("loading")));
      } else {
        children.push(option("domestic", "domestic", "domesticHint"));
        children.push(option("cloudflare", "cloudflare", "cloudflareHint"));
        if (address) {
          children.push(
            React.createElement(
              "div",
              { key: "addr", style: S.meta },
              t("address") + "：" + address,
            ),
          );
        }
        if (status.phase === "unavailable") {
          children.push(React.createElement("div", { key: "nan", style: S.meta }, t("unavailable")));
        }
      }
      if (message) {
        children.push(
          React.createElement(
            "div",
            { key: "msg", style: failed ? Object.assign({}, S.message, S.error) : S.message },
            message,
          ),
        );
      }

      return React.createElement(
        "div",
        {
          style: S.box,
          "data-dsh-plugin": "dsh-pal-channel",
          "data-dsh-surface": "settings-modal",
        },
        children,
      );
    }

    /** 把浏览器半的失败落到 host 日志，避免再次静默消失。 */
    function reportFailure(stage, error) {
      var detail = String((error && (error.stack || error.message)) || error).slice(0, 600);
      try {
        fetch("/api/pal-channel/client-log", {
          method: "POST",
          headers: { "content-type": "application/json" },
          credentials: "same-origin",
          body: JSON.stringify({ stage: stage, detail: detail }),
        }).catch(function () {});
      } catch (e) {
        /* 忽略 */
      }
      try {
        console.error("[pal-channel] " + stage, error);
      } catch (e) {
        /* 忽略 */
      }
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
        ctx.effect(function () {
          var disposeSlot = slots.inject("settings.section", function () {
            return slots.register(
              {
                name: "settings.section",
                id: "pal-channel",
                order: 72,
                label: function () {
                  return bound("nav");
                },
                locale: NS,
              },
              PalChannelPanel,
            );
          });
          return function () {
            disposeSlot();
          };
        }, "pal-channel.panel");
      } catch (error) {
        reportFailure("apply", error);
      }
    }

    exports.name = "pal-channel-client";
    exports.apply = apply;
    exports.inject = ["slots", "locale"];
    exports.PalChannelPanel = PalChannelPanel;
    return module.exports;
  },
});
