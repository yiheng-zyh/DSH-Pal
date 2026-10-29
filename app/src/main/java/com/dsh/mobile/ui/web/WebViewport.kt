package com.dsh.mobile.ui.web

/** Runs before phone scripts. Only presentation changes; credentials and sockets stay intact. */
object WebViewport {
    fun script() = """
;(function(){
  if(window.top!==window)return;
  if(window.__DSH_VIEWPORT__){window.__DSH_VIEWPORT__.apply();return;}
  var scheduled=false,last='',lastAdapt=null,lastAdaptDesktop=null,adaptOriginal=null,adaptWrapper=null;
  function syncAdapt(desktop){
    var api=window.__dshRemoteAdapt;
    if(!api||typeof api.setEnabled!=='function')return false;
    var changed=api!==lastAdapt||desktop!==lastAdaptDesktop;
    lastAdaptDesktop=desktop;
    if(api!==lastAdapt||api.setEnabled!==adaptWrapper){
      lastAdapt=api;adaptOriginal=api.setEnabled;
      // The remote UI may restore its own phone preference after hydration.
      // Respect this App's desktop selection on those later API calls too.
      var original=adaptOriginal;
      adaptWrapper=function(on){return original.call(api,lastAdaptDesktop?false:on);};
      api.setEnabled=adaptWrapper;changed=true;
    }
    if(changed){try{api.setEnabled(!desktop);}catch(_){lastAdapt=null;return false;}}
    return true;
  }
  function apply(){
    if(!document.documentElement)return;
    var mode='AUTO',desktop=false,width=0;
    try{mode=DshFiles.webLayoutMode();desktop=mode==='DESKTOP'||(mode==='AUTO'&&DshFiles.largeWindow());width=DshFiles.windowWidth();}catch(_){}
    // Keep the user's desktop preference, but avoid shrinking a full desktop
    // into a narrow split/freeform window. Restore it when the window expands.
    var compactWindow=false;
    try{compactWindow=width>0&&width<600&&typeof DshFiles.multiWindow==='function'&&DshFiles.multiWindow();}catch(_){}
    if(compactWindow)desktop=false;
    // DSH and plugins may each provide a viewport. Browsers use the later one;
    // keep every node consistent without deleting nodes owned by React/plugins.
    // Wide windows use their actual width (at least the desktop breakpoint),
    // rather than unnecessarily shrinking tablet text into a fixed 1280 canvas.
    var desktopWidth=mode==='DESKTOP'&&width<600?1280:Math.max(1024,width);
    var value=desktop?('width='+desktopWidth):('width='+Math.min(900,Math.max(320,width||screen.width)));
    var nodes=document.querySelectorAll('meta[name="viewport"]');
    if(!nodes.length){var node=document.createElement('meta');node.name='viewport';(document.head||document.documentElement).appendChild(node);nodes=[node];}
    Array.from(nodes).forEach(function(node){if(node.content!==value)node.content=value;});
    document.documentElement.setAttribute('data-dsh-web-layout',desktop?'desktop':'phone');
    syncAdapt(desktop);
    if(last!==value){last=value;requestAnimationFrame(function(){window.dispatchEvent(new Event('resize'));});}
  }
  window.__DSH_VIEWPORT__={apply:apply};
  function watchHead(){
    if(!document.head)return false;
    new MutationObserver(function(){if(scheduled)return;scheduled=true;queueMicrotask(function(){scheduled=false;apply();});})
      .observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
    return true;
  }
  if(!watchHead()){var initial=new MutationObserver(function(){if(watchHead()){initial.disconnect();apply();}});initial.observe(document,{childList:true,subtree:true});}
  window.addEventListener('resize',apply,{passive:true});
  document.addEventListener('DOMContentLoaded',apply);
  document.addEventListener('dsh-mobile-capabilities',apply);
  document.addEventListener('visibilitychange',function(){if(!document.hidden)apply();});
  // Optional remote adapter can arrive after the main shell. Bounded polling;
  // no requirement that the remote instance installs this specific plugin.
  var attempts=0,adapterPoll=setInterval(function(){apply();if(lastAdapt||++attempts>=120)clearInterval(adapterPoll);},500);
  apply();
})();
""".trimIndent()
}
