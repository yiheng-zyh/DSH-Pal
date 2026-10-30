package com.dsh.mobile.bridge

/** Coordinate navigation and anchored menus without editing the remote plugin. */
object WebSidebarNavigation {
    val script = """
;(function(){
  if(window.top!==window||window.__DSH_SIDEBAR_NAV__)return;
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)');
  var policy=window.__DSH_INTERACTION__;
  var protectUntil=0,allowUntil=0,face=null,menuAnchor=null;
  function report(message){try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:message}));}catch(_){}}
  function shape(){return window.__DSH_MOBILE_COMPAT__&&__DSH_MOBILE_COMPAT__.snapshot();}
  function expanded(s){return s&&s.left&&s.frame&&!s.frame.hasAttribute('data-sidebar-collapsed')&&!s.frame.hasAttribute('data-dsh-left-closed');}
  function account(s){return s&&s.left&&s.left.querySelector('[data-slot="settings.launcher"] button[aria-haspopup="menu"],button[aria-label="账号菜单"][aria-haspopup="menu"]');}
  function protecting(){
    var s=shape(),a=account(s),now=performance.now();
    return mobile.matches&&expanded(s)&&now>=allowUntil&&(now<protectUntil||a&&a.getAttribute('aria-expanded')==='true');
  }
  function wrapFace(){
    var api=window.__dshRemoteAdapt;
    if(!api||typeof api.toggleSidebar!=='function'||face&&api.toggleSidebar===face.wrapper)return;
    var original=api.toggleSidebar;
    var wrapper=function(){
      if(policy&&!policy.enabled())return original.apply(this,arguments);
      if(protecting()){report('sidebar verified collapse ignored; account menu owns anchor');return;}
      var s=shape(),motion=window.__DSH_MOBILE_MOTION__;
      if(mobile.matches&&s&&s.frame&&motion&&
        (s.frame.hasAttribute('data-dsh-left-closed')||motion.isClosing&&motion.isClosing()))return;
      return original.apply(this,arguments);
    };
    face={api:api,original:original,wrapper:wrapper};api.toggleSidebar=wrapper;
  }
  function close(s,reason){
    if(!expanded(s))return;
    allowUntil=performance.now()+600;
    if(window.__DSH_MOBILE_MOTION__&&__DSH_MOBILE_MOTION__.closeLeft&&__DSH_MOBILE_MOTION__.closeLeft())return;
    var toggle=policy?policy.leftToggle(s.left):s.left.querySelector('[class$="_logoRow"] button[class*="_toggle"],[class$="_logoRow"] button[aria-label="收起侧边栏"],[class$="_logoRow"] button[aria-label="Close sidebar"]');
    if(toggle){report('sidebar close; reason='+(reason||'navigation'));if(window.__DSH_SNAPSHOT_DRAWER__&&__DSH_SNAPSHOT_DRAWER__.closeLeft){__DSH_SNAPSHOT_DRAWER__.closeLeft();}else toggle.click();}
  }
  function navigation(button,s){
    if(!button||!s.left.contains(button)||button.closest('[data-dsh-mobile-keep-layout]'))return false;
    if(button.disabled||button.getAttribute('aria-disabled')==='true'||button.hasAttribute('aria-haspopup')||button.hasAttribute('aria-pressed')||button.getAttribute('aria-expanded')!==null||/^(switch|checkbox)$/.test(button.getAttribute('role')||''))return false;
    // Top-level plugin pages, including third-party rows. Submenus and actions
    // retain their own lifecycle; don't guess from translated button text.
    return !!button.closest('[class$="_panelList"],[class$="_newSession"],[class$="_brand"]') ||
      !!button.closest('[class*="_sessionRow"]')&&!button.closest('[class*="_rowActions"]');
  }
  function clicked(event){
    if(!mobile.matches||policy&&!policy.enabled()||!(event.target instanceof Element))return;
    var s=shape();if(!expanded(s))return;
    var button=event.target.closest('button,a,[role="treeitem"][class*="_sessionRow"]'),a=account(s);
    var isToggle=button&&s.left.contains(button)&&(policy?button===policy.leftToggle(s.left):button.matches('[class$="_logoRow"] button[class*="_toggle"]')||/^(打开侧边栏|收起侧边栏|Open sidebar|Close sidebar)$/.test(button.getAttribute('aria-label')||''));
    if(event.target.closest('.dsh-drawer-scrim,.dsh-mobile-sidebar-scrim')||isToggle&&event.isTrusted){
      allowUntil=performance.now()+600;return;
    }
    if(button&&button===a){
      // The remote adapter's verified collapse calls the real button and then
      // its public layout face after 240ms. Protect both paths, not just one.
      menuAnchor=null;allowUntil=0;protectUntil=performance.now()+500;wrapFace();report('sidebar account interaction; preserve drawer anchor');return;
    }
    if(button&&s.left.contains(button)&&button.closest('[class$="_panelList"]')&&button.getAttribute('aria-haspopup')==='menu') {menuAnchor=button;return;}
    var fromNavigationMenu=button&&menuAnchor&&menuAnchor.isConnected&&menuAnchor.getAttribute('aria-expanded')==='true'&&button.matches('[role="menuitem"]')&&button.closest('[role="menu"]')&&!button.hasAttribute('aria-haspopup')&&!button.disabled&&button.getAttribute('aria-disabled')!=='true';
    if(navigation(button,s)||fromNavigationMenu){
      menuAnchor=null;
      allowUntil=performance.now()+600;protectUntil=0;
      var frame=s.frame,left=s.left;
      setTimeout(function(){var current=shape();if(current&&current.frame===frame&&current.left===left)close(current);},0);
      return;
    }
    if(isToggle&&!event.isTrusted&&protecting()){
      event.preventDefault();event.stopImmediatePropagation();
    }
  }
  // Before document-level auto-close listeners, while still allowing the real
  // account click to reach React and open/dismiss its own menu.
  window.addEventListener('click',clicked,true);
  document.addEventListener('dsh-mobile-capabilities',function(){
    var s=shape();if(s&&s.left){wrapFace();return;}
    protectUntil=allowUntil=0;
    if(face&&face.api.toggleSidebar===face.wrapper)face.api.toggleSidebar=face.original;
    face=null;
  });
  wrapFace();
  window.__DSH_SIDEBAR_NAV__={closeDrawer:function(){
    var s=shape();if(!mobile.matches||!expanded(s))return false;close(s,'back');return true;
  },dismissAccountMenu:function(){
    var s=shape(),a=account(s);
    if(mobile.matches&&expanded(s)&&a&&a.getAttribute('aria-expanded')==='true'){a.click();return true;}
    return false;
  }};
})();
""".trimIndent()
}
