import test from 'node:test';
import assert from 'node:assert/strict';
import {freeMainlandNodes,sakuraRequest} from './sakura.mjs';
test('node selection excludes paid, overseas, full, offline and mandatory-auth nodes',()=>{
  const nodes={1:{vip:0,flag:12},2:{vip:1,flag:12},3:{vip:0,flag:4},4:{vip:0,flag:8},5:{vip:0,flag:524},6:{vip:0,flag:268}};
  assert.deepEqual(freeMainlandNodes(nodes).map(x=>x.id),[1]);
});
test('provider errors never expose token or response payload; mutations are not retried',async()=>{
  let calls=0;
  const fetchImpl=async(url,options)=>{
    calls++;assert.equal(options.redirect,'error');assert.equal(options.headers.Authorization,'Bearer private-test-sentinel');
    assert.equal(options.method,'POST');assert.equal(options.body.get('name'),'test');
    return new Response('credential private-test-sentinel',{status:500});
  };
  await assert.rejects(sakuraRequest('private-test-sentinel','/tunnels',{method:'POST',form:{name:'test'},fetchImpl}),e=>e.message==='Sakura API /tunnels: HTTP 500');
  assert.equal(calls,1);
});
