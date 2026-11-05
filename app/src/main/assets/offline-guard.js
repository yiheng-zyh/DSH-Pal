/**
 * 离线屏蔽：在页面内截获 fetch / XMLHttpRequest / WebSocket。
 *
 * 为什么必须在页面内做：Android 的 `shouldInterceptRequest` 只覆盖**资源加载**
 * （主文档、img/css/js/font、iframe），而 DSH 的业务数据全部走页面里 JS 发起的
 * fetch/XHR 与 WebSocket —— 这些请求根本不经过那个回调（已实测确认：页面加载后
 * 断网，拦截器一条日志都没有）。所以"离线时别让页面白等超时"只能在这一层实现。
 *
 * 行为边界（宁可少挡，不可挡错）：
 *  - 只在 __DSH_OFFLINE_MODE__ 为真时生效，由原生侧在"开关开启 + 无网络"时设置；
 *  - 只挡 /api/ 与实时通道路径；静态资源、插件包、页面导航一律放行；
 *  - 被挡的请求给出**明确的 offline 错误**（不是伪造成功数据），让页面走自己的错误分支；
 *  - 不缓存、不改写响应内容、不读 Cookie。
 *
 * 幂等：重复注入只装一次。页面导航后由 document-start 重新注入。
 */
(function () {
  if (window.__DSH_OFFLINE_GUARD__) return;
  window.__DSH_OFFLINE_GUARD__ = true;

  var REALTIME = ['/api/remote.mux', '/remote/api/remote.mux', '/api/ego/stream',
    '/remote/api/ego/stream', '/api/events', '/remote/api/events',
    '/api/remote.events', '/remote/api/remote.events', '/remote/api/remote.stream'];
  var API_PREFIX = ['/api/', '/remote/api/'];
  var ALLOW_PREFIX = ['/plugins/', '/remote/plugins/', '/assets/', '/skin-assets/',
    '/wallpaper-engine/'];
  var ALLOW_EXT = /\.(js|mjs|css|woff2?|ttf|otf|png|webp|svg|jpg|jpeg|gif|ico|map)(\?|$)/i;

  function pathOf(url) {
    try {
      var text = String(url);
      var schemeAt = text.indexOf('://');
      if (schemeAt < 0) return text;
      var rest = text.slice(schemeAt + 3);
      var slash = rest.indexOf('/');
      return slash < 0 ? '/' : rest.slice(slash);
    } catch (e) { return String(url); }
  }

  // 0 = 放行，1 = 实时通道，2 = 业务 API
  function classify(url) {
    var path = pathOf(url);
    var lower = path.toLowerCase();
    for (var i = 0; i < ALLOW_PREFIX.length; i++) {
      if (lower.indexOf(ALLOW_PREFIX[i]) === 0) return 0;
    }
    if (ALLOW_EXT.test(lower)) return 0;
    for (var j = 0; j < REALTIME.length; j++) {
      if (lower === REALTIME[j] || lower.indexOf(REALTIME[j] + '/') === 0 ||
          lower.indexOf(REALTIME[j] + '?') === 0) return 1;
    }
    for (var k = 0; k < API_PREFIX.length; k++) {
      if (lower.indexOf(API_PREFIX[k]) === 0) return 2;
    }
    return 0;
  }

  function offlineNow() {
    // 原生侧通过 __DSH_OFFLINE_MODE__ 下发；同时交叉验证 navigator.onLine，
    // 防止标志过期（例如刚恢复网络但原生还没来得及更新）时误挡。
    return window.__DSH_OFFLINE_MODE__ === true && navigator.onLine === false;
  }

  function offlineMessage(kind) {
    return kind === 1
      ? '设备当前离线：实时通道已暂停，联网后自动恢复'
      : '设备当前离线：这一项需要联网，可在设置里查看已缓存的对话';
  }

  // ---- fetch ----
  var nativeFetch = window.fetch;
  if (typeof nativeFetch === 'function') {
    window.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var kind = classify(url);
        if (kind !== 0 && offlineNow()) {
          return Promise.resolve(new Response(
            JSON.stringify({ ok: false, code: 'offline', message: offlineMessage(kind) }),
            { status: 503, statusText: 'Offline', headers: { 'content-type': 'application/json' } }
          ));
        }
      } catch (e) { /* 判定失败就放行，绝不因守卫自身出错而阻断页面 */ }
      return nativeFetch.apply(this, arguments);
    };
  }

  // ---- XMLHttpRequest ----
  var nativeOpen = window.XMLHttpRequest && window.XMLHttpRequest.prototype.open;
  var nativeSend = window.XMLHttpRequest && window.XMLHttpRequest.prototype.send;
  if (nativeOpen && nativeSend) {
    window.XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__dshOfflineKind = classify(url); } catch (e) { this.__dshOfflineKind = 0; }
      return nativeOpen.apply(this, arguments);
    };
    window.XMLHttpRequest.prototype.send = function () {
      var kind = this.__dshOfflineKind || 0;
      if (kind !== 0 && offlineNow()) {
        var self = this;
        var body = JSON.stringify({ ok: false, code: 'offline', message: offlineMessage(kind) });
        // 异步投递，保持与真实请求相同的事件时序（页面常在同一次调用里挂 onload）。
        setTimeout(function () {
          try {
            Object.defineProperty(self, 'readyState', { value: 4, configurable: true });
            Object.defineProperty(self, 'status', { value: 503, configurable: true });
            Object.defineProperty(self, 'responseText', { value: body, configurable: true });
            Object.defineProperty(self, 'response', { value: body, configurable: true });
            if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
            if (typeof self.onload === 'function') self.onload();
          } catch (e) { }
        }, 0);
        return;
      }
      return nativeSend.apply(this, arguments);
    };
  }

  // ---- WebSocket ----
  var NativeWS = window.WebSocket;
  if (typeof NativeWS === 'function') {
    var Guarded = function (url, protocols) {
      try {
        var kind = classify(url);
        if (kind !== 0 && offlineNow()) {
          // 构造一个"永不打开、只报离线"的替身：页面会走 onerror/onclose 分支，
          // 而不是卡在 CONNECTING。不伪造 OPEN，避免页面以为已连接。
          var fake = {
            url: String(url), readyState: 3, bufferedAmount: 0,
            onopen: null, onmessage: null, onerror: null, onclose: null,
            close: function () { }, send: function () { },
            addEventListener: function () { }, removeEventListener: function () { },
          };
          setTimeout(function () {
            try {
              var ev = { type: 'error', code: 'offline' };
              if (typeof fake.onerror === 'function') fake.onerror(ev);
              if (typeof fake.onclose === 'function') fake.onclose({ type: 'close', code: 1006, reason: 'offline' });
            } catch (e) { }
          }, 0);
          return fake;
        }
      } catch (e) { }
      return new NativeWS(url, protocols);
    };
    Guarded.prototype = NativeWS.prototype;
    Guarded.CONNECTING = 0; Guarded.OPEN = 1; Guarded.CLOSING = 2; Guarded.CLOSED = 3;
    window.WebSocket = Guarded;
  }

  // ---- 原生侧下发开关的入口 ----
  window.__DSH_SET_OFFLINE__ = function (offline) {
    window.__DSH_OFFLINE_MODE__ = offline === true;
  };
})();
