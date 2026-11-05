(function () {
  if(window.top!==window)return;
  if(window.__DSH_INTERACTION__&&!__DSH_INTERACTION__.layoutSupported())return;
  if(window.__DSH_PAGE_MOTION__)window.__DSH_PAGE_MOTION__.dispose();
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)');
  var rows=new Map(),scheduled=false,disposed=false;
  function indicator(row,state){
    var selected=row.querySelector('[role="tab"][aria-selected="true"]');
    if(!selected||!row.isConnected)return;
    // Keep plugin-owned underlines. Only the official conversation row gets our underline.
    if(!row.matches('[data-conversation-tabs]'))return;
    if(!state.indicator){state.indicator=document.createElement('span');state.indicator.className='dsh-tab-indicator';state.indicator.setAttribute('aria-hidden','true');row.appendChild(state.indicator);}
    var r=selected.getBoundingClientRect(),base=row.getBoundingClientRect();
    state.indicator.style.width=r.width+'px';state.indicator.style.transform='translate3d('+(r.left-base.left+row.scrollLeft)+'px,0,0)';
  }
  function refresh(){
    scheduled=false;if(disposed)return;
    rows.forEach(function(state,row){if(!row.isConnected){rows.delete(row);}});
    document.querySelectorAll('[role="tablist"],[data-conversation-tabs]').forEach(function(row){
      if(row.closest('[data-dsh-mobile-keep-layout]'))return;
      if(!rows.has(row))rows.set(row,{generation:0,indicator:null});
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
    // Selection feedback is local. Do not scan/raster a large conversation or
    // wait for an idle period just to play a second animation after navigation.
    requestAnimationFrame(function(){
      if(!disposed && state.generation===token && row.isConnected)indicator(row,state);
    });
  }

  var observer=new MutationObserver(function(records){
    if(records.some(function(record){return record.type==='attributes'||Array.from(record.addedNodes).some(function(node){return node instanceof Element&&(node.matches('[role="tablist"],[data-conversation-tabs]')||node.querySelector('[role="tablist"],[data-conversation-tabs]'));})||Array.from(record.removedNodes).some(function(node){return node instanceof Element&&(node.matches('[role="tablist"],[data-conversation-tabs]')||node.querySelector('[role="tablist"],[data-conversation-tabs]'));});}))schedule();
  });
  observer.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['aria-selected']});
  document.addEventListener('click',clicked,true);window.addEventListener('resize',schedule);refresh();
  window.__DSH_PAGE_MOTION__={version:4,refresh:schedule,dispose:function(){disposed=true;observer.disconnect();document.removeEventListener('click',clicked,true);window.removeEventListener('resize',schedule);rows.forEach(function(state,row){state.generation++;if(state.indicator)state.indicator.remove();row.removeAttribute('data-dsh-mobile-tablist');});rows.clear();}};
})();
