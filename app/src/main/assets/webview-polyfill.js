// 旧 WebView JS 兼容层（issue #1：鸿蒙卓易通 com.huawei.webview，Chromium < 116）。
// 页面 boot 脚本用了 Promise.withResolvers（Chromium 119+）与 AbortSignal.any
// （Chromium 116+），旧内核直接 TypeError、挂载中断。这里只在缺失时补，
// 新内核零改动；不依赖 DOM，document-start 在任意 frame 都安全。
// 幂等：靠"缺失才装"保证，重复注入不覆盖已有实现。
// 语法遵守 WebView 126 子集（var/function，无 ??=、无 Array.at）。
;(function () {
  try {
    // 可观测 foothold：injectCheck 靠它确认本脚本真的执行过
    // （document-start 在部分国产 WebView 上静默失效，注册成功≠跑到了）。
    window.__DSH_POLYFILLS__ = { version: 1 };
    if (typeof Promise === 'function' && typeof Promise.withResolvers !== 'function') {
      Promise.withResolvers = function () {
        var resolve, reject;
        var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
        return { promise: promise, resolve: resolve, reject: reject };
      };
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
    }
  } catch (_) {}
})();