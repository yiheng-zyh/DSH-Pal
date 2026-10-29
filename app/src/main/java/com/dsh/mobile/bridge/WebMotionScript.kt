package com.dsh.mobile.bridge

/** App-local presentation layer. Never changes the desktop UI or its layout store. */
object WebMotionScript {
    fun build(): String = WebSidebarNavigation.script + WebDrawerMotion.script + """
(function () {
  if (window.top !== window) return;
  if (window.__DSH_MOBILE_MOTION__ || window.__DSH_SNAPSHOT_DRAWER__) return;
  window.__DSH_MOBILE_MOTION__ = { version: 1 };
  var mobile = matchMedia('(max-width: 900px) and (pointer: coarse)');
  var reduced = matchMedia('(prefers-reduced-motion: reduce)');
  var frame, side, center, scrim, stateObserver;
  var closing = null, bypass = false, generation = 0, sideBackup=null;
  var sidebarSelector = '[class*="_sidebarCol"]';
  var toggleSelector = '[class$="_logoRow"] [class*="_iconButton"]';
  var style = document.createElement('style');
  style.id = 'dsh-mobile-motion';
  style.textContent = `
  .dsh-mobile-sidebar-scrim { display:none; }
  @media (max-width:900px) and (pointer:coarse) {
    [data-dsh-mobile-frame] {
      position:relative!important;
      grid-template-columns:minmax(0,1fr)!important;
      transition:none!important;
      isolation:isolate;
    }
    [data-dsh-mobile-center] { grid-column:1/-1!important; min-width:0!important; width:100%!important; }
    [data-dsh-mobile-sidebar] {
      position:absolute!important; inset:0 auto 0 0!important;
      width:min(86vw,340px)!important; min-width:0!important; max-width:calc(100vw - 44px)!important;
      height:100%!important; z-index:50!important;
      transform:translate3d(0,0,0); visibility:visible; pointer-events:auto;
      background:var(--dsw-alias-bg-module-platform,#202126);
      transition:transform 320ms cubic-bezier(.2,0,0,1),visibility 0s;
      contain:layout;
    }
    [data-dsh-mobile-sidebar][data-dsh-mobile-moving] { will-change:transform; }
    [data-dsh-mobile-frame][data-sidebar-collapsed] [data-dsh-mobile-sidebar] {
      transform:translate3d(-105%,0,0); pointer-events:none!important; visibility:hidden;
      transition:transform 220ms cubic-bezier(.3,0,.8,.15),visibility 0s 220ms;
    }
    .dsh-mobile-sidebar-scrim {
      display:block;
      position:absolute; inset:0; z-index:40; background:rgba(0,0,0,.36);
      opacity:1; visibility:visible; touch-action:none;
      transition:opacity 260ms cubic-bezier(.2,0,0,1),visibility 0s;
    }
    [data-sidebar-collapsed] > .dsh-mobile-sidebar-scrim {
      opacity:0; visibility:hidden; pointer-events:none;
      transition:opacity 220ms ease,visibility 0s 220ms;
    }
    [data-dsh-mobile-header] > button { transition:background-color 140ms ease,opacity 140ms ease; }
    [data-dsh-mobile-popup] {
      animation:dsh-mobile-surface-in 160ms cubic-bezier(.2,0,0,1);
    }
    @keyframes dsh-mobile-surface-in { from { opacity:0; translate:0 5px; } to { opacity:1; translate:0 0; } }
  }
  @media (prefers-reduced-motion:reduce) {
    [data-dsh-mobile-sidebar],.dsh-mobile-sidebar-scrim { transition:none!important; }
    [data-dsh-mobile-popup] { animation:none!important; }
  }`;

  function report(message) {
    try { window.DshNative.onBridgeMessage(JSON.stringify({kind:'ui-motion',payload:message})); } catch (_) {}
  }
  function isOpen() { return frame && side && !frame.hasAttribute('data-sidebar-collapsed'); }
  function measure(label, duration) {
    var token = ++generation, start = performance.now(), last = start, count = 0, gaps = 0, max = 0;
    var width = center.getBoundingClientRect().width, first = side.getBoundingClientRect().left, middle = first;
    var sampled = false, intervals = [];
    function sample(now) {
      if (token !== generation || !frame.isConnected) return;
      var delta = now - last; last = now; count++; max = Math.max(max, delta);
      intervals.push(delta); if (delta > 25) gaps++;
      if (!sampled && now-start > duration*.45) { middle = side.getBoundingClientRect().left; sampled = true; }
      if (now-start < duration+50) { requestAnimationFrame(sample); return; }
      report('sidebar '+label+' frames='+count+' maxGapMs='+Math.round(max)+' slowFrames='+gaps+
        ' budgetMs='+((intervals.sort(function(a,b){return a-b;})[Math.floor(intervals.length/2)]||0).toFixed(1))+
        ' centerWidthDelta='+Math.round(center.getBoundingClientRect().width-width)+
        ' x='+Math.round(first)+'/'+Math.round(middle)+'/'+Math.round(side.getBoundingClientRect().left)+
        ' reduced='+reduced.matches);
      side.removeAttribute('data-dsh-mobile-moving');
    }
    requestAnimationFrame(sample);
  }
  function restoreSide() {
    if(!sideBackup)return;
    var n=sideBackup.node;
    if(sideBackup.value)n.style.setProperty('background-color',sideBackup.value,sideBackup.priority);else n.style.removeProperty('background-color');
    n.removeAttribute('data-dsh-mobile-moving');sideBackup=null;
  }
  function sidebarToggle() {
    return side&&(side.querySelector(toggleSelector)||Array.from(side.querySelectorAll('button[aria-label]')).find(function(b){var label=b.getAttribute('aria-label');return !/右|right/i.test(label)&&/侧边栏|sidebar/i.test(label);}));
  }
  function closeWithMotion(toggle) {
    if (closing || !toggle) return;
    if (reduced.matches) { bypass=true; try { toggle.click(); } finally { bypass=false; } return; }
    var currentSide = side;
    currentSide.setAttribute('data-dsh-mobile-moving','');
    closing = currentSide.animate([
      {transform:getComputedStyle(currentSide).transform}, {transform:'translate3d(-105%,0,0)'}
    ], {duration:220,easing:'cubic-bezier(.3,0,.8,.15)',fill:'forwards'});
    var fade = scrim.animate([{opacity:1},{opacity:0}],{duration:220,fill:'forwards'});
    measure('close',220);
    closing.finished.then(function () {
      // Let React switch to its rail only after the expanded contents left the screen.
      if (frame.isConnected && isOpen()) {
        bypass=true; try { toggle.click(); } finally { bypass=false; }
      }
    }).catch(function () {}).finally(function () {
      requestAnimationFrame(function () {
        if (closing) closing.cancel(); closing=null; fade.cancel();
      });
    });
  }
  document.addEventListener('click',function (event) {
    if (bypass || !mobile.matches || !isOpen() || !(event.target instanceof Element)) return;
    var toggle = event.target.closest(toggleSelector) || event.target.closest('button[aria-label]');
    if(toggle&&!toggle.matches(toggleSelector)&&toggle!==sidebarToggle())return;
    if (toggle && side.contains(toggle)) {
      event.preventDefault(); event.stopImmediatePropagation(); closeWithMotion(toggle);
    }
  },true);

  function bind() {
    var shape=window.__DSH_MOBILE_COMPAT__ && window.__DSH_MOBILE_COMPAT__.snapshot();
    if(!shape || !shape.frame || !shape.center){
      restoreSide();if(scrim){scrim.remove();scrim=null;}if(stateObserver)stateObserver.disconnect();
      if(closing){closing.cancel();closing=null;}generation++;frame=side=center=null;return;
    }
    var candidate=shape.frame,nextSide=shape.left,nextCenter=shape.center;
    if(frame===candidate&&side===nextSide&&center===nextCenter)return;
    restoreSide();
    if(stateObserver)stateObserver.disconnect();
    if(closing){closing.cancel();closing=null;}
    if(scrim){scrim.remove();scrim=null;}
    frame=candidate;side=nextSide;center=nextCenter;
    if(!side){report('sidebar absent; center only');return;}
    frame.setAttribute('data-dsh-mobile-frame','');
    side.setAttribute('data-dsh-mobile-sidebar','');
    // Wallpaper plugins deliberately make the desktop sidebar transparent. An
    // overlay needs an opaque surface or two sets of text paint over each other.
    sideBackup={node:side,value:side.style.getPropertyValue('background-color'),priority:side.style.getPropertyPriority('background-color')};
    side.style.setProperty('background-color','#1e2025','important');
    center.setAttribute('data-dsh-mobile-center','');
    scrim=document.createElement('div'); scrim.className='dsh-mobile-sidebar-scrim';
    scrim.setAttribute('aria-hidden','true');
    scrim.addEventListener('click',function(){ closeWithMotion(sidebarToggle()); });
    frame.appendChild(scrim);
    stateObserver=new MutationObserver(function(){
      if (!mobile.matches || !isOpen()) return;
      var prepared = performance.now(), target = side;
      target.setAttribute('data-dsh-mobile-moving','');
      target.style.setProperty('transition','none','important');
      target.style.setProperty('transform','translate3d(-105%,0,0)','important');
      target.style.setProperty('visibility','hidden','important');
      // Separate React's first mount/layout from the compositor animation.
      // Otherwise its expensive first paint consumes the transition's duration.
      requestAnimationFrame(function(){ requestAnimationFrame(function(){
        target.style.removeProperty('transition');
        target.style.removeProperty('transform');
        target.style.removeProperty('visibility');
        if (isOpen()) {
          report('sidebar prepareMs='+Math.round(performance.now()-prepared));
          measure('open',320);
        }
      }); });
    });
    stateObserver.observe(frame,{attributes:true,attributeFilter:['data-sidebar-collapsed']});
    report('sidebar motion ready mobile='+mobile.matches+' reduced='+reduced.matches);
  }
  document.addEventListener('dsh-mobile-capabilities',bind);
  function start() {
    if (!style.isConnected) document.documentElement.appendChild(style);
    bind();
    // After binding, this callback is constant-time; never re-scan chat messages.
    new MutationObserver(function(){ if (!frame || !frame.isConnected) bind(); })
      .observe(document.documentElement,{childList:true,subtree:true});
  }
  if (document.documentElement) start(); else document.addEventListener('DOMContentLoaded',start,{once:true});
})();
""".trimIndent()
}
