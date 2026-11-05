import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const script=fs.readFileSync(new URL('../../app/src/main/assets/ego-stream-budget.js',import.meta.url),'utf8');
test('ego probe requests events only; frame consumers and unrelated EventSources retain full transport',async()=>{
 const sources=[];
 class Source extends EventTarget {
   static CONNECTING=0;static OPEN=1;static CLOSED=2;
   constructor(url,options){super();this.url=String(url);this.options=options;this.readyState=0;sources.push(this);}
   close(){this.readyState=2;}
 }
 const context={EventTarget,Event,MessageEvent,URL,EventSource:Source,location:{href:'https://fixture.test/pair-app',origin:'https://fixture.test'},setTimeout,clearTimeout};
 context.window=context;context.top=context;vm.runInNewContext(script,context);
 const probe=new context.EventSource('/remote/api/ego/stream');let calls=0;probe.addEventListener('tool-call',()=>calls++);
 await new Promise(r=>setTimeout(r,5));assert.equal(new URL(sources[0].url).searchParams.get('dsh_events_only'),'1');
 sources[0].dispatchEvent(new MessageEvent('tool-call',{data:'{}'}));assert.equal(calls,1);
 const viewer=new context.EventSource('/remote/api/ego/stream');viewer.addEventListener('frame',()=>{});
 await new Promise(r=>setTimeout(r,5));assert.equal(new URL(sources[1].url).searchParams.has('dsh_events_only'),false);
 const unrelated=new context.EventSource('/notifications');assert.ok(unrelated instanceof Source);
 probe.addEventListener('frame',()=>{});assert.equal(sources[0].readyState,2);
 assert.equal(new URL(sources.at(-1).url).searchParams.has('dsh_events_only'),false);
 probe.close();viewer.close();unrelated.close();
 const pending=new context.EventSource('/remote/api/ego/stream');pending.close();const count=sources.length;
 await new Promise(r=>setTimeout(r,5));assert.equal(sources.length,count);
});
