(function () {
  if(window.top!==window)return;
  if(window.__DSH_PAGE_MOTION__)window.__DSH_PAGE_MOTION__.dispose();
  var reduced=matchMedia('(prefers-reduced-motion:reduce)'),mobile=matchMedia('(max-width:900px) and (pointer:coarse)');
  var rows=new Map(),scheduled=false,disposed=false;
  function report(message){try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-motion',payload:message}));}catch(_){}}
  function ownedMotion(area){var s=getComputedStyle(area);return s.animationName!=='none'||s.transform!=='none'||area.getAnimations().some(function(a){return a.playState==='running';})||s.transitionDuration.split(',').some(function(t){return parseFloat(t)>0;});}
  function region(button,row){
    var id=button.getAttribute('aria-controls'),panel=id&&document.getElementById(id);
    if(panel&&!panel.closest('[data-dsh-mobile-keep-layout]'))return panel;
    if(row.matches('[data-conversation-tabs]'))return document.querySelector('[data-dsh-mobile-center] [data-conversation-scroll]')||document.querySelector('[data-dsh-mobile-center] [data-conversation-content]');
    var host=row.closest('[data-dsh-mobile-right]');
    return host&&(host.querySelector('[class*="_tabHostBody_"]')||host.querySelector('[class$="_panelBody"]'));
  }
  function indicator(row,state){
    var selected=row.querySelector('[role="tab"][aria-selected="true"]');
    if(!selected||!row.isConnected)return;
    // Keep plugin-owned underlines. Only the official conversation row gets our underline.
    if(!row.matches('[data-conversation-tabs]'))return;
    if(!state.indicator){state.indicator=document.createElement('span');state.indicator.className='dsh-tab-indicator';state.indicator.setAttribute('aria-hidden','true');row.appendChild(state.indicator);}
    var r=selected.getBoundingClientRect(),base=row.getBoundingClientRect();
    state.indicator.style.width=r.width+'px';state.indicator.style.transform='translate3d('+(r.left-base.left+row.scrollLeft)+'px,0,0)';
  }
  function release(state){
    if(state.observer){state.observer.disconnect();state.observer=null;}
    if(state.prepared){var p=state.prepared;if(p.value)p.node.style.setProperty('will-change',p.value,p.priority);else p.node.style.removeProperty('will-change');state.prepared=null;}
  }
  function refresh(){
    scheduled=false;if(disposed)return;
    rows.forEach(function(state,row){if(!row.isConnected){if(state.animation)state.animation.cancel();release(state);rows.delete(row);}});
    document.querySelectorAll('[role="tablist"],[data-conversation-tabs]').forEach(function(row){
      if(row.closest('[data-dsh-mobile-keep-layout]'))return;
      if(!rows.has(row))rows.set(row,{generation:0,animation:null,indicator:null});
      row.setAttribute('data-dsh-mobile-tablist','');indicator(row,rows.get(row));
    });
  }
  function schedule(){if(!scheduled){scheduled=true;requestAnimationFrame(refresh);}}
  function clicked(event){
    if(!mobile.matches||!(event.target instanceof Element))return;
    var button=event.target.closest('[role="tab"],button');
    if(!button||button.closest('[data-dsh-mobile-keep-layout]')||button.getAttribute('aria-disabled')==='true'||button.disabled||button.getAttribute('aria-selected')==='true')return;
    var row=button.closest('[role="tablist"],[data-conversation-tabs]');
    if(!row)return;
    if(!rows.has(row))refresh();
    var state=rows.get(row);if(!state)return;
    var token=++state.generation;
    if(state.animation){state.animation.cancel();state.animation=null;}
    release(state);
    // Wait for the plugin's commit/scroll restoration to settle before starting
    // a compositor transition. Large panels get time to raster their first layer.
    var requested=performance.now(),last=requested,stable=0,lastMutation=requested,large=false;
    function prepare(now){
      if(disposed||state.generation!==token||!row.isConnected)return;
      var area=region(button,row);
      if(!area||!area.isConnected||reduced.matches){release(state);return;}
      if(!state.prepared||state.prepared.node!==area){
        release(state);large=area.querySelectorAll('*').length>1000;
        state.prepared={node:area,value:area.style.getPropertyValue('will-change'),priority:area.style.getPropertyPriority('will-change')};
        area.style.setProperty('will-change','opacity,translate');lastMutation=now;stable=0;
        state.observer=new MutationObserver(function(){lastMutation=performance.now();});
        state.observer.observe(area,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','style','hidden','aria-busy']});
      }
      var gap=now-last;last=now;stable=gap<34?stable+1:0;
      if(now-requested>800&&(stable<3||now-lastMutation<80)){
        release(state);report('tab transition skipped; renderer busy; prepareMs='+Math.round(now-requested));return;
      }
      if(stable<3||now-lastMutation<(large?120:80)){requestAnimationFrame(prepare);return;}
      indicator(row,state);
      if(ownedMotion(area)){release(state);return;}
      var box=area.getBoundingClientRect();if(!box.width||!box.height){release(state);return;}
      state.animation=area.animate([{opacity:.94,translate:'4px 0'},{opacity:1,translate:'0px 0px'}],{duration:200,easing:'cubic-bezier(.2,0,0,1)'});
      if(state.observer){state.observer.disconnect();state.observer=null;}
      var animation=state.animation;
      animation.finished.catch(function(){}).then(function(){if(state.generation===token){state.animation=null;release(state);}});
      if(window.__DSH_MOBILE_COMPAT__)window.__DSH_MOBILE_COMPAT__.measure('tab',200);
      report('tab transition; semantic target; durationMs=200 prepareMs='+Math.round(now-requested));
    }
    requestAnimationFrame(prepare);
  }

  var observer=new MutationObserver(function(records){
    if(records.some(function(record){return record.type==='attributes'||Array.from(record.addedNodes).some(function(node){return node instanceof Element&&(node.matches('[role="tablist"],[data-conversation-tabs]')||node.querySelector('[role="tablist"],[data-conversation-tabs]'));})||Array.from(record.removedNodes).some(function(node){return node instanceof Element&&(node.matches('[role="tablist"],[data-conversation-tabs]')||node.querySelector('[role="tablist"],[data-conversation-tabs]'));});}))schedule();
  });
  observer.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['aria-selected']});
  document.addEventListener('click',clicked,true);window.addEventListener('resize',schedule);refresh();
  window.__DSH_PAGE_MOTION__={version:3,refresh:schedule,dispose:function(){disposed=true;observer.disconnect();document.removeEventListener('click',clicked,true);window.removeEventListener('resize',schedule);rows.forEach(function(state,row){state.generation++;if(state.animation)state.animation.cancel();release(state);if(state.indicator)state.indicator.remove();row.removeAttribute('data-dsh-mobile-tablist');});rows.clear();}};
})();
