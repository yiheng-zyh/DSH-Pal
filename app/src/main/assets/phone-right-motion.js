(function(){
  if(window.top!==window||window.__DSH_PHONE_RIGHT__)return;
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)'),reduced=matchMedia('(prefers-reduced-motion:reduce)');
  var frame=null,rail=null,entry=null,observer=null,animation=null,closing=false,shown=false,generation=0;
  function stop(){generation++;if(animation)animation.cancel();animation=null;closing=false;if(rail)rail.removeAttribute('data-dsh-right-moving');}
  function nativeOpen(){var p=rail&&rail.querySelector('[data-sidebar-right-open]');return !!p&&p.getAttribute('data-sidebar-right-open')==='true';}
  function animate(open){
    if(!rail||!rail.isConnected||!mobile.matches||reduced.matches)return null;
    rail.setAttribute('data-dsh-right-moving','');
    return rail.animate([{translate:open?'100% 0':'0px 0px'},{translate:open?'0px 0px':'100% 0'}],{duration:open?240:180,easing:'cubic-bezier(.2,0,0,1)',fill:'forwards'});
  }
  function finishClose(){if(frame&&rail){frame.setAttribute('data-dsh-right-closed','');rail.inert=true;}}
  function close(immediate){
    if(!mobile.matches||!frame||!frame.hasAttribute('data-dsh-mobile-right-open'))return false;
    if(closing&&!immediate)return true;
    stop();closing=true;var token=generation,target=rail;
    if(immediate){finishClose();closing=false;return true;}
    animation=animate(false);
    if(!animation){finishClose();closing=false;return true;}
    var current=animation;
    current.finished.then(function(){if(token===generation&&target===rail&&target.isConnected)finishClose();}).catch(function(){}).finally(function(){
      current.cancel();if(animation===current){animation=null;closing=false;if(rail)rail.removeAttribute('data-dsh-right-moving');}
    });return true;
  }
  function open(){
    if(!mobile.matches||!frame||!nativeOpen())return;
    if(window.__DSH_MOBILE_MOTION__&&__DSH_MOBILE_MOTION__.closeLeft)__DSH_MOBILE_MOTION__.closeLeft(true);
    stop();rail.inert=false;frame.removeAttribute('data-dsh-right-closed');
  }
  function sync(){
    if(!frame||!rail)return;
    if(!mobile.matches||!nativeOpen()){
      stop();rail.inert=false;frame.removeAttribute('data-dsh-right-closed');shown=false;return;
    }
    var next=frame.hasAttribute('data-dsh-mobile-right-open');
    if(next===shown)return;shown=next;
    if(!next)return;
    stop();var current=animate(true);animation=current;
    if(current)current.finished.catch(function(){}).then(function(){current.cancel();if(animation===current){animation=null;if(rail)rail.removeAttribute('data-dsh-right-moving');}});
  }
  function bind(){
    var s=window.__DSH_MOBILE_COMPAT__&&__DSH_MOBILE_COMPAT__.snapshot();
    if(s&&s.frame===frame&&s.right===rail){sync();return;}
    stop();if(observer)observer.disconnect();if(entry)entry.remove();
    if(rail)rail.inert=false;if(frame)frame.removeAttribute('data-dsh-right-closed');
    frame=s&&s.frame;rail=s&&s.right;entry=null;shown=false;
    if(!frame||!rail)return;
    entry=document.createElement('button');entry.type='button';entry.className='dsh-mobile-right-entry';entry.setAttribute('aria-label','打开右侧边栏');
    // Same pane glyph as the standard right-sidebar control; no plugin content is copied.
    entry.innerHTML='<svg width="20" height="20" viewBox="0 0 16 16" fill="none" aria-hidden="true"><rect x="2" y="2" width="12" height="12" rx="1" stroke="currentColor"/><path d="M10 2v12" stroke="currentColor"/></svg>';
    entry.addEventListener('click',open);frame.appendChild(entry);
    observer=new MutationObserver(sync);observer.observe(frame,{attributes:true,subtree:true,attributeFilter:['data-dsh-mobile-right-open','data-sidebar-right-open','data-rightbar-fullscreen','data-rightbar-collapsed']});sync();
  }
  document.addEventListener('click',function(e){
    if(!mobile.matches||!rail||!(e.target instanceof Element))return;
    if(e.target.closest('button[aria-label^="在侧边栏"]')){open();return;}
    var b=e.target.closest('button[aria-label="收起右侧边栏"]');
    if(b&&rail.contains(b)&&nativeOpen()){e.preventDefault();e.stopImmediatePropagation();close();}
  },true);
  document.addEventListener('dsh-mobile-capabilities',bind);mobile.addEventListener('change',sync);
  window.__DSH_PHONE_RIGHT__={close:close,open:open};bind();
})();
