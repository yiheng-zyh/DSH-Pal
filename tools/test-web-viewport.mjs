import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/ui/web/WebViewport.kt',import.meta.url),'utf8').match(/fun script\(\) = """([\s\S]*?)"""/)[1];
function page(mode,width,large,multi=false){
 const metas=[{name:'viewport',content:'width=1280'},{name:'viewport',content:'width=device-width, initial-scale=1'}];
 const attrs={},listeners=[],observers=[];
 const document={documentElement:{setAttribute:(k,v)=>attrs[k]=v,appendChild:x=>metas.push(x)},head:{appendChild:x=>metas.push(x)},querySelectorAll:()=>metas,createElement:()=>({}),addEventListener:()=>{}};
 const state={mode,width,large,multi};
 const env={document,screen:{width:390},DshFiles:{webLayoutMode:()=>state.mode,windowWidth:()=>state.width,largeWindow:()=>state.large,multiWindow:()=>state.multi},MutationObserver:class{constructor(cb){this.cb=cb;observers.push(this)}observe(){}disconnect(){}},requestAnimationFrame:cb=>cb(),queueMicrotask:cb=>cb(),setInterval:()=>1,clearInterval:()=>{},Event:class{},window:{addEventListener:(...x)=>listeners.push(x),dispatchEvent:()=>{}},console};
 env.window.top=env.window;vm.createContext(env);vm.runInContext(source,env);
 return {env,metas,attrs,state,observers,listeners};
}
const p=page('DESKTOP',720,true);const original=p.metas.slice();assert.deepEqual(p.metas.map(x=>x.content),['width=1024','width=1024']);assert.equal(p.attrs['data-dsh-web-layout'],'desktop');
p.state.mode='AUTO';p.env.window.__DSH_VIEWPORT__.apply();assert.ok(p.metas.every(x=>x.content==='width=1024'));p.state.width=1152;p.env.window.__DSH_VIEWPORT__.apply();assert.ok(p.metas.every(x=>x.content==='width=1152'));
p.state.mode='PHONE';p.env.window.__DSH_VIEWPORT__.apply();assert.ok(p.metas.every(x=>x.content==='width=900'));assert.equal(p.attrs['data-dsh-web-layout'],'phone');p.metas.forEach((node,i)=>assert.equal(node,original[i]));
const count=p.observers.length;vm.runInContext(source,p.env);assert.equal(p.observers.length,count);assert.equal(p.listeners.length,1);
const phone=page('AUTO',834,false);assert.ok(phone.metas.every(x=>x.content==='width=834'));assert.equal(phone.attrs['data-dsh-web-layout'],'phone');
phone.metas.push({name:'viewport',content:'width=device-width, initial-scale=1'});phone.observers[0].cb();assert.ok(phone.metas.every(x=>x.content==='width=834'));
const modes=[];p.env.window.__dshRemoteAdapt={setEnabled:value=>modes.push(value)};p.state.mode='DESKTOP';p.env.window.__DSH_VIEWPORT__.apply();p.env.window.__DSH_VIEWPORT__.apply();p.env.window.__dshRemoteAdapt.setEnabled(true);p.state.mode='PHONE';p.env.window.__DSH_VIEWPORT__.apply();assert.deepEqual(modes,[false,false,true]);
console.log('PASS duplicate and late viewports, tablet portrait/landscape, phone mode, idempotent updates, original nodes retained');
const split=page('DESKTOP',571,false,true);
assert.equal(split.attrs['data-dsh-web-layout'],'phone');assert.ok(split.metas.every(x=>x.content==='width=571'));
split.state.width=600;split.env.window.__DSH_VIEWPORT__.apply();assert.equal(split.attrs['data-dsh-web-layout'],'desktop');
split.state.width=571;split.state.multi=false;split.env.window.__DSH_VIEWPORT__.apply();assert.equal(split.attrs['data-dsh-web-layout'],'desktop');assert.ok(split.metas.every(x=>x.content==='width=1280'));
split.state.width=1152;split.env.window.__DSH_VIEWPORT__.apply();assert.ok(split.metas.every(x=>x.content==='width=1152'));assert.equal(split.state.mode,'DESKTOP');
console.log('PASS narrow multi-window reflow, 600dp boundary, fullscreen desktop retained, expansion restores preference');
