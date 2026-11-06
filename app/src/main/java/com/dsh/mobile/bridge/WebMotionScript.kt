package com.dsh.mobile.bridge

/** App-local presentation layer. Never changes the desktop UI or its layout store. */
object WebMotionScript {
    fun build(): String = WebInteractionPolicy.script + WebSidebarNavigation.script + WebDrawerMotion.script + """
(function () {
  if (window.top !== window) return;
  if (window.__DSH_MOBILE_MOTION__ || window.__DSH_SNAPSHOT_DRAWER__) return;
  window.__DSH_MOBILE_MOTION__ = { version: 1 };
  var mobile = matchMedia('(max-width: 900px) and (pointer: coarse)');
  var reduced = matchMedia('(prefers-reduced-motion: reduce)');
  var frame, side, center, scrim, stateObserver;
  var policy=window.__DSH_INTERACTION__,active=false;
  var closing = null, closingFade = null, closingTimer=null, bypass = false, generation = 0, sideBackup=null;
  // Desktop builds from 2026-11 onward render their own narrow-window drawer:
  // the frame carries data-sidebar-drawer and a [data-sidebar-mask] scrim at
  // z-index 70. Their drawer state is the source of truth on the phone too.
  var nativeDrawer=false;
  var sidebarSelector = '[class*="_sidebarCol"]';
  var toggleSelector = '[class$="_logoRow"] button[class*="_toggle"]';
  var style = document.createElement('style');
  style.id = 'dsh-mobile-motion';
  style.textContent = `
  .dsh-mobile-sidebar-scrim { display:none; }
  @media (max-width:900px) and (pointer:coarse) {
    [data-dsh-left-managed] {
      position:relative!important;
      grid-template-columns:minmax(0,1fr)!important;
      transition:none!important;
      isolation:isolate;
    }
    [data-dsh-left-managed] > [data-dsh-mobile-center] { grid-column:1/-1!important; min-width:0!important; width:100%!important; }
    [data-dsh-left-managed] > [data-dsh-mobile-sidebar] {
      position:absolute!important; inset:0 auto 0 0!important;
      width:min(86vw,340px)!important; min-width:0!important; max-width:calc(100vw - 44px)!important;
      height:100%!important; z-index:72!important;
      transform:translate3d(0,0,0); visibility:visible; pointer-events:auto;
      background:var(--dsw-alias-bg-module-platform,#202126);
      transition:transform 320ms cubic-bezier(.2,0,0,1),visibility 0s;
      contain:layout;
    }
    [data-dsh-mobile-sidebar][data-dsh-mobile-moving] { will-change:transform; }
    [data-dsh-left-managed]:is([data-sidebar-collapsed],[data-dsh-left-closed]) > [data-dsh-mobile-sidebar] {
      transform:translate3d(-105%,0,0); pointer-events:none!important; visibility:hidden;
      transition:transform 220ms cubic-bezier(.3,0,.8,.15),visibility 0s 220ms;
    }
    .dsh-mobile-sidebar-scrim {
      display:block;
      position:absolute; inset:0; z-index:40; background:rgba(0,0,0,.36);
      opacity:1; visibility:visible; touch-action:none;
      transition:opacity 260ms cubic-bezier(.2,0,0,1),visibility 0s;
    }
    /* A native-drawer desktop already paints its own darkening mask. This scrim
       then carries no colour at all: it exists only to turn "tap the empty area
       beside the drawer" into a close, so it must sit above that mask (70) and
       below the drawer itself (72). */
    [data-dsh-mobile-native-drawer] > .dsh-mobile-sidebar-scrim {
      z-index:71!important; background:transparent!important;
    }
    :is([data-sidebar-collapsed],[data-dsh-left-closed]) > .dsh-mobile-sidebar-scrim {
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
  function isOpen() { return active && frame && side && !frame.hasAttribute('data-sidebar-collapsed') && !frame.hasAttribute('data-dsh-left-closed'); }
  // Commit an App-local close. On a native-drawer build the desktop keeps its
  // own [data-sidebar-mask] scrim visible until the frame really collapses, so
  // flip the desktop state too — otherwise the mask parks over the chat with no
  // drawer behind it and the whole left column reads as a dead overlay.
  function settleClosed() {
    if (!frame || !side) return;
    frame.setAttribute('data-dsh-left-closed',''); isolate(side,true);
    if (nativeDrawer && !frame.hasAttribute('data-sidebar-collapsed')) {
      var control = sidebarToggle();
      if (control) { bypass = true; try { control.click(); } finally { bypass = false; } }
    }
  }
  function isolate(node,on){if(policy)policy.isolate(node,on);else if(node)node.inert=on;}
  // The CSS below pins the drawer above the desktop's mask at today's values.
  // Read the mask's actual z-index as well, so a future desktop build that
  // re-layers its drawer cannot bury the phone drawer again.
  function raiseAboveMask(){
    if(!nativeDrawer||!frame||!side)return;
    var mask=frame.querySelector('[data-sidebar-mask]');
    if(!mask)return;
    var above=parseInt(getComputedStyle(mask).zIndex,10);
    if(!isFinite(above))return;
    side.style.setProperty('z-index',String(Math.max(above+2,72)),'important');
  }
  function safeAnimation(node,frames,options){
    if(policy)return policy.animate(node,frames,options);
    try{var a=node.animate(frames,options);return a&&a.finished&&typeof a.finished.then==='function'?a:null;}catch(_){return null;}
  }
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
    n.removeAttribute('data-dsh-mobile-moving');isolate(n,false);
    if(frame){frame.removeAttribute('data-dsh-left-closed');frame.removeAttribute('data-dsh-left-managed');frame.removeAttribute('data-dsh-mobile-native-drawer');}sideBackup=null;
  }
  function sidebarToggle() {
    if(policy)return policy.leftToggle(side);
    return side&&(side.querySelector(toggleSelector)||Array.from(side.querySelectorAll('button[aria-label]')).find(function(b){var label=b.getAttribute('aria-label');return !/右|right/i.test(label)&&/侧边栏|sidebar/i.test(label);}));
  }
  function openLocal(toggle) {
    if(!active||!frame||!side||!toggle)return;
    if(window.__DSH_PHONE_RIGHT__)__DSH_PHONE_RIGHT__.close(true);
    if(closing){clearTimeout(closingTimer);closing.cancel();closing=null;if(closingFade){closingFade.cancel();closingFade=null;}}
    isolate(side,false);frame.removeAttribute('data-dsh-left-closed');
    // Mount the plugin's full sidebar once. Later closes retain that live DOM,
    // so reopening does not rebuild its workspace tree and conversation rows.
    if(frame.hasAttribute('data-sidebar-collapsed')){
      bypass=true;try{toggle.click();}finally{bypass=false;}
    }else{side.setAttribute('data-dsh-mobile-moving','');measure('open-local',320);}
  }
  function closeWithMotion(toggle) {
    if (!toggle) return;
    if (closing) {
      clearTimeout(closingTimer);var interrupted=closing;closing=null;interrupted.cancel();
      if(closingFade){closingFade.cancel();closingFade=null;}
      generation++;side.removeAttribute('data-dsh-mobile-moving');return;
    }
    if (reduced.matches) { settleClosed();return; }
    var currentSide = side;
    currentSide.setAttribute('data-dsh-mobile-moving','');
    closing = safeAnimation(currentSide,[
      {transform:getComputedStyle(currentSide).transform}, {transform:'translate3d(-105%,0,0)'}
    ], {duration:220,easing:'cubic-bezier(.3,0,.8,.15)',fill:'forwards'});
    var fade = scrim ? safeAnimation(scrim,[{opacity:1},{opacity:0}],{duration:220,fill:'forwards'}) : null;
    if(!closing||(scrim&&!fade)){if(closing)closing.cancel();if(fade)fade.cancel();closing=null;settleClosed();side.removeAttribute('data-dsh-mobile-moving');return;}
    var animation=closing;closingFade=fade;
    measure('close',220);
    function commitClose(){
      if(closing===animation&&currentSide===side&&frame&&frame.isConnected&&isOpen()){
        settleClosed();
      }
    }
    function releaseClose(){
      clearTimeout(closingTimer);animation.cancel();if(fade)fade.cancel();
      if(closing===animation){closing=null;closingFade=null;currentSide.removeAttribute('data-dsh-mobile-moving');}
    }
    animation.finished.then(commitClose,commitClose).then(function(){
      if(closing===animation)requestAnimationFrame(releaseClose);
    });
    closingTimer=setTimeout(function(){if(closing===animation){commitClose();releaseClose();}},500);
  }
  document.addEventListener('click',function (event) {
    if (bypass || !active || !mobile.matches || !frame || !side || !(event.target instanceof Element)||event.target.closest('[data-dsh-mobile-keep-layout]')) return;
    var toggle = policy?event.target.closest('button'):event.target.closest(toggleSelector) || event.target.closest('button[aria-label]');
    if(toggle&&(policy?toggle!==sidebarToggle():!toggle.matches(toggleSelector)&&toggle!==sidebarToggle()))return;
    if (toggle && side.contains(toggle)) {
      event.preventDefault(); event.stopImmediatePropagation();
      // Navigation adapters may replay a delayed collapse. It must neither
      // reverse an in-flight close nor reopen a locally closed drawer.
      if(!event.isTrusted&&(closing||frame.hasAttribute('data-dsh-left-closed')))return;
      if(frame.hasAttribute('data-dsh-left-closed'))openLocal(toggle);
      else if(isOpen()||closing)closeWithMotion(toggle);else openLocal(toggle);
    }
  },true);
  // Tapping the empty area beside an open drawer closes it. Native-drawer
  // desktops paint their own mask, but that mask is a sibling React node the
  // App cannot rely on: handle the tap here, at document capture, and stop it
  // so the desktop toggle does not run a second time (which would reopen).
  document.addEventListener('click',function (event) {
    if (bypass || !nativeDrawer || !active || !mobile.matches || !frame || !side || !isOpen()) return;
    if (!(event.target instanceof Element)) return;
    if (event.target.closest('[data-dsh-mobile-sidebar]')) return;
    event.preventDefault(); event.stopImmediatePropagation();
    var control = sidebarToggle();
    if (control) closeWithMotion(control); else settleClosed();
  }, true);

  function bind() {
    var shape=window.__DSH_MOBILE_COMPAT__ && window.__DSH_MOBILE_COMPAT__.snapshot();
    var allowed=!!shape&&!!shape.frame&&!!shape.center&&(!policy||!!policy.left(shape));
    if(!allowed){
      var hidden=frame&&frame.hasAttribute('data-dsh-left-closed'),control=hidden&&sidebarToggle();
      active=false;
      restoreSide();if(scrim){scrim.remove();scrim=null;}if(stateObserver)stateObserver.disconnect();
      if(closing){clearTimeout(closingTimer);closing.cancel();closing=null;}if(closingFade){closingFade.cancel();closingFade=null;}
      generation++;if(hidden&&control&&control.isConnected){bypass=true;try{control.click();}finally{bypass=false;}}
      frame=side=center=null;return;
    }
    var candidate=shape.frame,nextSide=shape.left,nextCenter=shape.center;
    if(frame===candidate&&side===nextSide&&center===nextCenter)return;
    restoreSide();
    if(stateObserver)stateObserver.disconnect();
    if(closing){clearTimeout(closingTimer);closing.cancel();closing=null;}
    if(closingFade){closingFade.cancel();closingFade=null;}
    if(scrim){scrim.remove();scrim=null;}
    frame=candidate;side=nextSide;center=nextCenter;active=true;
    if(!side){report('sidebar absent; center only');return;}
    nativeDrawer=!!candidate.hasAttribute&&candidate.hasAttribute('data-sidebar-drawer');
    raiseAboveMask();
    frame.setAttribute('data-dsh-left-managed','');
    frame.setAttribute('data-dsh-mobile-frame','');
    side.setAttribute('data-dsh-mobile-sidebar','');
    // Wallpaper plugins deliberately make the desktop sidebar transparent. An
    // overlay needs an opaque surface or two sets of text paint over each other.
    sideBackup={node:side,value:side.style.getPropertyValue('background-color'),priority:side.style.getPropertyPriority('background-color')};
    side.style.setProperty('background-color','#1e2025','important');
    center.setAttribute('data-dsh-mobile-center','');
    // A native-drawer desktop already paints its own opaque [data-sidebar-mask];
    // a second scrim only darkens the same pixels twice. Keep the scrim in that
    // case, but transparent: tapping the empty area beside the drawer has to
    // close it, and that tap must not reach the desktop mask behind (which would
    // toggle the drawer a second time).
    if(nativeDrawer)frame.setAttribute('data-dsh-mobile-native-drawer','');
    scrim=document.createElement('div'); scrim.className='dsh-mobile-sidebar-scrim';
    scrim.setAttribute('aria-hidden','true');
    // On a native-drawer desktop the tap is handled once at document level
    // (see below): the scrim there is a plain paint-free cover, and a listener
    // bound to it would run a second time.
    if(!nativeDrawer)scrim.addEventListener('click',function(){ closeWithMotion(sidebarToggle()); });
    frame.appendChild(scrim);
    stateObserver=new MutationObserver(function(){
      if (!mobile.matches || !isOpen()) return;
      raiseAboveMask();
      // The closed transform is already committed. Let CSS transition directly
      // to its open position instead of hiding it for two more animation frames.
      side.setAttribute('data-dsh-mobile-moving','');
      measure('open',320);
    });
    stateObserver.observe(frame,{attributes:true,attributeFilter:['data-sidebar-collapsed']});
    // Prepare optional navigation content while idle, off screen. The first
    // deliberate open then follows the same retained-content path as later ones.
    // Native-drawer desktops keep the sidebar mounted and only translate it, so
    // the warm-up is pointless there — and its "open the desktop sidebar while
    // the App keeps it hidden" state leaves the native mask on screen.
    if(!nativeDrawer&&mobile.matches&&frame.hasAttribute('data-sidebar-collapsed')&&window.requestIdleCallback){
      var warmFrame=frame,warmSide=side;
      requestIdleCallback(function(){
        if(document.hidden||frame!==warmFrame||side!==warmSide||!warmSide.isConnected||
          !mobile.matches||!frame.hasAttribute('data-sidebar-collapsed'))return;
        var control=sidebarToggle();if(!control)return;
        if(!active||policy&&!policy.left({frame:frame,left:side,center:center}))return;
        frame.setAttribute('data-dsh-left-closed','');isolate(side,true);
        bypass=true;try{control.click();}finally{bypass=false;}
      },{timeout:3000});
    }
    report('sidebar motion ready mobile='+mobile.matches+' reduced='+reduced.matches);
  }
  document.addEventListener('dsh-mobile-capabilities',bind);
  document.addEventListener('dsh-interaction-policy',bind);
  window.__DSH_MOBILE_MOTION__.isOpen=isOpen;
  window.__DSH_MOBILE_MOTION__.isClosing=function(){return !!closing;};
  window.__DSH_MOBILE_MOTION__.openLeft=function(){if(mobile.matches&&side)openLocal(sidebarToggle());};
  window.__DSH_MOBILE_MOTION__.closeLeft=function(immediate){
    if(active&&mobile.matches&&immediate&&frame&&side&&!frame.hasAttribute('data-sidebar-collapsed')){
      if(closing){clearTimeout(closingTimer);closing.cancel();closing=null;}if(closingFade){closingFade.cancel();closingFade=null;}
      generation++;side.removeAttribute('data-dsh-mobile-moving');
      settleClosed();return true;
    }
    if(mobile.matches&&closing)return true;
    if(!mobile.matches||!isOpen())return false;closeWithMotion(sidebarToggle());return true;
  };
  var mediaChanged=function(){
    if(mobile.matches)return;
    if(closing){clearTimeout(closingTimer);closing.cancel();closing=null;}
    if(closingFade){closingFade.cancel();closingFade=null;}
    generation++;if(frame)frame.removeAttribute('data-dsh-left-closed');
    if(side){isolate(side,false);side.removeAttribute('data-dsh-mobile-moving');}
  };
  if(policy)policy.listen(mobile,mediaChanged);else mobile.addEventListener('change',mediaChanged);
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
