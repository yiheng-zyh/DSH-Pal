;(function () {
  // Page-level uncaught error collector.
  //
  // Why a separate script: `WebChromeClient.onConsoleMessage` only proves that
  // *something* printed to the console. It cannot see a rejected promise, cannot
  // tell which script element failed to load, and never reports the column. This
  // script owns those three, deduplicates them, and hands one structured frame
  // per distinct error to the existing `DshNative.onBridgeMessage` channel.
  //
  // Constraints: runs at document-start, must never break the page, and must stay
  // inside the WebView 126 syntax subset (no ??=, ||=, Array.prototype.at,
  // structuredClone, top-level await). ES5 with var/function only.
  try {
    if (window.top !== window) return;
    if (window.__DSH_JS_ERRORS__) return;

    var counts = {};        // dedup key -> occurrences seen
    var announced = {};     // dedup key -> a frame for it actually went out
    var lastAt = {};        // dedup key -> timestamp of the last frame sent
    var failedUrl = {};     // absolute resource url -> 1 (dedup for load failures)
    var failedScripts = [];  // file names, newest last (read by the DOM snapshot)
    var kinds = 0;          // distinct error kinds seen
    var framesSent = 0;     // bridge frames emitted
    var MAX_KINDS = 20;     // bound: a crash loop must not flood the bridge
    var REPEAT_MS = 2000;   // repeats of one kind refresh the count at most this often

    window.__DSH_FAILED_SCRIPTS__ = failedScripts;
    window.__DSH_JS_ERRORS__ = {
      version: 1,
      failed: function () { return failedScripts.slice(0, 10); },
      stats: function () { return { kinds: kinds, frames: framesSent }; }
    };

    function now() { try { return Date.now(); } catch (_) { return 0; } }
    function text(value, max) {
      var s = (value === null || value === undefined) ? '' : String(value);
      return s.length > max ? s.slice(0, max) : s;
    }
    /** Trim a url down to its file name: the log line must name a file, not a path. */
    function fileOf(src) {
      var s = text(src, 600);
      var i = s.lastIndexOf('/');
      if (i >= 0) s = s.slice(i + 1);
      var q = s.indexOf('?');
      if (q >= 0) s = s.slice(0, q);
      var h = s.indexOf('#');
      if (h >= 0) s = s.slice(0, h);
      return text(s, 60);
    }
    function absUrl(src) {
      try { return new URL(text(src, 600), location.href).href; } catch (_) { return text(src, 600); }
    }
    /** Reuse the shell's existing transport; never open a second channel. */
    function post(payload) {
      try {
        var api = window.DshNative;
        if (!api || typeof api.onBridgeMessage !== 'function') return;
        api.onBridgeMessage(JSON.stringify(payload));
        framesSent++;
      } catch (_) {}
    }

    var TYPES = ['SyntaxError', 'ReferenceError', 'TypeError', 'RangeError', 'EvalError',
      'URIError', 'NotSupportedError', 'InvalidStateError', 'NetworkError', 'SecurityError',
      'DOMException', 'PromiseRejection', 'ResourceError', 'Error'];
    function typeOf() {
      var i, j;
      for (i = 0; i < arguments.length; i++) {
        var s = text(arguments[i], 400);
        if (!s) continue;
        for (j = 0; j < TYPES.length; j++) if (s.indexOf(TYPES[j]) >= 0) return TYPES[j];
      }
      return 'Error';
    }

    function report(source, line, column, message, stack, errorName) {
      try {
        var file = fileOf(source);
        if (!file) file = fileOf(location.href);
        var msg = text(message, 200);
        if (!msg) msg = '(no message)';
        var ln = parseInt(line, 10) || 0;
        var col = parseInt(column, 10) || 0;
        var key = file + ':' + ln + ':' + msg;
        var count = (counts[key] || 0) + 1;
        counts[key] = count;
        var first = count === 1;
        if (first) {
          kinds++;
          if (kinds > MAX_KINDS) return; // drop the kind entirely...
          announced[key] = 1;            // ...so its refreshes must be dropped too
        } else if (!announced[key]) {
          return;
        }
        // Same file+line+message: the first frame goes out immediately, later
        // ones only refresh the running total at most once per REPEAT_MS.
        var stamp = now();
        if (!first && stamp - (lastAt[key] || 0) < REPEAT_MS) return;
        lastAt[key] = stamp;
        post({
          kind: 'js-error',
          payload: {
            source: file, line: ln, column: col, message: msg,
            type: typeOf(errorName, msg, stack), count: count, stack: text(stack, 300)
          }
        });
      } catch (_) {}
    }

    function resourceError(node) {
      try {
        var tag = node && node.tagName ? String(node.tagName).toUpperCase() : '';
        var stylesheet = tag === 'LINK' && /stylesheet/i.test(text(node.rel, 40));
        if (tag !== 'SCRIPT' && !stylesheet) return;
        var url = absUrl(node.src || node.href || '');
        if (!url || url === 'undefined') return;
        var name = fileOf(url);
        if (!failedUrl[url]) {
          failedUrl[url] = 1;
          if (failedScripts.length < 20) failedScripts.push(name);
        }
        var key = 'load:' + url;
        if (counts[key]) return;
        counts[key] = 1;
        kinds++;
        if (kinds > MAX_KINDS) return;
        // Keep the whole url: a bad cached bundle is only recognizable by its
        // exact path, and masking is the log layer's job, not this one's.
        post({
          kind: 'js-error',
          payload: {
            source: name, line: 0, column: 0,
            message: 'Failed to load ' + (stylesheet ? 'stylesheet' : 'script') + ' ' + url,
            type: 'ResourceError', count: 1, stack: ''
          }
        });
      } catch (_) {}
    }

    // --- window.onerror ---------------------------------------------------
    // The page may replace this handler later; the capture listener below then
    // takes over, so an error is never missed and never counted twice.
    var previous = window.onerror;
    var ownerHandler = function (message, source, lineno, colno, error) {
      try {
        report(source, lineno, colno, message,
          error && error.stack ? error.stack : '',
          error && error.name ? error.name : '');
      } catch (_) {}
      try {
        if (typeof previous === 'function') return previous.call(window, message, source, lineno, colno, error);
      } catch (_) {}
      return false;
    };
    window.onerror = ownerHandler;

    // One capture listener on window sees both runtime errors (target === window)
    // and resource load failures on descendant elements (capture path includes window).
    window.addEventListener('error', function (event) {
      try {
        var target = event && event.target;
        if (!target || target === window) {
          if (window.onerror === ownerHandler) return; // already counted through onerror
          report(event.filename, event.lineno, event.colno,
            event.message || (event.error && event.error.message) || '',
            event.error && event.error.stack ? event.error.stack : '',
            event.error && event.error.name ? event.error.name : '');
          return;
        }
        resourceError(target);
      } catch (_) {}
    }, true);

    // --- unhandled rejections --------------------------------------------
    window.addEventListener('unhandledrejection', function (event) {
      try {
        var reason = event && event.reason;
        var detail = reason && reason.message ? text(reason.message, 180) : text(reason, 180);
        if (!detail) detail = '(empty reason)';
        report(location.href, 0, 0, 'Unhandled promise rejection: ' + detail,
          reason && reason.stack ? reason.stack : '',
          reason && reason.name ? reason.name : 'PromiseRejection');
      } catch (_) {}
    }, false);
  } catch (error) {
    // The collector failing to install is itself the finding: say so instead of
    // staying quiet, otherwise "no errors" is indistinguishable from "no probes".
    try {
      var api = window.DshNative;
      if (api && typeof api.onBridgeMessage === 'function') {
        api.onBridgeMessage(JSON.stringify({
          kind: 'inject-failed',
          payload: {
            script: 'dsh-js-errors',
            error: String((error && error.name) || error || '').slice(0, 60)
          }
        }));
      }
    } catch (_) {}
  }
})();
