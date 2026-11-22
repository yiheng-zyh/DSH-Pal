// 旧 WebView JS 兼容层（issue #1 / #2：鸿蒙卓易通 com.huawei.webview，Chromium < 120）。
// 页面 boot 脚本用了 Promise.withResolvers（Chromium 119+）、AbortSignal.any
// （Chromium 116+）与 URL.canParse（Chromium 120+），旧内核直接 TypeError、挂载中断。
// 这里只在缺失时补，新内核零改动；不依赖 DOM，document-start 在任意 frame 都安全。
// 幂等：靠"缺失才装"保证，重复注入不覆盖已有实现。
// 语法遵守 WebView 126 子集（var/function，无 ??=、无 Array.at）。
;
(function () {
  try {
    // 实际补了哪些项。故意只记"真的补过的"而不是静态清单：
    // 新内核上这里就是空数组，等于证据"兼容层跑了、但无事可做"，
    // 旧内核上才列出真正缺失的 API —— 静态清单会让人以为新内核也补过。
    var applied = [];
    // 可观测 foothold：injectCheck 靠它确认本脚本真的执行过
    // （document-start 在部分国产 WebView 上静默失效，注册成功≠跑到了）。
    // 先占位成 version 1，补完再覆写，避免中途抛错时 foothold 完全消失。
    window.__DSH_POLYFILLS__ = { version: 1, apis: [] };
    if (typeof Promise === 'function' && typeof Promise.withResolvers !== 'function') {
      Promise.withResolvers = function () {
        var resolve, reject;
        var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
        return { promise: promise, resolve: resolve, reject: reject };
      };
      applied.push('Promise.withResolvers');
    }
    if (typeof AbortSignal === 'function' && typeof AbortSignal.any !== 'function' &&
        typeof AbortController === 'function') {
      AbortSignal.any = function (signals) {
        var list = Array.prototype.slice.call(signals);
        var controller = new AbortController();
        var onAbort = function (event) {
          for (var i = 0; i < list.length; i++) {
            try { list[i].removeEventListener('abort', onAbort); } catch (_) {}
          }
          controller.abort(event.target.reason);
        };
        for (var i = 0; i < list.length; i++) {
          var signal = list[i];
          if (signal.aborted) { controller.abort(signal.reason); return controller.signal; }
          signal.addEventListener('abort', onAbort);
        }
        return controller.signal;
      };
      applied.push('AbortSignal.any');
    }
    // issue #2：真正炸渲染的是这一个。DSH 主包 assets/index-*.js 有 3 处调用、
    // 2 处无 typeof 守卫，其中一处踩在 conversation.chat.node 渲染路径上 ——
    // 一崩就是整个 slot 崩，页面永远 mount 不出来。
    if (typeof URL === 'function' && typeof URL.canParse !== 'function') {
      URL.canParse = function (value, base) {
        try { new URL(value, base); return true; } catch (_) { return false; }
      };
      applied.push('URL.canParse');
    }
    // URL.parse 是 Chrome 140+ 才有的（不是 issue 里写的 117），同样只补缺失。
    if (typeof URL === 'function' && typeof URL.parse !== 'function') {
      URL.parse = function (value, base) {
        try { return new URL(value, base); } catch (_) { return null; }
      };
      applied.push('URL.parse');
    }
    // Object.groupBy / Map.groupBy 是 Chromium 117+ 才有；插件包里有调用，
    // 顺手兜住以后 DSH 升级的情况。分组键按规范做 ToPropertyKey（symbol 保持原样）。
    if (typeof Object.groupBy !== 'function') {
      Object.groupBy = function (items, keyFn) {
        var out = {};
        var list = Array.from(items);
        for (var i = 0; i < list.length; i++) {
          var raw = typeof keyFn === 'function' ? keyFn(list[i], i) : list[i][keyFn];
          var key = typeof raw === 'symbol' ? raw : String(raw);
          // 统一走 defineProperty：分组键可能是 '__proto__'，普通赋值会污染原型。
          if (Object.prototype.hasOwnProperty.call(out, key)) out[key].push(list[i]);
          else Object.defineProperty(out, key, {
            value: [list[i]], writable: true, enumerable: true, configurable: true
          });
        }
        return out;
      };
      applied.push('Object.groupBy');
    }
    if (typeof Map === 'function' && typeof Map.groupBy !== 'function') {
      Map.groupBy = function (items, keyFn) {
        var out = new Map();
        var list = Array.from(items);
        for (var i = 0; i < list.length; i++) {
          var item = list[i];
          var key = typeof keyFn === 'function' ? keyFn(item, i) : item[keyFn];
          var bucket = out.get(key);
          if (bucket) bucket.push(item);
          else out.set(key, [item]);
        }
        return out;
      };
      applied.push('Map.groupBy');
    }
    window.__DSH_POLYFILLS__ = { version: 2, apis: applied };
  } catch (_) {}
})();
