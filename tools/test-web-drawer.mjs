import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebDrawerMotion.kt',import.meta.url),'utf8').split('"""')[1];
const wait=ms=>new Promise(r=>setTimeout(r,ms));
function harness({supported=true,reduced=false,left=true,right=false,fail=false,syncFail=false}={}) {
  const listeners=new Map(),idle=[];let clicks=0,captures=0,updating=false;
  class Element {
    constructor(label=''){this.attrs=new Map();this.label=label;this.isConnected=true;}
    setAttribute(k,v){this.attrs.set(k,v)} removeAttribute(k){this.attrs.delete(k)} hasAttribute(k){return this.attrs.has(k)}
    getAttribute(k){return k==='aria-label'?this.label:this.attrs.get(k)}
    append(){} remove(){this.isConnected=false} addEventListener(){} closest(s){return s==='button'?this:null}
    matches(){return false} contains(n){return n===button} querySelector(){return button}
    click(){clicks++;frame.hasAttribute('data-sidebar-collapsed')?frame.removeAttribute('data-sidebar-collapsed'):frame.setAttribute('data-sidebar-collapsed','')}
  }
  const frame=new Element(),side=new Element(),button=new Element('打开侧边栏');frame.setAttribute('data-sidebar-collapsed','');
  const doc={hidden:false,documentElement:new Element(),createElement:()=>new Element(),addEventListener:(n,f)=>{listeners.set(n,[...(listeners.get(n)||[]),f])}};
  if(supported)doc.startViewTransition=callback=>{
    captures++;if(syncFail)throw Error("synchronous API failure");updating=true;
    const ready=Promise.resolve().then(callback).then(()=>{updating=false;if(fail)throw new Error('capture rejected')});
    const finished=ready.then(()=>wait(20));finished.catch(()=>{});
    return {ready,finished,skipTransition(){}};
  };
  const shape={frame,left:left?side:null,right:right?new Element():null};
  const ctx={document:doc,CSS:{supports:()=>supported},Element,performance,setTimeout,clearTimeout,matchMedia:q=>({matches:q.includes('reduced')?reduced:true,addEventListener(){}}),requestAnimationFrame:()=>{assert.equal(updating,false,'rAF cannot settle an update callback: rendering is suspended');return 1}};
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
  const h=harness();h.click();await wait(60);assert.equal(h.clicks,1);assert.equal(h.captures,1);assert.equal(h.frame.hasAttribute('data-sidebar-collapsed'),false);assert.equal(h.ctx.__DSH_SNAPSHOT_DRAWER__.samples.length,1);
}
{
  const h=harness();h.click();h.click();await wait(100);assert.equal(h.clicks,2,'a quick second toggle must settle to closed');assert.equal(h.frame.hasAttribute('data-sidebar-collapsed'),true);
}
{
  const h=harness();h.click();h.frame.removeAttribute('data-sidebar-collapsed');await wait(60);assert.equal(h.clicks,0,'an upstream state commit before capture must not toggle the drawer back');assert.equal(h.frame.hasAttribute('data-sidebar-collapsed'),false);
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
 const h=harness({right:true});h.click('打开右侧边栏');await wait(10);assert.equal(h.captures,0,'phone right control must reach original plugin without snapshot interception');assert.equal(h.clicks,0,'app must not synthesize duplicate native clicks');
}
console.log('PASS drawer commit ordering, rapid reversal, capture failure, reduced motion, missing sidebar and API fallback');
