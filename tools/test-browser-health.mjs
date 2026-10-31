import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const probe=fs.readFileSync(new URL('../app/src/main/assets/browser-probe.js',import.meta.url),'utf8');
const health=fs.readFileSync(new URL('../app/src/main/assets/business-health.js',import.meta.url),'utf8');
function fixture(fetch){
 const messages=[],requests=[],timers=new Map();let sequence=0;
 const box={location:{origin:'https://fixture.invalid',href:'https://fixture.invalid/'},URL,AbortController,Date,Set,Map,performance:{now:()=>100},document:{addEventListener(){}},DshNative:{onBridgeMessage(v){messages.push(JSON.parse(v))}},setInterval(){},setTimeout(fn){const id=++sequence;timers.set(id,fn);return id},clearTimeout(id){timers.delete(id)}};
 box.fetch=(...args)=>{requests.push(args);return fetch(...args)};box.window=box;box.top=box;vm.createContext(box);return {box,messages,requests,timers};
}
let f=fixture(async()=>({status:401}));vm.runInContext(probe,f.box);await f.box.__DSH_PROBE_PAGE__('https://fixture.invalid','one');
assert.equal(f.messages.at(-1).payload.status,401);assert.equal(f.requests.length,1);assert.equal(f.requests[0][0],'https://fixture.invalid/');assert.equal(f.requests[0][1].method,'HEAD');assert.equal(f.requests[0][1].credentials,'same-origin');assert.equal(f.timers.size,0);
f=fixture(async()=>({status:200}));vm.runInContext(probe,f.box);await f.box.__DSH_PROBE_PAGE__('https://other.invalid','two');assert.equal(f.requests.length,0);assert.equal(f.messages.at(-1).payload.error,'context-changed');
f=fixture((url,init)=>new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>reject(Object.assign(new Error(),{name:'AbortError'})))));vm.runInContext(probe,f.box);let pending=f.box.__DSH_PROBE_PAGE__('https://fixture.invalid','three');for(const timer of f.timers.values())timer();await pending;assert.equal(f.messages.at(-1).payload.error,'timeout');assert.equal(f.requests.length,1);
for(const [path,status,samples,restricted] of [['/remote/api/changes.summary',404,0,1],['/api/changes.summary',405,0,1],['/remote/api/workspaces',404,1,0],['/remote/api/changes.summary',500,1,0],['/remote/api/changes.summary',403,1,0]]){
 f=fixture(async()=>({status,ok:false}));vm.runInContext(health,f.box);await f.box.fetch(path);const s=f.box.__DSH_BUSINESS_HEALTH__.snapshot();assert.equal(s.sampleCount,samples);assert.equal(s.restrictedPlugins,restricted);assert.equal(s.failurePercent,samples?100:-1);
}
console.log('PASS browser health uses one same-origin HEAD, reports authentication response, cancels on timeout and separates optional missing APIs from core failure');

{
 const handlers={},messages=[];let observe,mutate;
 const box={URL,AbortController,Set,Map,performance:{now:()=>100,getEntriesByType:()=>[{name:'https://fixture.invalid/assets/main.js',startTime:1,encodedBodySize:2048,responseStatus:200}]},
   document:{addEventListener(type,fn){handlers[type]=fn;}},location:{origin:'https://fixture.invalid',href:'https://fixture.invalid/'},
   DshNative:{onBridgeMessage(raw){messages.push(JSON.parse(raw));}},setInterval(){},setTimeout(){return 1;},clearTimeout(){},
   PerformanceObserver:class{constructor(fn){observe=fn;}observe(){}},MutationObserver:class{constructor(fn){mutate=fn;}observe(){}}};
 box.window=box;box.top=box;vm.createContext(box);vm.runInContext(health,box);
 const entry={name:'https://fixture.invalid/assets/main.js',startTime:1,encodedBodySize:2048,responseStatus:200};
 observe({getEntries:()=>[entry]}); // buffered duplicate must not double count.
 const node={tagName:'SCRIPT',src:'https://fixture.invalid/assets/second.js'};
 mutate([{addedNodes:[node]}]);assert.equal(box.__DSH_BUSINESS_HEALTH__.snapshot().loadingResources,1);
 handlers.load({target:node});observe({getEntries:()=>[{name:node.src,startTime:2,encodedBodySize:4096,responseStatus:200}]});
 let snapshot=box.__DSH_BUSINESS_HEALTH__.snapshot();assert.equal(snapshot.completedResources,2);assert.equal(snapshot.resourceBytes,6144);assert.equal(snapshot.loadingResources,0);
 const failed={tagName:'LINK',rel:'stylesheet',href:'https://fixture.invalid/assets/missing.css'};
 mutate([{addedNodes:[failed]}]);handlers.error({target:failed});snapshot=box.__DSH_BUSINESS_HEALTH__.snapshot();assert.equal(snapshot.resourceFailures,1);assert.equal(snapshot.loadingResources,0);
 observe({getEntries:()=>[{name:'https://elsewhere.invalid/foreign.js',startTime:3,encodedBodySize:999999,responseStatus:200}]});assert.equal(box.__DSH_BUSINESS_HEALTH__.snapshot().resourceBytes,6144);
 assert.equal(messages.length>0,true);
 console.log('PASS resource progress combines browser timing and DOM events without duplicate byte counts or extra downloads');
}
