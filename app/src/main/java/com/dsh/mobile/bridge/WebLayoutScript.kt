package com.dsh.mobile.bridge

import android.content.Context
import org.json.JSONObject

/** Phone presentation only; React keeps ownership of controls, data and events. */
object WebLayoutScript {
    @Volatile private var css: String? = null
    @Volatile private var keyboard: String? = null
    @Volatile private var pageMotion: String? = null
    @Volatile private var compatibility: String? = null
    @Volatile private var rightMotion: String? = null

    fun build(context: Context): String {
        val stylesheet = css ?: context.assets.open("mobile-layout.css").bufferedReader().use { it.readText() }.trimStart('\uFEFF').also { css = it }
        val ime = keyboard ?: context.assets.open("keyboard-motion.js").bufferedReader().use { it.readText() }.also { keyboard = it }
        val motion = pageMotion ?: context.assets.open("page-motion.js").bufferedReader().use { it.readText() }.also { pageMotion = it }
        val compat = compatibility ?: context.assets.open("plugin-compat.js").bufferedReader().use { it.readText() }.also { compatibility = it }
        val right = rightMotion ?: context.assets.open("phone-right-motion.js").bufferedReader().use { it.readText() }.also { rightMotion = it }
        return compat + controller + "(" + JSONObject.quote(stylesheet) + ");" + ime + right + motion + if (com.dsh.mobile.BuildConfig.USB_DEVELOPMENT) usbHotReload else ""
    }

    private val usbHotReload = """
;(function(){
  if(window.top!==window||location.origin!=='http://dsh-usb.localhost:18765'||window.__DSH_USB_CSS__)return;
  window.__DSH_USB_CSS__=true;
  var busy=false;
  function refresh(){
    if(document.hidden||busy)return;busy=true;
    fetch('/__dsh_mobile/layout.css',{cache:'no-store'}).then(function(r){if(!r.ok)throw Error('style unavailable');return r.text()})
      .then(function(css){css=css.replace(/^\uFEFF+/,'');var s=document.getElementById('dsh-mobile-layout');if(s&&s.textContent!==css){s.textContent=css;try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:'USB stylesheet updated'}))}catch(_){}}})
      .catch(function(){}).finally(function(){busy=false});
  }
  setInterval(refresh,1200);refresh();
})();
""".trimIndent()

    private val controller = """
(function(css) {
  if(window.top!==window)return;
  css=css.replace(/^\uFEFF+/,'');
  if(window.__DSH_INTERACTION__&&!__DSH_INTERACTION__.layoutSupported())return;
  if (window.__DSH_MOBILE_LAYOUT__) return;
  window.__DSH_MOBILE_LAYOUT__ = {version:1};
  var frame=null, frameObserver=null, pending=new Set(), scheduled=false;
  var titleNode=null, titleObserver=null, lastTitle='';
  var rightOpen=null,rightPanel=null,rightGeneration=0,rightAnimation=null,rightClosing=false,rightBypass=false;
  function bindTitle() {
    if (!frame || (titleNode && titleNode.isConnected)) return;
    var next=frame.querySelector('[data-dsh-mobile-center] [class*="_crumbCurrent"]');
    if (!next) return;
    if (titleObserver) titleObserver.disconnect();
    titleNode=next;
    function publish() {
      var title=titleNode.textContent.trim().slice(0,180);
      if (title && title!==lastTitle) {
        lastTitle=title;
        try { window.DshNative.onBridgeMessage(JSON.stringify({kind:'page-title',payload:title})); } catch (_) {}
      }
    }
    titleObserver=new MutationObserver(publish);
    titleObserver.observe(titleNode,{childList:true,characterData:true,subtree:true});
    publish();
  }
  function log(message) {
    try { window.DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:message})); } catch (_) {}
  }
  var phoneRailStyles=new Map();
  function phoneStyle(node,key,value){
    var saved=phoneRailStyles.get(node);
    if(!saved){saved=new Map();phoneRailStyles.set(node,saved);}
    if(!saved.has(key))saved.set(key,[node.style.getPropertyValue(key),node.style.getPropertyPriority(key)]);
    if(node.style.getPropertyValue(key)!==value||node.style.getPropertyPriority(key)!=='important')node.style.setProperty(key,value,'important');
  }
  function restorePhoneRail(){
    phoneRailStyles.forEach(function(saved,node){saved.forEach(function(value,key){if(value[0])node.style.setProperty(key,value[0],value[1]);else node.style.removeProperty(key);});});
    phoneRailStyles.clear();
  }
  function fixPhoneRightRailGeometry(rail,open){
    if(!rail||!matchMedia('(max-width:900px) and (pointer:coarse)').matches){restorePhoneRail();return;}
    // A closed rail and its descendants cannot intercept the chat. Restore all
    // temporary inline geometry first; leave accessibility state owned by React.
    if(!open){
      restorePhoneRail();
      phoneStyle(rail,'display','none');
      return;
    }
    phoneStyle(rail,'display','block');
    phoneStyle(rail,'position','absolute');phoneStyle(rail,'inset','0');
    phoneStyle(rail,'width','100%');phoneStyle(rail,'height','100%');
    phoneStyle(rail,'z-index','60');
    var pane=rail.querySelector('[data-sidebar-right-panel]');
    if(pane){
      phoneStyle(pane,'position','absolute');phoneStyle(pane,'inset','0');
      phoneStyle(pane,'width','100%');phoneStyle(pane,'height','100%');
      phoneStyle(pane,'overflow-y','auto');phoneStyle(pane,'overflow-x','hidden');
      phoneStyle(pane,'touch-action','pan-y');
    }
    // The phone dock constrains its tab body and hides overflow there. The
    // outer panel then has no scroll range, even though the guide is clipped.
    // Keep the tab's existing height; allow its own content to scroll.
    rail.querySelectorAll('[data-sidebar-right-tab][class$="_tabBody"]').forEach(function(body){
      phoneStyle(body,'overflow-y','auto');phoneStyle(body,'overflow-x','hidden');
      phoneStyle(body,'touch-action','pan-y');phoneStyle(body,'pointer-events','auto');
    });
    // Only the dock's empty host is known to retain an off-screen transform.
    var host=rail.querySelector('[class*="_emptyTabHost"]');
    if(host)phoneStyle(host,'transform','none');
  }
  var leftRailStyles=new Map(),leftScrollOwner=null;
  function restoreLeftRail(){
    leftRailStyles.forEach(function(saved,node){saved.forEach(function(value,key){if(value[0])node.style.setProperty(key,value[0],value[1]);else node.style.removeProperty(key);});});
    leftRailStyles.clear();leftScrollOwner=null;
  }
  function leftStyle(node,key,value){
    var saved=leftRailStyles.get(node);
    if(!saved){saved=new Map();leftRailStyles.set(node,saved);}
    if(!saved.has(key))saved.set(key,[node.style.getPropertyValue(key),node.style.getPropertyPriority(key)]);
    if(node.style.getPropertyValue(key)!==value||node.style.getPropertyPriority(key)!=='important')node.style.setProperty(key,value,'important');
  }
  function fixLeftRailScroll(){
    var scroll=frame&&frame.querySelector('[data-dsh-mobile-sidebar-scroll]');
    var region=scroll&&scroll.querySelector('[data-dsh-mobile-workspace]');
    if(!matchMedia('(pointer:coarse)').matches||!scroll||!region||frame.hasAttribute('data-sidebar-collapsed')){restoreLeftRail();return;}
    if(leftScrollOwner!==scroll||!leftRailStyles.has(region)){restoreLeftRail();leftScrollOwner=scroll;}
    leftStyle(scroll,'overflow-y','auto');leftStyle(scroll,'overflow-x','hidden');
    leftStyle(scroll,'height','100%');leftStyle(scroll,'min-height','0');
    leftStyle(scroll,'overscroll-behavior-y','contain');
    leftStyle(region,'flex','0 0 auto');leftStyle(region,'min-height','240px');
    leftStyle(region,'height','45dvh');leftStyle(region,'max-height','420px');
    leftStyle(region,'overflow','hidden');leftStyle(region,'order','2');
    Array.from(scroll.children).forEach(function(row){
      leftStyle(row,'flex-shrink','0');
      if(row.matches('[class$="_logoRow"]'))leftStyle(row,'order','0');
      if(row.matches('[class$="_newSession"]'))leftStyle(row,'order','1');
      if(row.matches('[class$="_panelList"]'))leftStyle(row,'order','3');
      if(row.matches('[class$="_footArea"]'))leftStyle(row,'order','4');
    });
    region.querySelectorAll('[role="tree"],[data-workspace-tree],[data-conversations-list]').forEach(function(tree){
      leftStyle(tree,'overflow-y','auto');leftStyle(tree,'min-height','0');
      leftStyle(tree,'touch-action','pan-y');leftStyle(tree,'overscroll-behavior-y','auto');
    });
  }
  function syncRight() {
    if (!frame) return;
    var panel=frame.querySelector(':scope > [data-dsh-mobile-right]');
    var declared=panel&&panel.querySelector('[data-sidebar-right-open]');
    var open=!!panel && !frame.hasAttribute('data-dsh-right-closed') && (declared ? declared.getAttribute('data-sidebar-right-open')==='true' : (frame.hasAttribute('data-rightbar-fullscreen') || (frame.hasAttribute('data-rightbar-collapsed') ? false : getComputedStyle(panel).display!=='none')));
    frame.toggleAttribute('data-dsh-mobile-right-open',open);
    fixPhoneRightRailGeometry(panel,open);
    if(rightOpen===open&&rightPanel===panel)return;
    rightPanel=panel;
    rightOpen=open;
    var token=++rightGeneration;
    if(rightAnimation){rightAnimation.cancel();rightAnimation=null;}
    if(!panel)return;
    if(window.__DSH_SNAPSHOT_DRAWER__||matchMedia('(max-width:900px) and (pointer:coarse)').matches){panel.style.removeProperty('translate');panel.removeAttribute('data-dsh-right-moving');return;}
    if(!open||!matchMedia('(max-width:1100px) and (pointer:coarse)').matches||matchMedia('(prefers-reduced-motion:reduce)').matches){
      panel.style.removeProperty('translate');panel.removeAttribute('data-dsh-right-moving');return;
    }
    // One owner moves the complete opaque page. No CSS entrance animation and
    // no visible content at its final position before the slide begins.
    panel.setAttribute('data-dsh-right-moving','');
    panel.style.translate='100% 0';
    try{DshNative.onBridgeMessage(JSON.stringify({kind:'dismiss-keyboard'}));}catch(_){}
    requestAnimationFrame(function(){requestAnimationFrame(function(){
      if(token!==rightGeneration||!panel.isConnected)return;
      rightAnimation=panel.animate([{translate:'100% 0'},{translate:'0px 0px'}],{duration:280,easing:'cubic-bezier(.2,0,0,1)'});
      panel.style.translate='0px 0px';
      rightAnimation.finished.catch(function(){}).then(function(){if(token===rightGeneration){rightAnimation=null;panel.removeAttribute('data-dsh-right-moving');}});
      if(window.__DSH_MOBILE_COMPAT__)window.__DSH_MOBILE_COMPAT__.measure('right',280);
      log('right page slide started; single owner; durationMs=280');
    });});
  }
  document.addEventListener('click',function(event){
    if(matchMedia('(max-width:900px) and (pointer:coarse)').matches||window.__DSH_SNAPSHOT_DRAWER__||rightBypass||!rightOpen||!matchMedia('(max-width:1100px) and (pointer:coarse)').matches||!(event.target instanceof Element))return;
    var close=event.target.closest('button[aria-label="收起右侧边栏"]');
    if(!close||matchMedia('(prefers-reduced-motion:reduce)').matches)return;
    var panel=frame&&frame.querySelector(':scope > [data-dsh-mobile-right]');
    if(!panel||!panel.contains(close))return;
    event.preventDefault();event.stopImmediatePropagation();
    if(rightClosing)return;rightClosing=true;
    var current=getComputedStyle(panel).translate;
    ++rightGeneration;
    if(rightAnimation)rightAnimation.cancel();
    panel.style.translate=current;panel.setAttribute('data-dsh-right-moving','');
    rightAnimation=panel.animate([{translate:current==='none'?'0px 0px':current},{translate:'100% 0'}],{duration:220,easing:'cubic-bezier(.3,0,.8,.15)'});
    panel.style.translate='100% 0';
    rightAnimation.finished.then(function(){rightBypass=true;try{close.click();}finally{rightBypass=false;}}).catch(function(){}).finally(function(){rightClosing=false;});
  },true);
  function bindFrame() {
    if (frame && frame.isConnected && frame.hasAttribute('data-dsh-mobile-frame') && !frame.closest('[data-dsh-mobile-keep-layout]')) { syncRight(); fixLeftRailScroll(); return; }
    if(frame){restorePhoneRail();restoreLeftRail();frame.removeAttribute('data-dsh-mobile-right-open');}
    if(frameObserver)frameObserver.disconnect();
    frame=null;
    var next=document.querySelector('[data-dsh-mobile-frame]');
    if (!next) return;
    if (frameObserver) frameObserver.disconnect();
    frame=next;
    bindTitle();
    syncRight();fixLeftRailScroll();
    frameObserver=new MutationObserver(function(records){
      // Chat output can mutate hundreds of nodes. Only rail changes affect rail
      // geometry; keep them immediate so a newly opened phone dock is usable.
      if(records&&records.length&&!records.some(function(record){
        return record.target===frame || record.target instanceof Element &&
          !!record.target.closest('[data-dsh-mobile-sidebar],[data-dsh-mobile-right]');
      }))return;
      syncRight();fixLeftRailScroll();
    });
    frameObserver.observe(frame,{attributes:true,subtree:true,childList:true,attributeFilter:['data-rightbar-fullscreen','data-rightbar-collapsed','data-sidebar-right-open','data-dsh-right-closed','data-sidebar-collapsed','aria-hidden']});
    log('phone layout ready; optional regions detected');
  }
  function tagMenus(root) {
    if (!(root instanceof Element)) return;
    var menus=Array.from(root.querySelectorAll('[class*="_menu"]'));
    if (root.matches('[class*="_menu"]')) menus.push(root);
    menus.forEach(function(menu){
      if (!menu.hasAttribute('data-dsh-mobile-jobs') &&
          menu.querySelector('[class$="_sectionHeader"]') && menu.querySelector('[class$="_duration"]')) {
        menu.setAttribute('data-dsh-mobile-jobs','');
        log('background tasks fitted to phone viewport');
      }
    });
  }
  function flush() {
    scheduled=false;
    if(!frame||!frame.isConnected)bindFrame();
    bindTitle();
    pending.forEach(tagMenus); pending.clear();
  }
  document.addEventListener('dsh-mobile-capabilities',function(){bindFrame();});
  window.addEventListener('resize',function(){
    var mobile=matchMedia('(max-width:1100px) and (pointer:coarse)').matches;
    syncRight();fixLeftRailScroll();
    if(!mobile){
      ++rightGeneration;if(rightAnimation){rightAnimation.cancel();rightAnimation=null;}
      if(rightPanel){rightPanel.style.removeProperty('translate');rightPanel.removeAttribute('data-dsh-right-moving');}
    }
  },{passive:true});
  function start() {
    var style=document.createElement('style');style.id='dsh-mobile-layout';style.textContent=css;
    document.documentElement.appendChild(style);
    bindFrame(); tagMenus(document.documentElement);
    // Right rail scrolling on wide tablet windows.
    //
    // The plugin's dock panel declares `overflow: clip`, and a CSS rule from our
    // injected sheet does not win against it (measured: the sheet is present, the
    // media query matches, the selector matches, and the computed value is still
    // `clip` — so the plugin ships it with higher precedence, e.g. its own
    // !important in a later sheet). `clip` is also the reason no ancestor can be
    // scrolled: unlike hidden, it creates no scroll container at all, so the entry
    // list past the fold was unreachable by any gesture. Inline style is the one
    // thing that outranks a sheet rule, and it only touches overflow/flex, so the
    // plugin keeps ownership of everything else.
    //
    // Scoped to wide coarse-pointer windows: at <=900px the App's own overlay rules
    // already replace this chain, and there the list scrolls correctly.
    // Verified on the tablet: the entry list now moves under a swipe.
    // Re-applied on a timer because the panel is rebuilt whenever the dock switches
    // tabs or remounts, which drops the inline styles with it.
    // Do NOT widen this to phones: measured there it collapsed the rail to 0x0 and
    // took its toggle button with it. The phone's overlay layout needs no help.
    function fixRightRailScroll(){
      // Tablet-only on purpose. On the phone the rail is a full-screen overlay whose
      // layout already works, and applying this there collapsed the whole panel to
      // 0x0 (measured: rightRect=0x0, paneH=0 while data-dsh-mobile-right-open was
      // set) — the rail "opened" into nothing and its toggle became unreachable.
      // The flex:0 0 auto below assumes the tablet's wide dock, where panelBody sits
      // in a row; in the phone's column layout it makes the body take no space.
      if(!matchMedia('(min-width:901px) and (pointer:coarse)').matches) return;
      var rail=document.querySelector('[data-dsh-mobile-right]');
      if(!rail) return;
      var pane=rail.querySelector('[class*="_panel"]');
      if(pane){
        pane.style.setProperty('overflow-y','auto','important');
        pane.style.setProperty('overflow-x','hidden','important');
        pane.style.setProperty('touch-action','pan-y','important');
      }
      var body=rail.querySelector('[class*="_panelBody"]');
      if(body){
        body.style.setProperty('overflow','visible','important');
        body.style.setProperty('flex','0 0 auto','important');
      }
      Array.from(rail.querySelectorAll('[class*="_panel"] > *, [class*="_panelBody"] > *'))
        .forEach(function(n){ n.style.setProperty('flex-shrink','0','important'); });
    }
    setTimeout(fixRightRailScroll, 3000);
    setInterval(fixRightRailScroll, 4000);

    new MutationObserver(function(records){
      records.forEach(function(record){record.addedNodes.forEach(function(node){
        if (node.nodeType===1) pending.add(node);
      });});
      if (!scheduled && (pending.size || !frame || !frame.isConnected)) {
        scheduled=true;requestAnimationFrame(flush);
      }
    }).observe(document.documentElement,{childList:true,subtree:true});
  }
  if (document.documentElement) start();else document.addEventListener('DOMContentLoaded',start,{once:true});
})
""".trimIndent()
}
