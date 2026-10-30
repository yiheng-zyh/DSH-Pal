(function(){
  if(window.top!==window||window.__DSH_PHONE_RIGHT__)return;
  var policy=window.__DSH_INTERACTION__,ignored='[data-dsh-mobile-keep-layout]';
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)'),reduced=matchMedia('(prefers-reduced-motion:reduce)');
  var frame=null,rail=null,entry=null,observer=null,animation=null,closing=false,shown=false,generation=0,timer=null,active=false,intentUntil=0;
  function isolate(node,on){if(policy)policy.isolate(node,on);else if(node)node.inert=on;}
  function stop(){generation++;clearTimeout(timer);timer=null;if(animation)animation.cancel();animation=null;closing=false;if(rail)rail.removeAttribute('data-dsh-right-moving');}
  function nativeOpen(){var p=rail&&rail.querySelector('[data-sidebar-right-open]');return !!p&&p.getAttribute('data-sidebar-right-open')==='true';}
  function control(){return policy?policy.rightClose(rail):rail&&rail.querySelector('button[data-sidebar-right-toggle],button[aria-label="收起右侧边栏"],button[aria-label="Close right sidebar"]');}
  function animate(open){
    if(!rail||!rail.isConnected||!mobile.matches||reduced.matches)return null;
    var frames=[{transform:open?'translate3d(100%,0,0)':'translate3d(0,0,0)'},{transform:open?'translate3d(0,0,0)':'translate3d(100%,0,0)'}];
    var options={duration:open?240:180,easing:'cubic-bezier(.2,0,0,1)',fill:'forwards'},result=null;
    if(policy)result=policy.animate(rail,frames,options);
    else try{result=rail.animate(frames,options);if(!result||!result.finished||typeof result.finished.then!=='function')result=null;}catch(_){}
    if(result)rail.setAttribute('data-dsh-right-moving','');return result;
  }
  function finishClose(){if(active&&frame&&rail&&rail.isConnected&&nativeOpen()){frame.setAttribute('data-dsh-right-closed','');isolate(rail,true);}}
  function close(immediate){
    if(!active||!mobile.matches||!frame||!frame.hasAttribute('data-dsh-mobile-right-open'))return false;
    if(closing&&!immediate)return true;
    stop();closing=true;var token=generation,target=rail;
    if(immediate){finishClose();closing=false;return true;}
    animation=animate(false);
    if(!animation){finishClose();closing=false;return true;}
    var current=animation;
    function complete(){if(token!==generation||target!==rail)return;finishClose();clearTimeout(timer);timer=null;current.cancel();if(animation===current){animation=null;closing=false;target.removeAttribute('data-dsh-right-moving');}}
    current.finished.then(complete).catch(function(){});timer=setTimeout(complete,450);return true;
  }
  function open(){
    if(!active||!mobile.matches||!frame||!nativeOpen())return;
    if(window.__DSH_MOBILE_MOTION__&&__DSH_MOBILE_MOTION__.closeLeft)__DSH_MOBILE_MOTION__.closeLeft(true);
    stop();isolate(rail,false);frame.removeAttribute('data-dsh-right-closed');
  }
  function sync(){
    if(!active||!frame||!rail)return;
    if(!nativeOpen()) {stop();isolate(rail,false);frame.removeAttribute('data-dsh-right-closed');shown=false;return;}
    var next=frame.hasAttribute('data-dsh-mobile-right-open');
    if(next===shown)return;shown=next;if(!next)return;
    stop();var current=animate(true);animation=current;
    if(current){var token=generation;function release(){if(token!==generation)return;current.cancel();clearTimeout(timer);timer=null;if(animation===current){animation=null;if(rail)rail.removeAttribute('data-dsh-right-moving');}}
      current.finished.then(release).catch(function(){});timer=setTimeout(release,500);}
  }
  function suspend(){
    var hidden=frame&&frame.hasAttribute('data-dsh-right-closed'),button=hidden&&control();
    active=false;stop();if(observer)observer.disconnect();observer=null;if(entry)entry.remove();entry=null;
    if(rail)isolate(rail,false);if(frame)frame.removeAttribute('data-dsh-right-closed');
    if(hidden&&button&&button.isConnected)button.click();frame=rail=null;shown=false;
  }
  function bind(){
    var s=window.__DSH_MOBILE_COMPAT__&&__DSH_MOBILE_COMPAT__.snapshot();
    var allowed=mobile.matches&&s&&s.frame&&s.right&&(!policy||policy.right(s));
    if(!allowed){suspend();return;}
    if(s.frame===frame&&s.right===rail){sync();return;}
    suspend();frame=s.frame;rail=s.right;active=true;
    entry=document.createElement('button');entry.type='button';entry.className='dsh-mobile-right-entry';entry.setAttribute('aria-label','打开右侧边栏');
    entry.innerHTML='<svg width="20" height="20" viewBox="0 0 16 16" fill="none" aria-hidden="true"><rect x="2" y="2" width="12" height="12" rx="1" stroke="currentColor"/><path d="M10 2v12" stroke="currentColor"/></svg>';
    entry.addEventListener('click',open);frame.appendChild(entry);
    observer=new MutationObserver(function(records){
      if(frame&&frame.hasAttribute('data-dsh-right-closed')&&performance.now()<intentUntil&&records.some(function(r){
        return rail.contains(r.target)&&(r.attributeName==='aria-selected'&&r.target.getAttribute('aria-selected')==='true'||r.type==='childList'&&Array.from(r.addedNodes).some(function(n){return n instanceof Element&&(n.matches('[role="tab"][aria-selected="true"]')||n.querySelector('[role="tab"][aria-selected="true"]'));}));
      }))open();
      sync();
    });
    observer.observe(frame,{attributes:true,subtree:true,childList:true,attributeFilter:['data-dsh-mobile-right-open','data-sidebar-right-open','data-rightbar-fullscreen','data-rightbar-collapsed','aria-selected']});sync();
  }
  document.addEventListener('click',function(e){
    if(!active||!mobile.matches||!rail||!(e.target instanceof Element)||e.target.closest(ignored))return;
    var b=e.target.closest('button,a,[role="tab"]');
    if(b&&e.isTrusted&&!rail.contains(b)&&!b.closest('[data-dsh-mobile-sidebar]'))intentUntil=performance.now()+1000;
    if(e.target.closest('[data-sidebar-right-expand],[data-dsh-open-right],button[aria-label^="在侧边栏"]')){open();return;}
    if(b&&b===control()&&nativeOpen()){e.preventDefault();e.stopImmediatePropagation();close();}
  },true);
  document.addEventListener('dsh-mobile-capabilities',bind);document.addEventListener('dsh-interaction-policy',bind);
  if(policy)policy.listen(mobile,bind);else mobile.addEventListener('change',bind);
  window.__DSH_PHONE_RIGHT__={close:close,open:open};bind();
})();
