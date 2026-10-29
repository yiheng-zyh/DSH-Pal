package com.dsh.mobile.bridge

/**
 * The JavaScript injected into the paired DSH page before any of its own code
 * runs. Its only job is to observe the harness event stream the page already
 * consumes and mirror the interesting parts to the native layer.
 *
 * Design constraints that shaped this script:
 *
 *  - **Never break the page.** Every hook is wrapped in try/catch and the
 *    original function is always called with its original arguments and
 *    `this`. A failure here must cost the island, never the chat.
 *  - **Do not re-implement the transport.** The page already opens the
 *    authenticated WebSocket mux; we tee the frames off that socket rather
 *    than opening a second one, so there is exactly one credential path and
 *    zero extra server load.
 *  - **Stay passive.** The script never sends anything on the socket and never
 *    mutates the payload; it only reads and forwards.
 *  - **Forward raw JSON.** Interpretation lives in Kotlin
 *    ([com.dsh.mobile.core.EventReducer]), so a change here is rarely needed
 *    when the harness event vocabulary grows.
 */
object BridgeScript {

    /** Name of the Kotlin object exposed through `addJavascriptInterface`. */
    const val INTERFACE = "DshNative"

    /**
     * Build the injected script.
     *
     * @param forwardEvents forward every session event to native (island/notification).
     * @param forwardState  forward coarse page state (ready/auth-lost) to native.
     */
    fun build(forwardEvents: Boolean, forwardState: Boolean): String = """
(function () {
  if (window.top !== window) return 'subframe-skipped';
  if (window.__DSH_MOBILE_BRIDGE__) return 'already-installed';
  window.__DSH_MOBILE_BRIDGE__ = { version: 1 };

  function post(kind, payload) {
    try {
      var api = window.${INTERFACE};
      if (!api) return;
      api.onBridgeMessage(JSON.stringify({ kind: kind, payload: payload }));
    } catch (e) { /* never let the bridge break the page */ }
  }

  var MAX_FRAME = 65536;
  var taskSockets = new Set();
  window.__DSH_MOBILE_RECONNECT__ = function(){
    var count=0;
    taskSockets.forEach(function(ws){if(ws.readyState===0||ws.readyState===1){try{ws.close(4002,'network-reconnect');count++;}catch(_){}}});
    window.dispatchEvent(new Event('online'));return count;
  };

  function clip(s) {
    if (typeof s !== 'string') return '';
    return s.length > 1024 ? s.slice(0, 1024) + '…' : s;
  }

  // --- WebSocket tee -------------------------------------------------------
  // The harness multiplexes every Remote stream over one socket at
  // /api/remote.mux (rewritten to /remote/api/remote.mux by the pairing
  // channel). We wrap the constructor and listen on each instance with
  // addEventListener so the page's own onmessage handler is untouched.
  var NativeWebSocket = window.WebSocket;
  var socketSequence = 0, socketPage = Date.now().toString(36) + Math.random().toString(36).slice(2);
  if (NativeWebSocket && !NativeWebSocket.__dshMobileWrapped) {
    var Wrapped = function (url, protocols) {
      var ws = protocols === undefined
        ? new NativeWebSocket(url)
        : new NativeWebSocket(url, protocols);
      try { hookSocket(ws, String(url)); } catch (e) {}
      return ws;
    };
    Wrapped.prototype = NativeWebSocket.prototype;
    Wrapped.CONNECTING = NativeWebSocket.CONNECTING;
    Wrapped.OPEN = NativeWebSocket.OPEN;
    Wrapped.CLOSING = NativeWebSocket.CLOSING;
    Wrapped.CLOSED = NativeWebSocket.CLOSED;
    Wrapped.__dshMobileWrapped = true;
    window.WebSocket = Wrapped;
  }

  function hookSocket(ws, url) {
    // Only the task mux controls native connection state and task notifications.
    // Telemetry, wallpaper and terminal sockets have independent lifetimes.
    try {
      var path = new URL(url, location.href).pathname;
      if (path !== '/api/remote.mux' && path !== '/remote/api/remote.mux') return;
    } catch (_) { return; }
    var parsed=new URL(url,location.href);
    if(parsed.origin.replace(/^ws/,'http')===location.origin){taskSockets.add(ws);ws.addEventListener('close',function(){taskSockets.delete(ws);});}
    var socketId = socketPage + '-' + (++socketSequence);
    post('ws', { url: clip(url), socketId: socketId });
    ws.addEventListener('message', function (ev) {
      ${if (forwardEvents) """
      try {
        var data = ev && ev.data;
        if (typeof data !== 'string' || data.length > MAX_FRAME) return;
        var first = data.charCodeAt(0);
        // Only JSON objects/arrays can carry harness frames.
        if (first !== 123 && first !== 91) return;
        post('event', data);
      } catch (e) {}
      """ else "/* event forwarding disabled */"}
    });
    ws.addEventListener('open', function () { post('ws-open', { url: clip(url), socketId: socketId }); });
    ws.addEventListener('close', function (e) { post('ws-close', { url: clip(url), socketId: socketId, code: e && e.code }); });
  }

  ${if (forwardState) """
  // --- page readiness / auth state ----------------------------------------
  // A paired page that lost its device cookie shows the plugin's unpaired
  // fence; surfacing that lets native prompt for a re-pair instead of the user
  // staring at a dead QR screen.
  var nativeFetch = window.fetch;
  if (nativeFetch && !nativeFetch.__dshMobileWrapped) {
    var wrappedFetch = function (input, init) {
      return nativeFetch.apply(this, arguments).then(function (res) {
        try {
          var path = res && new URL(res.url || String(input && input.url || input), location.href).pathname;
          if (res && res.status === 401 && /^\/(remote\/)?api\//.test(path)) post('auth-lost', { url: clip(res.url) });
        } catch (e) {}
        return res;
      }, function (err) { throw err; });
    };
    wrappedFetch.__dshMobileWrapped = true;
    window.fetch = wrappedFetch;
  }
  """ else "/* state forwarding disabled */"}

  window.addEventListener('error', function (e) {
    post('page-error', { message: clip(String(e && e.message || '')) });
  });
  window.addEventListener('online', function () { post('net', { online: true }); });
  window.addEventListener('offline', function () { post('net', { online: false }); });

  post('ready', { href: clip(location.href), ua: navigator.userAgent });
  return 'installed';
})();
""".trimIndent()
}
