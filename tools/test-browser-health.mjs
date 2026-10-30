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
