import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebDrawerMotion.kt',import.meta.url),'utf8').split('"""')[1];
const wait=ms=>new Promise(r=>setTimeout(r,ms));
function harness({supported=true,reduced=false,left=true,right=true,fail=false,syncFail=false,phone=false}={}) {
  const listeners=new Map(),idle=[];let clicks=0,captures=0,updating=false;
  class Element {
    constructor(label=''){this.attrs=new Map();this.label=label;this.isConnected=true;}
    setAttribute(k,v){this.attrs.set(k,v)} removeAttribute(k){this.attrs.delete(k)} hasAttribute(k){return this.attrs.has(k)}
    getAttribute(k){return k==='aria-label'?this.label:this.attrs.get(k)}
    append(){} remove(){this.isConnected=false} addEventListener(){} closest(s){return s==='button'?this:null}
    matches(){return false} contains(n){return n===button} querySelector(){return button}
    click(){clicks++;frame.hasAttribute('data-dsh-mobile-right-open')?frame.removeAttribute('data-dsh-mobile-right-open'):frame.setAttribute('data-dsh-mobile-right-open','')}
  }
  const frame=new Element(),side=new Element(),button=new Element('打开右侧边栏');
  const doc={hidden:false,documentElement:new Element(),createElement:()=>new Element(),addEventListener:(n,f)=>{listeners.set(n,[...(listeners.get(n)||[]),f])}};
  if(supported)doc.startViewTransition=callback=>{
    captures++;if(syncFail)throw Error("synchronous API failure");updating=true;
    const ready=Promise.resolve().then(callback).then(()=>{updating=false;if(fail)throw new Error('capture rejected')});
    const finished=ready.then(()=>wait(20));finished.catch(()=>{});
    return {ready,finished,skipTransition(){}};
  };
  const shape={frame,left:left?side:null,right:right?new Element():null};
  const ctx={document:doc,CSS:{supports:()=>supported},Element,performance,setTimeout,clearTimeout,matchMedia:q=>({matches:q.includes('reduced')?reduced:q.includes('max-width:900px')?phone:true,addEventListener(){}}),requestAnimationFrame:()=>{assert.equal(updating,false,'rAF cannot settle an update callback: rendering is suspended');return 1}};
  ctx.window=ctx;ctx.top=ctx;ctx.requestIdleCallback=callback=>idle.push(callback);ctx.__DSH_MOBILE_COMPAT__={snapshot:()=>shape};vm.runInNewContext(source,ctx);
  function click(label){if(label)button.label=label;const e={target:button,preventDefault(){},stopImmediatePropagation(){}};for(const f of listeners.get('click')||[])f(e)}
  return {ctx,frame,click,removeShell:()=>{ctx.__DSH_MOBILE_COMPAT__.snapshot=()=>null;for(const f of listeners.get('dsh-mobile-capabilities')||[])f()},runIdle:()=>idle.forEach(f=>f()),get clicks(){return clicks},get captures(){return captures}};
}
{
  const h=harness({supported:false});assert.equal(h.ctx.__DSH_SNAPSHOT_DRAWER__,undefined);
}
{
  const h=harness({left:false,right:true});assert.ok(h.ctx.__DSH_SNAPSHOT_DRAWER__,'right-only shells must retain their controller');
}
{
  const h=harness();h.click();await wait(60);assert.equal(h.clicks,1);assert.equal(h.captures,1);assert.equal(h.frame.hasAttribute('data-dsh-mobile-right-open'),true);assert.equal(h.ctx.__DSH_SNAPSHOT_DRAWER__.samples.length,1);
}
{
  const h=harness();h.click();h.click();await wait(100);assert.equal(h.clicks,2,'a quick second toggle must settle to closed');assert.equal(h.frame.hasAttribute('data-dsh-mobile-right-open'),false);
}
{
  const h=harness();h.click();h.frame.setAttribute('data-dsh-mobile-right-open','');await wait(60);assert.equal(h.clicks,0,'an upstream state commit before capture must not toggle the drawer back');assert.equal(h.frame.hasAttribute('data-dsh-mobile-right-open'),true);
}
{
  const h=harness({fail:true});h.click();await wait(80);assert.equal(h.clicks,1,'capture failure must not re-toggle or retry forever');assert.equal(h.captures,1);
}
{
  const h=harness({syncFail:true});h.click();await wait(30);assert.equal(h.clicks,1);assert.equal(h.captures,1);
}
{
  const h=harness();h.click();h.removeShell();await wait(50);assert.equal(h.ctx.document.documentElement.hasAttribute('data-dsh-drawer-transition'),false,'unmount must release root capture styling');assert.equal(h.clicks,0,'stale callbacks must not navigate a replacement page');
}
{
  const h=harness({reduced:true});h.click();await wait(10);assert.equal(h.clicks,1);assert.equal(h.captures,0);
}
{
 const h=harness({phone:true});h.click('打开右侧边栏');await wait(10);assert.equal(h.ctx.__DSH_SNAPSHOT_DRAWER__,undefined,'phone must use compositor controller');assert.equal(h.captures,0,'phone right control must reach original plugin without snapshot interception');assert.equal(h.clicks,0,'app must not synthesize duplicate native clicks');
}
console.log('PASS drawer commit ordering, rapid reversal, capture failure, reduced motion, missing sidebar and API fallback');

// Exercise the phone controller against actual click propagation and animation
// completion/cancellation. A new-session icon must never become a drawer toggle.
const phoneSource=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebMotionScript.kt',import.meta.url),'utf8').split('"""')[1];
function phoneHarness({collapsed=false,idle=false,reduced=false}={}){
 const listeners=new Map(),frames=[],animations=[],idleJobs=[];let nativeClicks=0,intercepted=0;
 class Node {
  constructor(kind=''){this.kind=kind;this.attrs=new Map();this.isConnected=true;this.style={getPropertyValue:()=>'',getPropertyPriority:()=>'',setProperty(){},removeProperty(){}};}
  setAttribute(k,v){this.attrs.set(k,v)}removeAttribute(k){this.attrs.delete(k)}hasAttribute(k){return this.attrs.has(k)}
  matches(s){return this.kind==='toggle'&&s.includes('_toggle')}
  closest(s){return s.includes('_toggle')?(this.kind==='toggle'?this:null):s==='button[aria-label]'?this:null}
  getAttribute(k){return k==='aria-label'?(this.kind==='toggle'?'收起侧边栏':'新建会话'):null}
  contains(n){return n===toggle||n===newSession}querySelector(){return toggle}querySelectorAll(){return [toggle,newSession]}
  appendChild(){}remove(){}addEventListener(){}getBoundingClientRect(){return {width:374,left:0}}
  click(){nativeClicks++;if(frame.hasAttribute('data-sidebar-collapsed'))frame.removeAttribute('data-sidebar-collapsed');else frame.setAttribute('data-sidebar-collapsed','')}
  animate(){let resolve,reject;const finished=new Promise((r,j)=>{resolve=r;reject=j});finished.catch(()=>{});const a={finished,finish:resolve,cancel:()=>reject(Error('cancelled'))};animations.push(a);return a;}
 }
 const frame=new Node(),side=new Node(),center=new Node(),toggle=new Node('toggle'),newSession=new Node('new');
 if(collapsed)frame.setAttribute('data-sidebar-collapsed','');
 const document={documentElement:new Node(),createElement:()=>new Node(),addEventListener:(n,f)=>listeners.set(n,f)};
 const context={document,Element:Node,performance,matchMedia:q=>({matches:q.includes('reduced')?reduced:true,addEventListener(){}}),getComputedStyle:()=>({transform:'none'}),MutationObserver:class{observe(){}disconnect(){}},requestAnimationFrame:f=>frames.push(f)};
 context.window=context;context.top=context;context.__DSH_MOBILE_COMPAT__={snapshot:()=>({frame,left:side,center})};
 if(idle)context.requestIdleCallback=f=>idleJobs.push(f);
 vm.runInNewContext(phoneSource,context);
 function click(target,trusted=true){listeners.get('click')({target,isTrusted:trusted,preventDefault(){intercepted++},stopImmediatePropagation(){}})}
 return {click,toggle,newSession,animations,frame,frames,idleJobs,api:context.__DSH_MOBILE_MOTION__,side,get nativeClicks(){return nativeClicks},get intercepted(){return intercepted}};
}
{
 const h=phoneHarness();h.click(h.newSession);assert.equal(h.intercepted,0,'new-session action must reach its owner');
 h.click(h.toggle);assert.equal(h.intercepted,1);assert.equal(h.nativeClicks,0,'keep content until the close slide finishes');
 h.animations[0].finish();await wait(0);assert.equal(h.nativeClicks,0);assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),true);
 h.click(h.toggle,false);assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),true,'delayed adapter collapse must not reopen');
 h.click(h.toggle);assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),false,'reopen retained content');assert.equal(h.nativeClicks,0);
}
{
 const h=phoneHarness();h.click(h.toggle);h.click(h.toggle);await wait(0);
 assert.equal(h.nativeClicks,0,'a cancelled close must not collapse upstream state');
 assert.equal(h.frame.hasAttribute('data-sidebar-collapsed'),false);
}
{
 const h=phoneHarness();h.api.closeLeft();h.api.closeLeft();h.animations[0].finish();await wait(0);
 assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),true,'repeated navigation close requests must be idempotent');
}
console.log('PASS phone compositor: semantic toggle, close ordering and cancellation without stale navigation');
{
 const h=phoneHarness({collapsed:true,idle:true});assert.equal(h.idleJobs.length,1);
 h.idleJobs[0]();assert.equal(h.nativeClicks,1);assert.equal(h.frame.hasAttribute('data-sidebar-collapsed'),false);
 assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),true,'warm content remains off screen');assert.equal(h.side.inert,true);
 h.api.openLeft();assert.equal(h.nativeClicks,1,'opening warmed content must not toggle the plugin again');assert.equal(h.api.isOpen(),true);assert.equal(h.side.inert,false);
}
{
 const h=phoneHarness({reduced:true});h.click(h.toggle);assert.equal(h.animations.length,0);assert.equal(h.nativeClicks,0);assert.equal(h.frame.hasAttribute('data-dsh-left-closed'),true);
}
console.log('PASS idle prewarming, retained reopening and reduced-motion local close');

const navigationSource=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebSidebarNavigation.kt',import.meta.url),'utf8').split('"""')[1];
{
 const events=new Map(),attrs=new Set();let closes=0,nativeToggles=0,closing=false;
 class NavElement {
  getAttribute(k){return k==='role'?'treeitem':null}hasAttribute(){return false}
  matches(){return false}
  closest(s){return s.includes('button,a,')||s.includes('_sessionRow')?this:null}
 }
 const row=new NavElement(),left={contains:n=>n===row,querySelector:()=>null};
 const frame={hasAttribute:k=>attrs.has(k)};
 const ctx={Element:NavElement,performance,setTimeout:f=>f(),matchMedia:()=>({matches:true}),document:{addEventListener(){}},addEventListener:(n,f)=>events.set(n,f)};
 ctx.window=ctx;ctx.top=ctx;ctx.__DSH_MOBILE_COMPAT__={snapshot:()=>({frame,left})};
 ctx.__DSH_MOBILE_MOTION__={closeLeft:()=>{closes++;return true},isClosing:()=>closing};
 ctx.__dshRemoteAdapt={toggleSidebar:()=>nativeToggles++};
 vm.runInNewContext(navigationSource,ctx);
 events.get('click')({target:row,isTrusted:true});assert.equal(closes,1,'treeitem conversation navigation must close the local drawer');
 attrs.add('data-dsh-left-closed');ctx.__dshRemoteAdapt.toggleSidebar();assert.equal(nativeToggles,0);
 attrs.clear();closing=true;ctx.__dshRemoteAdapt.toggleSidebar();assert.equal(nativeToggles,0,'delayed adapter must not destroy retained content during close');
 closing=false;ctx.__dshRemoteAdapt.toggleSidebar();assert.equal(nativeToggles,1,'unrelated layout calls retain their owner');
}
console.log('PASS conversation tree navigation and delayed adapter collapse ownership');
