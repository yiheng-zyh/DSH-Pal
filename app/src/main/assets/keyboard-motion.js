(function () {
  if (window.top !== window || window.__DSH_MOBILE_IME__) return;
  var offset = 0;
  var generation = 0, animations = [], margins = new WeakMap();
  var viewportWidth = innerWidth, viewportHeight = innerHeight, viewportScale = visualViewport ? visualViewport.scale : 1;
  function positionMenus(root) {
    if (!(root instanceof Element)) return;
    var menus = Array.from(root.querySelectorAll('[role="menu"],[role="listbox"]'));
    if (root.matches('[role="menu"],[role="listbox"]')) menus.push(root);
    menus.forEach(function(menu) { menu.style.setProperty('--dsh-ime-offset', offset + 'px'); });
  }
  new MutationObserver(function(records) {
    if (offset <= 0) return;
    records.forEach(function(record) { record.addedNodes.forEach(positionMenus); });
  }).observe(document, {childList:true,subtree:true});
  // Some WebView builds dispatch a window resize for IME visibility even with
  // adjustNothing. Upstream mobile plugins reapply thousands of styles on it.
  // Install before those listeners; retain real resize, zoom and rotation events.
  window.addEventListener('resize', function(event) {
    var scale = visualViewport ? visualViewport.scale : 1;
    var unchanged = innerWidth === viewportWidth && innerHeight === viewportHeight && scale === viewportScale;
    viewportWidth = innerWidth; viewportHeight = innerHeight; viewportScale = scale;
    var focused = document.activeElement;
    var editing = focused && focused.matches('input,textarea,[contenteditable="true"],[role="textbox"]');
    if (event.isTrusted && unchanged && (offset > 0 || editing) && matchMedia('(max-width:900px) and (pointer:coarse)').matches) {
      event.stopImmediatePropagation();
    }
  }, true);
  // One summary per transition. No text, key events or message contents leave the page.
  function measureTransition(token, target) {
    var started = performance.now(), previous = started, maximum = 0, slow = 0, frames = 0;
    var initialHeight = innerHeight, resized = false;
    requestAnimationFrame(function sample(now) {
      if (token !== generation) return;
      var gap = now - previous; previous = now; maximum = Math.max(maximum, gap);
      if (gap > 34) slow++; frames++; resized = resized || innerHeight !== initialHeight;
      if (now - started < 800) { requestAnimationFrame(sample); return; }
      try { DshNative.onBridgeMessage(JSON.stringify({kind:'ui-motion',payload:'keyboard targetCss=' + Math.round(target) + ' frames=' + frames + ' maxGapMs=' + Math.round(maximum) + ' slowFrames=' + slow + ' viewportChanged=' + resized})); } catch (_) {}
    });
  }
  window.__DSH_MOBILE_IME__ = {
    get offsetCss() { return offset; },
    applyPixels: function (pixels, duration) {
      var next = Math.max(0, pixels / ((window.devicePixelRatio || 1) * (window.visualViewport ? window.visualViewport.scale : 1)));
      var seat = document.querySelector('[data-dsh-mobile-center] [data-composer-seat]');
      if (!seat || Math.abs(next - offset) < .5) return;
      var scroller = seat.closest('[data-conversation-scroll]');
      var flow = scroller && scroller.querySelector('[data-chat-flow]');
      var current = getComputedStyle(seat).translate;
      var atBottom = scroller && scroller.scrollHeight - scroller.clientHeight - scroller.scrollTop < 56;
      var oldTop = flow && flow.getBoundingClientRect().top;
      animations.forEach(function (a) { a.cancel(); }); animations = [];
      var token = ++generation;
      measureTransition(token, next);
      // One layout change per keyboard transition, never a viewport resize per frame.
      // A margin grows scroll range without resizing the message-list box and
      // waking all of its ResizeObservers during keyboard animation.
      if (flow) {
        if (!margins.has(flow)) margins.set(flow, parseFloat(getComputedStyle(flow).marginBottom) || 0);
        flow.style.marginBottom = (margins.get(flow) + next) + 'px';
      }
      if (atBottom) scroller.scrollTop = scroller.scrollHeight;
      var flowDelta = flow ? oldTop - flow.getBoundingClientRect().top : 0;
      var to = '0px ' + (-next) + 'px';
      seat.style.translate = to;
      offset = next;
      // Scope the inherited custom property to popups. Putting it on <html>
      // invalidates every message's style (4,000+ nodes in long conversations).
      positionMenus(document.documentElement);
      if (duration > 0 && !matchMedia('(prefers-reduced-motion:reduce)').matches) {
        var options = {duration:duration,easing:'cubic-bezier(.2,0,0,1)'};
        animations.push(seat.animate([{translate:current === 'none' ? '0px 0px' : current},{translate:to}],options));
        if (flow && Math.abs(flowDelta) > .5) animations.push(flow.animate([{translate:'0px '+flowDelta+'px'},{translate:'0px 0px'}],options));
        Promise.all(animations.map(function(a){return a.finished.catch(function(){});})).then(function(){if(token===generation)animations=[];});
      }
    }
  };
})();
