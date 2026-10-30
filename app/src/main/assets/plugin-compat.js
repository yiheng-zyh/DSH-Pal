(function () {
  if (window.top !== window || window.__DSH_MOBILE_COMPAT__) return;
  var frame=null,center=null,left=null,right=null,header=null,queued=false,last='',revision=0;
  var samples=[],rightStyles=new WeakMap(),rightContracts=new WeakSet();
  var workspaceRegion=null,workspaceScroll=null,sidebarEntry=null,entrySide=null;
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)');
  var ignored='[data-dsh-mobile-keep-layout]';
  function direct(root,selector) { return root && Array.from(root.children).find(function(n){return n.matches(selector)&&!n.matches(ignored);}) || null; }
  function mark(node,name,on) { if(node)node.toggleAttribute(name,!!on); }
  function report(message) { try { DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:message})); } catch(_){} }
  function discover() {
    var candidates=document.querySelectorAll('[data-dsh-frame],[data-dsh-mobile-frame],[class*="_frame"]');
    for(var i=0;i<candidates.length;i++) {
      var candidate=candidates[i];
      if(candidate.closest(ignored))continue;
      var middle=direct(candidate,'[data-dsh-slot="center"],[class*="_centerCol"]');
      if(middle)return {frame:candidate,center:middle};
    }
    return {frame:null,center:null};
  }
  function measure(kind,duration) {
    var start=performance.now(),last=start,count=0,max=0;
    requestAnimationFrame(function sample(now){
      if(document.hidden)return;
      max=Math.max(max,now-last);last=now;count++;
      if(now-start<duration){requestAnimationFrame(sample);return;}
      var entry={kind:kind,timeMs:Math.round(start),frames:count,maxGapMs:Math.round(max)};
      samples.push(entry);if(samples.length>10)samples.shift();
      report('motion '+kind+' frames='+count+' maxGapMs='+entry.maxGapMs+' durationMs='+duration);
    });
  }
  function snapshot() { return {frame:frame,center:center,left:left,right:right,header:header,revision:revision}; }
  function refresh() {
    queued=false;
    var found=discover(), old=[frame,center,left,right,header];
    frame=found.frame;center=found.center;
    left=direct(frame,'[data-dsh-slot="sidebar"],[class*="_sidebarCol"]');
    right=direct(frame,'[data-dsh-slot="rightbar"],[data-rightbar-col],[class$="_rightbarCol"]');
    // Recognise the official shell header by its structure, not every plugin's _header class.
    header=center&&Array.from(center.querySelectorAll('[data-conversation-header],[class$="_header"]')).find(function(n){
      return !n.closest(ignored)&&n.querySelector('[class$="_crumbs"],[class$="_titleRow"]');
    })||null;
    old.forEach(function(node,i){ if(node&&node!==[frame,center,left,right,header][i]) {
      if(i===0){node.removeAttribute('data-dsh-mobile-has-left');node.removeAttribute('data-dsh-mobile-has-right');node.removeAttribute('data-dsh-mobile-right-open');}
      if(i===3){
        var original=rightStyles.get(node);if(original){if(original.value)node.style.setProperty('translate',original.value,original.priority);else node.style.removeProperty('translate');rightStyles.delete(node);}
        node.removeAttribute('data-dsh-right-moving');node.removeAttribute('data-dsh-mobile-plugin-region');
        node.querySelectorAll('[data-dsh-mobile-right-surface],[data-dsh-mobile-right-host]').forEach(function(n){n.removeAttribute('data-dsh-mobile-right-surface');n.removeAttribute('data-dsh-mobile-right-host');});
      }
      if(i===4){node.style.removeProperty('--dsh-header-actions-row');node.removeAttribute('data-dsh-mobile-has-tabs');}
      node.removeAttribute(['data-dsh-mobile-frame','data-dsh-mobile-center','data-dsh-mobile-sidebar','data-dsh-mobile-right','data-dsh-mobile-header'][i]);
    }});
    if(frame&&(frame.hasAttribute('data-rightbar-collapsed')||frame.hasAttribute('data-rightbar-fullscreen')||right&&right.hasAttribute('data-rightbar-col')))rightContracts.add(frame);
    mark(frame,'data-dsh-mobile-frame',true);mark(center,'data-dsh-mobile-center',true);
    // Reserve a real work area only when a tree capability exists. Never guess
    // arbitrary plugin roots or create a blank workspace for missing plugins.
    var nextRegion=left&&left.querySelector('[data-sidebar-workspace],[class$="_regionArea"]');
    if(nextRegion&&!nextRegion.querySelector('[role="tree"],[data-workspace-tree],[data-conversations-list]'))nextRegion=null;
    var nextScroll=nextRegion&&nextRegion.parentElement;
    if(workspaceRegion&&workspaceRegion!==nextRegion)workspaceRegion.removeAttribute('data-dsh-mobile-workspace');
    if(workspaceScroll&&workspaceScroll!==nextScroll)workspaceScroll.removeAttribute('data-dsh-mobile-sidebar-scroll');
    workspaceRegion=nextRegion;workspaceScroll=nextScroll;
    mark(workspaceRegion,'data-dsh-mobile-workspace',true);mark(workspaceScroll,'data-dsh-mobile-sidebar-scroll',true);
    // Tag the right rail whenever the dock actually renders one.
    //
    // This used to require the frame to declare a dock state
    // (data-rightbar-collapsed / data-rightbar-fullscreen / rightbar-col) before the
    // attribute was set. Measured on the tablet's 1152px layout: the dock renders
    // `<div class="..._rightbarCol">` but declares none of those, so `right` existed
    // in the DOM while data-dsh-mobile-right was never applied. Every rule keyed on
    // that attribute — the overlay geometry, the scroll fix, the open/close state —
    // silently did nothing, which is why the rail could not be scrolled and why the
    // toggle had no target. The contract marker on line 53 still records whether the
    // dock exposes an open/closed state; this attribute only states that a rail is
    // present, which is what the rest of the code actually needs.
    mark(left,'data-dsh-mobile-sidebar',true);mark(right,'data-dsh-mobile-right',!!right);mark(header,'data-dsh-mobile-header',true);
    mark(frame,'data-dsh-mobile-has-left',left);mark(frame,'data-dsh-mobile-has-right',right);
    if(!right&&frame)frame.removeAttribute('data-dsh-mobile-right-open');
    if(header) {
      var tabs=!!header.querySelector('[data-conversation-tabs],[class$="_tabs"]');
      mark(header,'data-dsh-mobile-has-tabs',tabs);
      header.style.setProperty('--dsh-header-actions-row',tabs?'3':'2');
    }
    if(right) {
      if(!rightStyles.has(right))rightStyles.set(right,{value:right.style.getPropertyValue('translate'),priority:right.style.getPropertyPriority('translate')});
      right.setAttribute('data-dsh-mobile-plugin-region','');
      mark(right,'data-dsh-mobile-right-contract',!!right.querySelector('[data-sidebar-right-open]'));
      var surface=right.querySelector('[class$="_panel"]');
      if(surface)surface.setAttribute('data-dsh-mobile-right-surface','');
      var dock=right.querySelector('[data-dockkit-host="dock"]');
      if(dock)dock.setAttribute('data-dsh-mobile-right-host','');
    }
    // Keep navigation available when the remote plugin's floating mascot is hidden.
    // This is a presentation button; the real React control still owns the action.
    if(sidebarEntry&&(!sidebarEntry.isConnected||entrySide!==left)) {sidebarEntry.remove();sidebarEntry=null;entrySide=null;}
    if(left&&!sidebarEntry) {
      var sidebarControl=left.querySelector('[class$="_logoRow"] button[aria-label="打开侧边栏"],[class$="_logoRow"] button[aria-label="收起侧边栏"]');
      if(sidebarControl) {
        sidebarEntry=document.createElement('button');entrySide=left;
        sidebarEntry.type='button';sidebarEntry.className='dsh-mobile-sidebar-entry';
        sidebarEntry.setAttribute('aria-label','打开侧边栏');
        var glyphSource=left.querySelector('[data-slot="sidebar.brand.mark"] svg')||sidebarControl.querySelector('svg');
        if(glyphSource) {var glyph=glyphSource.cloneNode(true);glyph.setAttribute('aria-hidden','true');sidebarEntry.append(glyph);} else sidebarEntry.textContent='☰';
        sidebarEntry.addEventListener('click',function(){
          if(window.__DSH_MOBILE_MOTION__&&__DSH_MOBILE_MOTION__.openLeft){__DSH_MOBILE_MOTION__.openLeft();return;}
          var control=left&&left.querySelector('[class$="_logoRow"] button[aria-label="打开侧边栏"],[class$="_logoRow"] button[aria-label="收起侧边栏"]');
          if(control)control.click();
        });
        frame.append(sidebarEntry);
      }
    }
    var state=[!!frame,!!left,!!right,!!header].join('/');
    var changed=old.some(function(node,i){return node!==[frame,center,left,right,header][i];});
    if(changed||state!==last) {
      revision++;last=state;
      report('compat shell='+!!frame+' left='+!!left+' right='+!!right+' header='+!!header+' revision='+revision);
      document.dispatchEvent(new Event('dsh-mobile-capabilities'));
    }
  }
  function schedule() { if(!queued){queued=true;requestAnimationFrame(refresh);} }
  function fitPopup(menu) {
    if(!mobile.matches||menu.closest(ignored)||menu.parentElement&&menu.parentElement.closest('[role="menu"],[role="listbox"]'))return;
    var r=menu.getBoundingClientRect(), css=getComputedStyle(menu);
    if(!r.width||!r.height)return;
    if(css.position!=='absolute'&&css.position!=='fixed')return;
    var account=left&&left.querySelector('[data-slot="settings.launcher"] button[aria-expanded="true"][aria-haspopup="menu"],button[aria-label="账号菜单"][aria-expanded="true"]');
    if(menu.getAttribute('role')==='menu'&&account){
      var visibleMenus=Array.from(document.querySelectorAll('[role="menu"]')).filter(function(n){return n.getBoundingClientRect().height>0&&getComputedStyle(n).visibility==='visible';});
      if(visibleMenus.length===1)menu.setAttribute('data-dsh-mobile-account-menu','');
    }

    if(menu.getAttribute('role')==='dialog') {
      if(r.width>innerWidth-20||r.height>innerHeight-100) {
        menu.setAttribute('data-dsh-mobile-dialog','');
        Array.from(menu.children).forEach(function(row){var layout=getComputedStyle(row);if(layout.display==='flex'&&layout.flexDirection==='row'&&row.children.length>=2&&row.scrollWidth>innerWidth-20)row.setAttribute('data-dsh-mobile-stack','');});
      }
      return;
    }
    if(r.width>innerWidth-20||r.left<0||r.right>innerWidth||r.bottom>innerHeight||r.top<0||r.height>innerHeight-100)
      menu.setAttribute('data-dsh-mobile-menu','');
    // Only add an entrance to surfaces whose plugin does not already own motion.
    if(css.animationName==='none' && !css.transitionDuration.split(',').some(function(d){return parseFloat(d)>0;}))
      menu.setAttribute('data-dsh-mobile-popup','');
  }
  function inspect(root) {
    if(!(root instanceof Element)||root.closest(ignored))return;
    if(root.matches('[role="menu"],[role="listbox"],[role="dialog"]'))fitPopup(root);
    root.querySelectorAll('[role="menu"],[role="listbox"],[role="dialog"]').forEach(fitPopup);
  }
  var observer=new MutationObserver(function(records){
    var structural=!frame||!frame.isConnected||!center||!center.isConnected||!!header&&!header.isConnected||!!workspaceRegion&&!workspaceRegion.isConnected||!!workspaceScroll&&!workspaceScroll.isConnected;
    records.forEach(function(record){
      if(record.type==='attributes') { inspect(record.target);if(record.target===frame||record.target===right||record.target===left||record.target===center||record.target===header)structural=true;return; }
      if(record.target===frame||record.target===center||record.target===header||record.target===right||record.target===left||record.target===workspaceScroll||record.target===workspaceRegion)structural=true;
      if(left&&left.contains(record.target)) {
        Array.from(record.addedNodes).concat(Array.from(record.removedNodes)).forEach(function(node){
          if(node instanceof Element&&(node.matches('[class$="_regionArea"],[role="tree"],[data-workspace-tree],[data-conversations-list]')||node.querySelector('[role="tree"],[data-workspace-tree],[data-conversations-list]')))structural=true;
        });
      }
      record.addedNodes.forEach(function(node){
        if(!(node instanceof Element))return;
        if(node.matches('[data-dsh-frame],[data-dsh-slot],[class*="_centerCol"],[class*="_sidebarCol"],[class$="_rightbarCol"]'))structural=true;
        if(center&&center.contains(node)&&((!header&&(node.matches('[data-conversation-header],[class$="_header"]')||node.querySelector('[data-conversation-header],[class$="_header"]')))||node.matches('[data-conversation-tabs]')))structural=true;
        inspect(node);
      });
    });
    if(structural)schedule();
  });
  window.__DSH_MOBILE_COMPAT__={snapshot:snapshot,refresh:schedule,measure:measure,samples:samples,summary:function(){return {shell:!!frame,left:!!left,right:!!right,header:!!header,revision:revision,audioCapture:!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia),mediaRecorder:!!window.MediaRecorder,officialUploadHook:!!window.__DSH_FILE_UPLOAD__};}};
  function start(){refresh();inspect(document.documentElement);observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['hidden','aria-hidden','data-rightbar-collapsed','data-rightbar-fullscreen','data-dsh-mobile-keep-layout']});}
  if(document.documentElement)start();else document.addEventListener('DOMContentLoaded',start,{once:true});
  window.addEventListener('resize',schedule);mobile.addEventListener('change',schedule);
})();
