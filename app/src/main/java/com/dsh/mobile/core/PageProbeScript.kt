package com.dsh.mobile.core

/**
 * Page-side observability probes: resource accounting, DOM/mount snapshot,
 * injection self-check.
 *
 * These are *pure script builders* — no Android, no logging (`.ai/DECISIONS.md`,
 * 2026-10-27): the caller (ui.web layer) evaluates the returned text and renders
 * any result into RunLog/DiagFacts.
 *
 * Every emitted script obeys the same rules as `dsh-js-errors.js`:
 *  - document-start safe, idempotent (guarded by a global flag);
 *  - whole body wrapped in try/catch; the catch reports `inject-failed` through
 *    the existing `DshNative.onBridgeMessage` channel instead of dying silently;
 *  - WebView 126 syntax subset: var/function only, no `??=`, `||=`,
 *    `Array.prototype.at`, `structuredClone`, or top-level await.
 */
object PageProbeScript {

    /**
     * Per-resource accounting for main-frame `.js` / `.css` / `/plugins/` loads.
     *
     * Answers the question the console alone cannot: *did the failing file
     * actually arrive, did it arrive whole, and did it come out of the cache?*
     * `transferSize === 0 && decodedBodySize > 0` is the cache-hit fingerprint —
     * the signal that distinguishes "bad server" from "bad cached bundle".
     *
     * Publishes at most one aggregate frame per [RESOURCE_PUBLISH_MS] via
     * `{kind:'resources', payload:{total,ok,fail,slow,cached,failures}}`.
     * The page cannot see Content-Length, so `expected` stays -1 here; the
     * native side may fill it from its own transport when available.
     */
    fun resourceProbe(): String = """
;(function () {
  try {
    if (window.top !== window) return;
    if (window.__DSH_RES_PROBE__) return;
    var PUBLISH_MS = ${RESOURCE_PUBLISH_MS}, SLOW_MS = ${RESOURCE_SLOW_MS}, MAX_FAIL = ${RESOURCE_MAX_FAILURES}, MAX_SEEN = 4096;
    var total = 0, fail = 0, slow = 0, cached = 0, seenCount = 0;
    var seen = {}, failKeys = {}, failures = [], timer = null, lastSent = 0;
    // The native side snapshots the DOM at the moments it cares about; that is
    // also when a resource account is wanted, so the probe exposes a forced
    // publish. Without it a `progress=100` snapshot could report a blank root
    // while the bundle failures that explain it were still inside the 5s window.
    // It only publishes when something is actually pending, so it can never add
    // a frame beyond what the throttle already promised.
    window.__DSH_RES_PROBE__ = {
      version: 1,
      flush: function () { if (timer !== null) { clearTimeout(timer); publish(); } }
    };
    function post() {
      try {
        var api = window.DshNative;
        if (!api || typeof api.onBridgeMessage !== 'function') return;
        api.onBridgeMessage(JSON.stringify({
          kind: 'resources',
          payload: {
            total: total,
            ok: Math.max(0, total - fail),
            fail: fail,
            slow: slow,
            cached: cached,
            failures: failures.slice(0, MAX_FAIL)
          }
        }));
      } catch (_) {}
    }
    function publish() { timer = null; lastSent = Date.now(); post(); }
    function schedule() {
      if (timer !== null) return;
      var wait = PUBLISH_MS - (Date.now() - lastSent);
      timer = setTimeout(publish, wait > 0 ? wait : 0);
    }
    function pathOf(name) {
      try {
        var u = new URL(String(name || ''), location.href);
        if (u.origin !== location.origin) return '';
        return u.pathname;
      } catch (_) { return ''; }
    }
    function isAsset(path, entry) {
      // Business API calls share the Resource Timing buffer with real bundles. A
      // 403 from `/plugins/x/status` is a *normal* answer (plugin disabled / auth
      // required), and counting it as a failed asset made the summary advise a
      // cache clear — measured on a real device: 4 phantom failures from the
      // plugin status endpoints. Entry-level initiator is the only reliable
      // discriminator, so fetch/XHR/beacon are excluded before the path test.
      var kind = entry && entry.initiatorType ? String(entry.initiatorType).toLowerCase() : '';
      if (kind === 'fetch' || kind === 'xmlhttprequest' || kind === 'beacon') return false;
      return /\.(?:m?js|css)$/.test(path) || path.indexOf('/plugins/') >= 0;
    }
    /**
     * Register a failed asset. Returns true only the first time a path fails.
     *
     * [fail] is incremented here and nowhere else: a broken file normally shows
     * up twice (the element error event, then its ResourceTiming entry), and
     * counting both would make `fail` exceed `total`. So `fail` is the number of
     * distinct failed files, not the number of failed requests.
     */
    function markFail(path, status, bytes, expected) {
      if (failKeys[path]) return false;
      failKeys[path] = 1;
      fail++;
      if (failures.length < MAX_FAIL * 2) {
        // expected: Resource Timing never sees Content-Length, so a plain failure
        // stays -1. A *truncated* body fills it with the size that crossed the
        // wire, which is the number to compare [bytes] against.
        failures.push({ path: path.slice(0, 120), status: status, bytes: bytes, expected: expected });
      }
      return true;
    }
    function observe(entries) {
      try {
        for (var i = 0; i < entries.length; i++) {
          var entry = entries[i];
          var path = pathOf(entry.name);
          if (!path || !isAsset(path, entry)) continue;
          var key = entry.name + '#' + entry.startTime;
          if (seen[key] || seenCount >= MAX_SEEN) continue;
          seen[key] = 1; seenCount++;
          total++;
          var status = entry.responseStatus || 0;
          var transfer = entry.transferSize || 0;
          var encoded = entry.encodedBodySize || 0;
          var decoded = entry.decodedBodySize || 0;
          // Cache hit: nothing crossed the wire but the body is real.
          if (transfer === 0 && decoded > 0) cached++;
          if ((entry.duration || 0) > SLOW_MS) slow++;
          if (status >= 400) markFail(path, status, decoded, -1);
          else if (status === 0 && transfer === 0 && decoded === 0) {
            // No status *and* no body: the request answered with nothing. A plain
            // cache hit is NOT this — Chromium keeps the body sizes for those, and
            // some builds zero only responseStatus, so requiring all three empty is
            // what keeps a warm cache from being reported as 30 failed bundles.
            markFail(path, status, decoded, -1);
          }
          else if (transfer > 0 && encoded > 0 && decoded > 0 && decoded < encoded / 2) {
            // Truncated body: it decoded to less than half of what crossed the
            // wire — the classic signature of a cache entry cut short by a crash.
            markFail(path, status, decoded, encoded);
          }
          schedule();
        }
      } catch (_) {}
    }
    // A broken cached bundle shows HTTP 200 in ResourceTiming and only reveals
    // itself through the element error event; count that as a failure too.
    window.addEventListener('error', function (event) {
      try {
        var node = event && event.target;
        if (!node || !node.tagName) return;
        var tag = String(node.tagName).toUpperCase();
        var sheet = tag === 'LINK' && /stylesheet/i.test(String(node.rel || ''));
        if (tag !== 'SCRIPT' && !sheet) return;
        var path = pathOf(node.src || node.href || '');
        if (!path || !isAsset(path)) return;
        if (markFail(path, 0, -1, -1)) schedule();
      } catch (_) {}
    }, true);
    try { if (performance.getEntriesByType) observe(performance.getEntriesByType('resource')); } catch (_) {}
    try {
      if (typeof PerformanceObserver !== 'undefined') {
        new PerformanceObserver(function (list) { observe(list.getEntries()); })
          .observe({ type: 'resource', buffered: true });
      }
    } catch (_) {}
    window.addEventListener('load', schedule);
  } catch (error) {
    try {
      var api = window.DshNative;
      if (api && typeof api.onBridgeMessage === 'function') {
        api.onBridgeMessage(JSON.stringify({
          kind: 'inject-failed',
          payload: { script: 'dsh-res-probe', error: String((error && error.name) || error || '').slice(0, 60) }
        }));
      }
    } catch (_) {}
  }
})();
    """.trimIndent()

    /**
     * DOM / mount-state snapshot, evaluated by `WebView.snapshotDom(tag)`.
     *
     * Returns a plain object (evaluateJavascript serializes it), so a blank
     * screen can be told apart from a slow network *by evidence*:
     * `rootChildren=0` with `scripts=37` means the scripts arrived and the app
     * never mounted; `scripts=0` means nothing arrived at all.
     * `editors` counts the same visible composer elements the existing
     * page-ready probe uses, so both agree on "is the UI actually there".
     */
    fun domSnapshot(): String = """
(function () {
  try {
    // A blank root is only explainable next to the resource account, so the
    // same trigger flushes any pending `Res:` frame.
    if (window.__DSH_RES_PROBE__ && typeof window.__DSH_RES_PROBE__.flush === 'function') window.__DSH_RES_PROBE__.flush();
    var root = document.querySelector('#root, #app, [data-dsh-root]');
    var editors = -1;
    try {
      editors = Array.prototype.slice
        .call(document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]'))
        .filter(function (el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
        .length;
    } catch (_) { editors = -1; }
    var failed = window.__DSH_FAILED_SCRIPTS__ ? Array.prototype.slice.call(window.__DSH_FAILED_SCRIPTS__, 0, 10) : [];
    return {
      readyState: String(document.readyState || ''),
      title: String(document.title || '').slice(0, 80),
      path: String(location.pathname || '').slice(0, 120),
      rootChildren: root ? (root.childElementCount | 0) : -1,
      bodyLen: document.body ? String(document.body.innerHTML || '').length : -1,
      editors: editors,
      scripts: document.scripts ? document.scripts.length : -1,
      failedScripts: failed
    };
  } catch (error) {
    return { readyState: 'probe-error', title: '', path: '', rootChildren: -1, bodyLen: -1, editors: -1, scripts: -1, failedScripts: [] };
  }
})()
    """.trimIndent()

    /**
     * Injection self-check: which critical globals actually exist.
     *
     * Registration success (`addDocumentStartJavaScript` returning without
     * throwing) does not prove the script *ran* — on some HyperOS WebView builds
     * it silently does nothing. This probe checks the real footholds, posts one
     * `{kind:'inject-missing', name}` frame per absent global, and also returns
     * the list so the native side can log even when `DshNative` itself is the
     * missing piece (then no frame could ever arrive).
     */
    fun injectCheck(): String = """
(function () {
  var names = ['DshNative', '__DSH_MOBILE_BRIDGE__', '__DSH_VIEWPORT__', '__DSH_JS_ERRORS__', '__DSH_RES_PROBE__', '__DSH_OFFLINE_GUARD__'];
  var missing = [];
  for (var i = 0; i < names.length; i++) {
    try { if (!window[names[i]]) missing.push(names[i]); } catch (_) { missing.push(names[i]); }
  }
  for (var j = 0; j < missing.length; j++) {
    try {
      var api = window.DshNative;
      if (api && typeof api.onBridgeMessage === 'function') {
        api.onBridgeMessage(JSON.stringify({ kind: 'inject-missing', payload: { name: missing[j] } }));
      }
    } catch (_) {}
  }
  return { missing: missing };
})()
    """.trimIndent()

    /** Aggregate resource frame throttle, matching the run-log contract (5s). */
    const val RESOURCE_PUBLISH_MS = 5_000

    /** A resource slower than this counts as `slow` in the aggregate. */
    const val RESOURCE_SLOW_MS = 3_000

    /** Failure entries per frame; the native side caps log lines to the same. */
    const val RESOURCE_MAX_FAILURES = 10
}
