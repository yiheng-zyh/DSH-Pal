import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const source=readFileSync(new URL('../app/src/main/assets/business-health.js',import.meta.url),'utf8');
function harness() {
  let now=100,sequence=0,handler,reported=[],observe;
  const timers=new Map(),listeners={};let mutation;
  const context={URL,Set,performance:{now:()=>now},location:{href:'https://example.test/',origin:'https://example.test'},
    document:{hidden:false,addEventListener:(name,fn)=>listeners[name]=fn},
    setTimeout:(fn,ms)=>{timers.set(++sequence,{fn,at:now+ms});return sequence;},clearTimeout:id=>timers.delete(id),setInterval:()=>0,
    MutationObserver:class {constructor(callback){mutation=callback;}observe(){}},
    PerformanceObserver:class {constructor(callback){observe=callback;}observe(){}},
    DshNative:{onBridgeMessage:raw=>reported.push(JSON.parse(raw))},fetch:(...args)=>handler(...args)};
  context.window=context;context.top=context;vm.runInNewContext(source,context);
  return {context,listeners,reported,addScript(node){mutation([{addedNodes:[node]}]);},set handler(value){handler=value;},
    tick(ms){now+=ms;for(const [id,timer] of [...timers])if(timer.at<=now){timers.delete(id);timer.fn();}},
    timings(entries){observe({getEntries:()=>entries});},
    health:()=>context.__DSH_BUSINESS_HEALTH__.snapshot()};
}
const reply=(status=200,body={ok:true})=>({status,ok:status>=200&&status<300,json:()=>Promise.resolve(body)});
let cases=0;
{
  const h=harness();h.handler=()=>Promise.resolve(reply(403));
  await h.context.fetch('/plugins/example/state');assert.equal(h.health().restrictedPlugins,1);assert.equal(h.health().resourceFailures,0);assert.equal(h.health().sampleCount,0);cases++;
  await h.context.fetch('/plugins/example/state?sessionId=fixture');assert.equal(h.health().restrictedPlugins,1);cases++;
  h.handler=()=>Promise.resolve(reply());await h.context.fetch('/plugins/example/state');assert.equal(h.health().restrictedPlugins,0);cases++;
  h.handler=()=>Promise.resolve(reply(403));await h.context.fetch('/plugins/??example/client.js&rev=42');assert.equal(h.health().resourceFailures,1);cases++;
}
{
  const h=harness();const script={tagName:'SCRIPT',src:'https://example.test/plugins/??example/client.js&rev=42'};
  h.addScript(script);h.tick(9000);assert.equal(h.health().loadingResources,1);assert.equal(h.health().oldestResourceMs,9000);cases++;
  h.listeners.load({target:script});assert.equal(h.health().loadingResources,0);cases++;
  h.addScript(script);h.listeners.error({target:script});assert.equal(h.health().loadingResources,0);assert.equal(h.health().resourceFailures,1);cases++;
}
{
  const h=harness();const expected=reply();h.handler=()=>Promise.resolve(expected);
  const actual=await h.context.fetch('/api/sessions');assert.equal(actual,expected);assert.deepEqual(await actual.json(),{ok:true});
  assert.equal(h.health().failurePercent,0);cases++;
  h.handler=()=>Promise.reject(new TypeError('offline'));
  await assert.rejects(h.context.fetch('/api/sessions'));assert.ok(h.health().failurePercent>0);cases++;
  h.handler=()=>Promise.resolve(reply(503));await h.context.fetch('/api/sessions');assert.ok(h.health().failurePercent>=50);cases++;
  assert.ok(!JSON.stringify(h.reported).includes('/api/'));cases++;
}
{
  const h=harness();let resolve;h.handler=()=>new Promise(done=>resolve=done);
  const pending=h.context.fetch('/remote/api/credentials');h.tick(8001);assert.equal(h.health().slowPending,1);cases++;
  resolve(reply());await pending;assert.equal(h.health().slowPending,0);assert.equal(h.health().failurePercent,0);assert.ok(h.health().lastLatencyMs>=8000);cases++;
}
{
  const h=harness();let resolve;h.handler=()=>Promise.resolve({...reply(),json:()=>new Promise(done=>resolve=done)});
  const response=await h.context.fetch('/api/sessions');const pending=response.json();h.tick(8001);
  assert.equal(h.health().slowPending,1);cases++;
  resolve({sessions:[]});assert.deepEqual(await pending,{sessions:[]});assert.equal(h.health().slowPending,0);cases++;
}
{
  const h=harness();h.handler=()=>Promise.reject(Object.assign(new Error('cancelled'),{name:'AbortError'}));
  await assert.rejects(h.context.fetch('/api/sessions'));assert.equal(h.health().sampleCount,0);cases++;
  h.handler=()=>Promise.reject(new Error('failed'));await assert.rejects(h.context.fetch('/plugins/test/index.js'));
  assert.equal(h.health().resourceFailures,1);h.handler=()=>Promise.resolve(reply());await h.context.fetch('/plugins/test/index.js');
  assert.equal(h.health().resourceFailures,0);cases++;
}
{
  const h=harness();h.handler=()=>Promise.resolve(reply(503));
  await h.context.fetch('https://elsewhere.test/api/data');await h.context.fetch('/api/events');
  assert.equal(h.health().sampleCount,0);cases++;
  let resolve;h.handler=()=>new Promise(done=>resolve=done);
  const pending=h.context.fetch('/api/message',{method:'POST'});assert.equal(h.context.__DSH_PENDING_WRITES__(),true);
  resolve(reply());await pending;assert.equal(h.context.__DSH_PENDING_WRITES__(),false);cases++;
}
{
  const h=harness();
  const entry={name:'https://example.test/plugins/??example/client.js&rev=42',responseStatus:200,decodedBodySize:700,encodedBodySize:500,workerStart:10};
  h.timings([
    {...entry,startTime:1,transferSize:800},
    {...entry,startTime:2,transferSize:0,deliveryType:'cache'},
    {...entry,startTime:2,transferSize:0,deliveryType:'cache'},
    {...entry,startTime:3,transferSize:0},
  ]);
  assert.equal(h.health().cacheSamples,2);
  assert.equal(h.health().cacheHits,1);
  assert.equal(h.health().cacheBytes,500);cases++;
}
{
  const h=harness();
  h.context.__DSH_PAL_PLUGIN_POLICY__={report:()=>({supported:true,paused:['fixture-plugin'],required:['fixture-required']})};
  h.addScript({tagName:'SCRIPT',src:'https://example.test/plugins/??fixture/client.js&rev=42'});
  h.tick(250);
  assert.equal(h.reported.at(-1).payload.pausedPlugins,1);
  assert.equal(h.reported.at(-1).payload.restrictedPlugins,0);
  assert.ok(!JSON.stringify(h.reported.at(-1)).includes('fixture-plugin'));
  cases++;
}
console.log(`PASS ${cases} business-health checks: headers, body stall, recovery, cache, plugin status, errors, abort, privacy and pending writes`);
