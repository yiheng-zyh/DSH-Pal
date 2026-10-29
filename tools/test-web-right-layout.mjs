import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
const raw=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebLayoutScript.kt',import.meta.url),'utf8');
const script=raw.match(/private val controller = """([\s\S]*?)"""/)[1];
let mobile=true,callback;
class Style {
 constructor(){this.values=new Map()}
 getPropertyValue(k){return this.values.get(k)?.[0]||''}
 getPropertyPriority(k){return this.values.get(k)?.[1]||''}
 setProperty(k,v,p=''){this.values.set(k,[v,p])}
 removeProperty(k){this.values.delete(k)}
}
class Element {
 constructor(){this.style=new Style();this.attrs=new Map();this.isConnected=true}
 setAttribute(k,v){this.attrs.set(k,v)} getAttribute(k){return this.attrs.get(k)??null}
 hasAttribute(k){return this.attrs.has(k)} removeAttribute(k){this.attrs.delete(k)}
 toggleAttribute(k,on){on?this.attrs.set(k,''):this.attrs.delete(k)}
 matches(){return false} querySelector(){return null} querySelectorAll(){return []} appendChild(){} addEventListener(){}
 getBoundingClientRect(){return {width:375}}
}
const frame=new Element(),rail=new Element(),pane=new Element(),body=new Element(),host=new Element();
frame.setAttribute('data-rightbar-collapsed','true');pane.setAttribute('data-sidebar-right-open','true');
rail.style.setProperty('display','flex');host.style.setProperty('transform','translateX(375px)');
let mounted=true;
frame.querySelector=s=>s.includes('data-dsh-mobile-right')?rail:null;
rail.querySelector=s=>s.includes('data-sidebar-right-open')?(mounted?pane:null):s.includes('data-sidebar-right-panel')?pane:s.includes('_emptyTabHost')?host:null;
rail.querySelectorAll=s=>s.includes('_tabBody')?[body]:[];
const listeners=new Map();
const document={documentElement:new Element(),querySelector:s=>s==='[data-dsh-mobile-frame]'?frame:null,createElement:()=>new Element(),addEventListener:(e,fn)=>listeners.set(e,fn)};
const ctx={document,Element,matchMedia:q=>({matches:q.includes('max-width:900px')?mobile:q.includes('reduced')?false:mobile}),MutationObserver:class {constructor(fn){this.fn=fn}observe(n){if(n===frame)callback=this.fn}disconnect(){}},getComputedStyle:()=>({display:'block'}),requestAnimationFrame:()=>{},setTimeout:()=>{},setInterval:()=>{}};
ctx.window=ctx;ctx.top=ctx;ctx.addEventListener=(e,fn)=>listeners.set(e,fn);ctx.__DSH_SNAPSHOT_DRAWER__={};
vm.runInNewContext('('+script+') ("")',ctx);
assert.equal(frame.hasAttribute('data-dsh-mobile-right-open'),true,'declared open overrides stale collapsed flag');
assert.equal(rail.style.getPropertyValue('position'),'absolute','open geometry must apply immediately without timer');
assert.equal(body.style.getPropertyValue('overflow-y'),'auto','real inner clipping body must scroll');
assert.equal(host.style.getPropertyValue('transform'),'none');
mounted=false;callback();assert.equal(rail.style.getPropertyValue('display'),'none','closed descendants cannot swallow chat');assert.equal(body.style.getPropertyValue('overflow-y'),'');
mounted=true;callback();assert.equal(rail.style.getPropertyValue('display'),'block','reopen restores surface');
mobile=false;listeners.get('resize')();assert.equal(rail.style.getPropertyValue('display'),'flex','tablet keeps original inline styles');assert.equal(rail.style.getPropertyValue('position'),'');assert.equal(host.style.getPropertyValue('transform'),'translateX(375px)');
console.log('PASS real layout controller: stale flags, immediate geometry, inner scroll, close/reopen, tablet style restoration');


const scroll=new Element(),region=new Element(),tree=new Element(),row=new Element();scroll.children=[region,row];
const previousQuery=frame.querySelector;
frame.querySelector=q=>q.includes('sidebar-scroll')?scroll:previousQuery(q);
scroll.querySelector=q=>q.includes('workspace')?region:null;region.querySelectorAll=()=>[tree];
scroll.style.setProperty('overflow-y','visible');region.style.setProperty('height','25px');
mobile=true;callback();
assert.equal(scroll.style.getPropertyValue('overflow-y'),'auto','phone global rail must scroll');
assert.equal(region.style.getPropertyValue('min-height'),'240px','workspace must not be squeezed');
assert.equal(tree.style.getPropertyValue('overscroll-behavior-y'),'auto','tree boundary may chain to rail');
frame.setAttribute('data-sidebar-collapsed','');callback();
assert.equal(scroll.style.getPropertyValue('overflow-y'),'visible','collapse restores original layout');
assert.equal(region.style.getPropertyValue('height'),'25px');
frame.removeAttribute('data-sidebar-collapsed');mobile=false;
ctx.matchMedia=q=>({matches:!q.includes('max-width:900px')&&!q.includes('reduced')});callback();
assert.equal(scroll.style.getPropertyValue('overflow-y'),'auto','tablet retains nested scrolling');
scroll.querySelector=()=>null;callback();assert.equal(region.style.getPropertyValue('height'),'25px','plugin removal releases reserved workspace');
console.log('PASS left rail: phone/tablet nested scroll contract, boundary chaining, collapse and plugin removal restore originals');
