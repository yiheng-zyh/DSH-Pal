package com.dsh.mobile.bridge

/** Browser-owned snapshots isolate a drawer slide from React's large chat commit. */
object WebDrawerMotion {
    val script = """
;(function(){
  // Phone drawers use the compositor motion controller below. A whole-document
  // snapshot waits for chat commits and adds latency before the first frame.
  if(matchMedia('(max-width:900px) and (pointer:coarse)').matches)return;
  if(window.top!==window||!document.startViewTransition||typeof CSS==='undefined'||typeof CSS.supports!=='function'||!CSS.supports('view-transition-name','dsh-left-drawer'))return;
  if(window.__DSH_SNAPSHOT_DRAWER__)return;
  var mobile=matchMedia('(max-width:900px) and (pointer:coarse)'),reduced=matchMedia('(prefers-reduced-motion:reduce)');
  var policy=window.__DSH_INTERACTION__;
  var shape=null,scrim=null,transition=null,bypass=false,desired=false,desiredKind="left",generation=0,intents=0;
  var samples=[];
  function report(message){try{DshNative.onBridgeMessage(JSON.stringify({kind:'ui-motion',payload:message}));}catch(_){}}
  function open(){return !!shape&&!!shape.left&&!shape.frame.hasAttribute('data-sidebar-collapsed');}
  function rightOpen(){return !!shape&&shape.frame.hasAttribute('data-dsh-mobile-right-open');}
  function state(kind){return kind==='right'?rightOpen():open();}
  function rightToggle(wanted){return shape&&shape.frame.querySelector(wanted?'button[aria-label="打开右侧边栏"]':'button[aria-label="收起右侧边栏"]');}
  function toggle(){return shape&&shape.left&&shape.left.querySelector('[class$="_logoRow"] button[class*="_toggle"],button[aria-label="收起侧边栏"],button[aria-label="打开侧边栏"],button[aria-label="Close sidebar"],button[aria-label="Open sidebar"]');}
  function original(button){bypass=true;try{button.click();}finally{bypass=false;}}
  function settle(){return new Promise(function(resolve){setTimeout(resolve,0);});}
  async function move(button,wanted,kind){
    if(policy&&!policy.enabled())return;
    kind=kind||"left";desired=wanted;desiredKind=kind;var intent=++intents;
    if(transition||!button||!shape)return;
    if(reduced.matches||document.hidden){original(button);return;}
    var token=++generation,started=performance.now(),target=kind==="right"?shape.right:shape.left;
    if(!target){original(button);return;}
    document.documentElement.setAttribute('data-dsh-drawer-transition','');
    var current;
    try{current=document.startViewTransition(async function(){
      if(token!==generation||!target.isConnected)return;
      if(state(kind)!==wanted)original(button);
      // React's commit must finish before the browser captures the new surface.
      await settle();
    });}catch(error){document.documentElement.removeAttribute('data-dsh-drawer-transition');original(button);return;}
    transition=current;var running=false;
    current.finished.catch(function(){});
    // Hard deadline on the whole capture.
    // `finished` can hang (or never settle) when the WebView stops producing frames,
    // and while it hangs `transition` stays non-null: every later tap is ignored by
    // the `if(transition)return` guard at the top, so the drawer button stops
    // responding entirely. Measured on the phone: one tap removed the right rail and
    // its toggle with no recovery short of restarting the app. If the capture has not
    // finished by the deadline, drop it and make sure the real control did get
    // clicked, so the plugin's own state and the DOM agree again.
    var deadlineTimer=setTimeout(function(){
      if(token!==generation)return;
      if(transition===current){
        try{current.skipTransition();}catch(_){}
        transition=null;
        document.documentElement.removeAttribute('data-dsh-drawer-transition');
      }
      if(shape&&target.isConnected&&state(kind)!==wanted){
        original((kind==="right"?rightToggle(wanted):toggle())||button);
      }
      report('sidebar snapshot timeout; state repaired; drawer='+kind);
    }, 700);
    try{
      await current.ready;
      if(token!==generation)return;
      var prepared=Math.round(performance.now()-started),last=null,gaps=[];running=true;
      function sample(now){if(!running||token!==generation)return;if(last!==null)gaps.push(now-last);last=now;requestAnimationFrame(sample);}
      requestAnimationFrame(sample);
      await current.finished;
      running=false;
      var sorted=gaps.slice().sort(function(a,b){return a-b;}),budget=sorted[Math.floor(sorted.length/2)]||0;
      var entry={action:wanted?'open':'close',drawer:kind,prepareMs:prepared,frames:gaps.length,budgetMs:+budget.toFixed(1),maxGapMs:Math.round(Math.max.apply(Math,[0].concat(gaps))),missed:gaps.filter(function(g){return budget&&g>budget*1.75;}).length};
      samples.push(entry);if(samples.length>12)samples.shift();
      report('sidebar snapshot '+JSON.stringify(entry));
    }catch(error){
      // Failed capture must never swallow the user's actual navigation.
      if(token===generation&&target.isConnected&&state(kind)!==wanted){original((kind==="right"?rightToggle(wanted):toggle())||button);await settle();}
      report('sidebar snapshot unavailable; native state retained; reason='+String(error&&error.name||'capture')); 
    }finally{
      running=false;
      clearTimeout(deadlineTimer);
      if(transition===current){transition=null;document.documentElement.removeAttribute('data-dsh-drawer-transition');}
      if(token===generation&&intent!==intents&&shape&&target.isConnected&&desired!==state(desiredKind))move(desiredKind==="right"?rightToggle(desired):toggle(),desired,desiredKind);
    }
  }
  function bind(){
    var next=window.__DSH_MOBILE_COMPAT__&&__DSH_MOBILE_COMPAT__.snapshot();
    if(next&&next.frame&&next.left&&next.left.closest('[data-dsh-mobile-keep-layout]'))next=null;
    if(shape&&next&&shape.frame===next.frame&&shape.left===next.left&&shape.right===next.right)return;
    generation++;if(transition)transition.skipTransition();transition=null;
    if(document.documentElement)document.documentElement.removeAttribute('data-dsh-drawer-transition');
    if(scrim)scrim.remove();scrim=null;
    shape=next&&next.frame&&(next.left||next.right)?next:null;
    if(!shape)return;
    desired=open();
    if(!shape.left)return;
    scrim=document.createElement('div');scrim.className='dsh-drawer-scrim';scrim.setAttribute('aria-hidden','true');
    scrim.addEventListener('click',function(){move(toggle(),false);});shape.frame.append(scrim);
  }
  var style=document.createElement('style');style.id='dsh-drawer-snapshots';style.textContent=`
    .dsh-drawer-scrim{display:none}
    @media(max-width:900px) and (pointer:coarse){
      [data-dsh-mobile-frame]{position:relative!important;grid-template-columns:minmax(0,1fr)!important;transition:none!important;isolation:isolate}
      [data-dsh-mobile-center]{grid-column:1/-1!important;min-width:0!important;width:100%!important}
      [data-dsh-mobile-sidebar]{position:absolute!important;inset:0 auto 0 0!important;width:min(86vw,340px)!important;min-width:0!important;max-width:calc(100vw - 44px)!important;height:100%!important;z-index:50!important;background:#1e2025!important;transform:none!important;transition:none!important;contain:layout;view-transition-name:dsh-left-drawer}
      [data-sidebar-collapsed] [data-dsh-mobile-sidebar],[data-sidebar-collapsed] [data-dsh-mobile-sidebar] *{visibility:hidden!important;pointer-events:none!important}
      .dsh-drawer-scrim{display:block;position:absolute;inset:0;z-index:40;background:rgba(0,0,0,.36);touch-action:none;view-transition-name:dsh-left-shade}
      [data-sidebar-collapsed]>.dsh-drawer-scrim{visibility:hidden;pointer-events:none}
    }
    @media(max-width:1100px) and (pointer:coarse){
      [data-dsh-mobile-right]{view-transition-name:dsh-right-drawer;translate:none!important;transition:none!important}
    }
    html[data-dsh-drawer-transition]::view-transition-group(dsh-right-drawer){animation-duration:280ms;animation-timing-function:cubic-bezier(.2,0,0,1)}
    html[data-dsh-drawer-transition]::view-transition-new(dsh-right-drawer):only-child{animation:dsh-right-enter 280ms cubic-bezier(.2,0,0,1)}
    html[data-dsh-drawer-transition]::view-transition-old(dsh-right-drawer):only-child{animation:dsh-right-exit 220ms cubic-bezier(.3,0,.8,.15)}
    @keyframes dsh-right-enter{from{transform:translateX(100%)}to{transform:translateX(0)}}
    @keyframes dsh-right-exit{from{transform:translateX(0)}to{transform:translateX(100%)}}
    html[data-dsh-drawer-transition]{view-transition-name:none!important}
    html[data-dsh-drawer-transition]::view-transition-group(root){animation:none}
    html[data-dsh-drawer-transition]::view-transition-old(root){display:none}
    html[data-dsh-drawer-transition]::view-transition-new(root){animation:none}
    /* Safety net for a capture that never finishes.
       While a transition is pending this stylesheet hides the old root snapshot and
       names the right rail as its own transition group. If the group never gets a
       new snapshot — a capture that fails after the ready promise resolves, a
       skipped transition, or a frame the WebView drops — the page is left showing
       neither the rail nor the dock fallback that holds its toggle button. Measured
       on the phone: one tap made the entire right side vanish with no way back
       except reopening the app. These rules bound that: any transition group the
       browser did not actually produce stays invisible rather than punching a hole
       in the page, and the old snapshot is only dropped for the capturable layers
       listed below.
       NOTE: this comment lives inside a JS template literal, so it must not contain
       backticks — a stray one closes the template and breaks the whole injected
       script (symptom: "Uncaught SyntaxError: Unexpected identifier"). */
    html[data-dsh-drawer-transition]::view-transition-group(dsh-right-drawer){animation-fill-mode:both}
    html[data-dsh-drawer-transition]::view-transition-old(dsh-right-drawer){animation-fill-mode:both}
    html[data-dsh-drawer-transition]::view-transition-new(dsh-right-drawer){animation-fill-mode:both}
    html[data-dsh-drawer-transition]::view-transition-group(dsh-left-drawer),html[data-dsh-drawer-transition]::view-transition-group(dsh-left-shade){animation-duration:280ms;animation-timing-function:cubic-bezier(.2,0,0,1)}
    html[data-dsh-drawer-transition]::view-transition-new(dsh-left-drawer):only-child{animation:dsh-drawer-enter 280ms cubic-bezier(.2,0,0,1)}
    html[data-dsh-drawer-transition]::view-transition-old(dsh-left-drawer):only-child{animation:dsh-drawer-exit 220ms cubic-bezier(.3,0,.8,.15)}
    html[data-dsh-drawer-transition]::view-transition-new(dsh-left-shade):only-child{animation:dsh-shade-enter 280ms ease}
    html[data-dsh-drawer-transition]::view-transition-old(dsh-left-shade):only-child{animation:dsh-shade-exit 220ms ease}
    @keyframes dsh-drawer-enter{from{transform:translateX(-105%)}to{transform:translateX(0)}}
    @keyframes dsh-drawer-exit{from{transform:translateX(0)}to{transform:translateX(-105%)}}
    @keyframes dsh-shade-enter{from{opacity:0}to{opacity:1}}
    @keyframes dsh-shade-exit{from{opacity:1}to{opacity:0}}
  `;
  function start(){style.media=policy&&!policy.enabled()?'not all':'';if(!style.isConnected)document.documentElement.append(style);bind();}
  if(document.documentElement)start();else document.addEventListener('DOMContentLoaded',start,{once:true});
  document.addEventListener('click',function(event){
    if(bypass||policy&&!policy.enabled()||!shape||!matchMedia('(max-width:1100px) and (pointer:coarse)').matches||!(event.target instanceof Element))return;
    var button=event.target.closest('button');
    if(!button||button.closest('[data-dsh-mobile-keep-layout]'))return;
    if(!shape.frame.contains(button))return;
    var label=button.getAttribute('aria-label');
    if((label==='打开右侧边栏'||label==='收起右侧边栏')&&shape.right){
      if(policy&&!policy.rightKnown(shape.frame,shape.right))return;
      // Phone dock owns its actual open/close event; never swallow or replay it.
      if(mobile.matches)return;
      event.preventDefault();event.stopImmediatePropagation();
      move(button,transition&&desiredKind==='right'?!desired:!rightOpen(),'right');return;
    }
    if(!mobile.matches)return;
    var official=shape.left&&shape.left.contains(button)&&button.matches('[class$="_logoRow"] button[class*="_toggle"]');
    if(!official&&label!=='打开侧边栏'&&label!=='收起侧边栏')return;
    event.preventDefault();event.stopImmediatePropagation();move(button,transition&&desiredKind==="left"?!desired:!open());
  },true);
  document.addEventListener('visibilitychange',function(){if(document.hidden&&transition)transition.skipTransition();});
  mobile.addEventListener('change',function(){if(transition)transition.skipTransition();});
  document.addEventListener('dsh-mobile-capabilities',bind);
  document.addEventListener('dsh-interaction-policy',function(){style.media=policy&&!policy.enabled()?'not all':'';if(policy&&!policy.enabled()){generation++;if(transition)transition.skipTransition();transition=null;document.documentElement.removeAttribute('data-dsh-drawer-transition');}});
  window.__DSH_SNAPSHOT_DRAWER__={samples:samples,closeLeft:function(){
    if(!open()&&!(transition&&desiredKind==='left'))return false;
    move(toggle(),false,'left');return true;
  }};
  bind();
})();
""".trimIndent()
}
