import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {canRecoverLanding,recoveryScript,unavailableLanding} from './unavailable.mjs';

test('local startup recovery never replays credentials, writes, or arbitrary routes',()=>{
  assert.equal(canRecoverLanding('GET','/pair-app'),true);assert.equal(canRecoverLanding('GET','/'),true);
  for(const url of ['/pair-accept?pair=private','/pair-app?grant=private','/pair-app?other=1','/api/task','/plugins/main.js'])assert.equal(canRecoverLanding('GET',url),false);
  assert.equal(canRecoverLanding('POST','/pair-app'),false);
  assert.match(unavailableLanding().headers['content-security-policy'],/script-src 'sha256-/);
});
test('error document waits for actual pairing protocol then resumes bare landing once',async()=>{
  const scheduled=[],navigated=[];let stored='0',calls=0;
  const context={AbortController,sessionStorage:{getItem:()=>stored,setItem:(_,s)=>stored=s},
    setTimeout:(fn,ms)=>{if(ms!==4000)scheduled.push(fn);return 1;},clearTimeout:()=>{},
    fetch:async()=>{calls++;return {ok:true,json:async()=>calls===1?{ok:true}:{ok:true,paired:true,phase:'connected'}};},
    location:{replace:url=>navigated.push(url)}};
  vm.runInNewContext(recoveryScript,context);
  scheduled.shift()();await new Promise(r=>setImmediate(r));assert.deepEqual(navigated,[]);
  scheduled.shift()();await new Promise(r=>setImmediate(r));assert.deepEqual(navigated,['/pair-app']);assert.equal(JSON.parse(stored).count,1);
  stored=JSON.stringify({count:3,until:Date.now()+120000});vm.runInNewContext(recoveryScript,context);assert.equal(scheduled.length,0);
  stored=JSON.stringify({count:3,until:Date.now()-1});vm.runInNewContext(recoveryScript,context);assert.equal(scheduled.length,1);
});
