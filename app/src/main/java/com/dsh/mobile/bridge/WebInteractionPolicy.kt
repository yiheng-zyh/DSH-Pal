package com.dsh.mobile.bridge

/** Capability and ownership checks shared by optional interaction enhancements. */
object WebInteractionPolicy {
    val script = """
;(function(){
  if(window.top!==window)return;
  if(window.__DSH_INTERACTION__){window.__DSH_INTERACTION__.refresh();return;}
  var requested=true,isolated=new WeakMap();
  function read(){try{return !window.DshFiles||typeof DshFiles.enhancedInteractions!=='function'||DshFiles.enhancedInteractions();}catch(_){return true;}}
  function layout(){try{return typeof CSS!=='undefined'&&CSS.supports('selector(:is(*))')&&CSS.supports('selector(:has(*))');}catch(_){return false;}}
  function enabled(){return requested&&layout();}
  function owned(node){return !!node&&node.isConnected&&!node.closest('[data-dsh-mobile-keep-layout]');}
  function retain(){return enabled()&&typeof HTMLElement!=='undefined'&&'inert' in HTMLElement.prototype;}
  function retained(node){return owned(node)&&!node.closest('[data-dsh-mobile-no-retain]');}
  function leftToggle(side){
    if(!owned(side))return null;
    var marked=side.querySelector('button[data-sidebar-toggle],[class$="_logoRow"] button[class*="_toggle"]');
    if(marked&&!marked.closest('[data-dsh-mobile-keep-layout]'))return marked;
    return Array.from(side.querySelectorAll('button[aria-label],button[aria-controls]')).find(function(b){
      if(b.closest('[data-dsh-mobile-keep-layout]'))return false;
      return !!side.id&&b.getAttribute('aria-controls')===side.id||/^(打开侧边栏|收起侧边栏|Open sidebar|Close sidebar)$/.test(b.getAttribute('aria-label')||'');
    })||null;
  }
  function rightKnown(frame,rail){return owned(frame)&&owned(rail)&&!!(frame.hasAttribute('data-rightbar-collapsed')||frame.hasAttribute('data-rightbar-fullscreen')||rail.hasAttribute('data-rightbar-col')||rail.querySelector('[data-sidebar-right-open],[data-dockkit-host="dock"]'));}
  function left(shape){return retain()&&shape&&retained(shape.frame)&&owned(shape.center)&&retained(shape.left)&&leftToggle(shape.left)&&(!shape.right||rightKnown(shape.frame,shape.right));}
  function right(shape){
    var panel=shape&&shape.right&&shape.right.querySelector('[data-sidebar-right-open]');
    var surface=shape&&shape.right&&shape.right.querySelector('[data-sidebar-right-panel]');
    return retain()&&shape&&retained(shape.frame)&&retained(shape.right)&&owned(panel)&&retained(surface)&&rightClose(shape.right);
  }
  function rightClose(rail){return rail&&rail.querySelector('button[data-sidebar-right-toggle],button[aria-label="收起右侧边栏"],button[aria-label="Close right sidebar"]');}
  function isolate(node,on){
    if(!node)return;
    if(on){if(!isolated.has(node))isolated.set(node,node.inert===true);node.inert=true;}
    else if(isolated.has(node)){node.inert=isolated.get(node);isolated.delete(node);}
  }
  function animate(node,frames,options){
    try{if(!enabled()||!node||typeof node.animate!=='function')return null;
      var a=node.animate(frames,options);
      if(a&&a.finished&&typeof a.finished.then==='function'&&typeof a.finished.catch==='function'&&typeof a.cancel==='function'){a.finished.then(function(){},function(){});return a;}
      if(a&&typeof a.cancel==='function')a.cancel();
    }catch(_){}return null;
  }
  function listen(media,fn){if(media.addEventListener)media.addEventListener('change',fn);else if(media.addListener)media.addListener(fn);}
  function set(value){var next=!!value;if(next===requested)return;requested=next;document.dispatchEvent(new Event('dsh-interaction-policy'));}
  requested=read();
  try{if(window.DshNative)DshNative.onBridgeMessage(JSON.stringify({kind:'ui-layout',payload:'interaction capabilities layout='+layout()+' retention='+retain()+' enabled='+enabled()}));}catch(_){}
  window.__DSH_INTERACTION__={enabled:enabled,layoutSupported:layout,retain:retain,left:left,right:right,rightKnown:rightKnown,rightClose:rightClose,leftToggle:leftToggle,isolate:isolate,animate:animate,listen:listen,setEnabled:set,refresh:function(){set(read());}};
})();
""".trimIndent()
}
