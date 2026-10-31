import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import {once} from 'node:events';
import {createGateway} from './gateway.mjs';
import {validateConfig} from './config.mjs';

const publicOrigin='https://gateway.example.test:18443';
async function fixture(handler){
  const sockets=new Set();
  const backend=http.createServer(handler);
  backend.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  backend.listen(0,'127.0.0.1');await once(backend,'listening');
  let target=backend.address().port;
  const gateway=createGateway({publicOrigin,getTargetPort:()=>target});
  await gateway.listen(0);
  return {gateway,backend,setTarget:value=>target=value,
    close:async()=>{await gateway.close();for(const socket of sockets)socket.destroy();await new Promise(resolve=>backend.close(resolve));}};
}
function request(f,path='/',{headers={},method='GET',body}={}){
  return new Promise((resolve,reject)=>{
    const req=http.request({host:'127.0.0.1',port:f.gateway.server.address().port,path,method,headers:{host:'gateway.example.test:18443',...headers}},res=>{
      const parts=[];res.on('data',part=>parts.push(part));res.on('error',reject);
      res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(parts)}));
    });
    req.setTimeout(3000,()=>req.destroy(Error('fixture request timed out')));req.on('error',reject);req.end(body);
  });
}
test('configuration rejects plaintext secrets, non-HTTPS origins and mismatched ports',()=>{
  const good={version:1,provider:'external',publicOrigin,gatewayPort:18772};
  assert.equal(validateConfig(good).publicOrigin,publicOrigin);
  for(const patch of [{token:'fixture-secret'},{publicOrigin:'http://example.test'},{publicOrigin:'https://example.test/path'},{publicOrigin:'https://user@example.test'},{dshPort:18772}])
    assert.throws(()=>validateConfig({...good,...patch}));
});
test('original authority and credentials remain intact; spoofed local forwarding metadata is replaced',async()=>{
  let seen;
  const f=await fixture((req,res)=>{seen={headers:req.headers,path:req.url};res.writeHead(200,{'content-type':'text/html','content-length':'2','set-cookie':'seat=fixture; Secure; HttpOnly; SameSite=Lax','content-security-policy':"default-src 'self'"});res.end('ok');});
  try{
    const result=await request(f,'/plugins/??module.js&rev=fixture',{headers:{origin:publicOrigin,cookie:'seat=fixture','x-forwarded-for':'127.0.0.1',forwarded:'for=127.0.0.1'}});
    assert.equal(result.status,200);assert.equal(result.body.toString(),'ok');
    assert.equal(seen.headers.host,'gateway.example.test:18443');assert.equal(seen.headers.origin,publicOrigin);assert.equal(seen.headers.cookie,'seat=fixture');
    assert.equal(seen.headers['x-forwarded-proto'],'https');assert.equal(seen.headers['x-forwarded-for'],'unknown');assert.equal(seen.headers.forwarded,undefined);
    assert.equal(seen.path,'/plugins/??module.js&rev=fixture');assert.equal(result.headers['content-length'],'2');
    assert.equal(result.headers['content-security-policy'],"default-src 'self'");assert.match(result.headers['set-cookie'][0],/Secure; HttpOnly/);
  }finally{await f.close();}
});
test('management path normalization and authority checks fail closed',async()=>{
  let reached=0;
  const f=await fixture((_,res)=>{reached++;res.end('ok');});
  try{
    for(const path of ['/api','/api/pair/issue','/%61pi/pair/issue','/%2561pi%252fpair%252fissue','/a/../api/private','//other.test/api','/%2e%2e/api/private'])
      assert.equal((await request(f,path)).status,403,path);
    for(const headers of [{host:'other.example.test:18443'},{host:'gateway.example.test'},{connection:'host'},{connection:'Authorization'}])
      assert.equal((await request(f,'/',{headers})).status,403);
    assert.equal(reached,0);
    assert.equal((await request(f,'/api/pair/status',{method:'POST'})).status,403);
    assert.equal((await request(f,'/api/pair/status')).status,200);assert.equal(reached,1);
    assert.equal((await request(f,'/api/pair/heartbeat',{method:'POST'})).status,200);
    assert.equal((await request(f,'/api/pair/accept',{method:'POST'})).status,200);
    assert.equal((await request(f,'/api/pair/heartbeat')).status,403);
    assert.equal((await request(f,'/remote-bridge/pair/token')).status,403);
    assert.equal((await request(f,'/remote-bridge%2fpair%2ftoken')).status,403);
  }finally{await f.close();}
});
test('authentication errors and application 503 are neither hidden nor retried',async()=>{
  let calls=0;
  const f=await fixture((req,res)=>{calls++;const status=req.headers.cookie?503:403;res.writeHead(status,{'cache-control':'no-store'});res.end(status===503?'relay storage unavailable':'unpaired');});
  try{
    assert.equal((await request(f,'/remote/api/session/list')).status,403);
    const result=await request(f,'/remote/api/session/list',{headers:{cookie:'seat=fixture'}});
    assert.equal(result.status,503);assert.equal(result.body.toString(),'relay storage unavailable');assert.equal(calls,2);
  }finally{await f.close();}
});
test('compression is passed through with exact encoded content length',async()=>{
  const body=zlib.gzipSync(Buffer.from('fixture source '.repeat(20000)));
  const f=await fixture((_,res)=>{res.writeHead(200,{'content-type':'text/javascript','content-encoding':'gzip','content-length':body.length});res.end(body);});
  try{
    const result=await request(f,'/assets/fixture.js');assert.deepEqual(result.body,body);
    assert.equal(+result.headers['content-length'],body.length);assert.equal(result.headers['content-encoding'],'gzip');
  }finally{await f.close();}
});
test('large resources stream with backpressure rather than a bundle-size buffer cutoff',async()=>{
  const chunk=Buffer.alloc(64*1024,0x5a),count=600;
  const expected=crypto.createHash('sha256');for(let i=0;i<count;i++)expected.update(chunk);
  const f=await fixture(async(_,res)=>{res.writeHead(200,{'content-type':'text/javascript','content-length':chunk.length*count});for(let i=0;i<count;i++)if(!res.write(chunk))await once(res,'drain');res.end();});
  try{
    const result=await request(f,'/plugins/large.js');assert.equal(result.body.length,chunk.length*count);
    assert.equal(crypto.createHash('sha256').update(result.body).digest('hex'),expected.digest('hex'));
    assert.equal(f.gateway.snapshot().responseBytes,result.body.length);
  }finally{await f.close();}
});
test('SSE delivers its first event before the upstream finishes',async()=>{
  let finish;
  const done=new Promise(resolve=>finish=resolve);
  const f=await fixture((_,res)=>{res.writeHead(200,{'content-type':'text/event-stream'});res.write('data: first\n\n');done.then(()=>res.end('data: last\n\n'));});
  try{
    const req=http.get({host:'127.0.0.1',port:f.gateway.server.address().port,path:'/remote/events',headers:{host:'gateway.example.test:18443'}});
    const [res]=await once(req,'response');const [first]=await once(res,'data');assert.equal(first.toString(),'data: first\n\n');
    const ended=once(res,'end');finish();res.resume();await ended;
  }finally{finish();await f.close();}
});
test('abandoning a resource cancels the corresponding local request',async()=>{
  let cancelled;
  const cancellation=new Promise(resolve=>cancelled=resolve);
  const f=await fixture((_,res)=>{res.writeHead(200);res.write('first');res.on('close',()=>cancelled());});
  try{
    const req=http.get({host:'127.0.0.1',port:f.gateway.server.address().port,path:'/plugins/slow.js',headers:{host:'gateway.example.test:18443'}});
    const [res]=await once(req,'response');await once(res,'data');req.destroy();
    await Promise.race([cancellation,new Promise((_,reject)=>setTimeout(()=>reject(Error('upstream was not cancelled')),2000))]);
    assert.equal(f.gateway.snapshot().failed,0);assert.equal(f.gateway.snapshot().cancelled,1);
  }finally{await f.close();}
});
test('WebSocket upgrade forwards authority, cookie and opaque bytes',async()=>{
  const f=await fixture((_,res)=>res.end());let seen;
  f.backend.on('upgrade',(req,socket)=>{seen=req.headers;socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');socket.on('data',bytes=>socket.write(bytes));});
  let socket;
  try{
    socket=net.connect(f.gateway.server.address().port,'127.0.0.1');await once(socket,'connect');
    socket.write('GET /remote/api/remote.mux HTTP/1.1\r\nHost: gateway.example.test:18443\r\nOrigin: '+publicOrigin+'\r\nCookie: seat=fixture\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');
    assert.match((await once(socket,'data'))[0].toString(),/101 Switching Protocols/);
    assert.equal(seen.cookie,'seat=fixture');assert.equal(seen.origin,publicOrigin);assert.equal(seen.host,'gateway.example.test:18443');
    const payload=Buffer.from([0x81,3,1,2,3]);socket.write(payload);assert.deepEqual((await once(socket,'data'))[0],payload);
    assert.equal(f.gateway.snapshot().webSocketAccepted,1);assert.equal(f.gateway.snapshot().taskMuxAccepted,1);
    assert.equal(f.gateway.snapshot().upgradeClientBytes,payload.length);
  }finally{socket?.destroy();await f.close();}
});
test('a missing local service stays a real failure and recovers when a verified target is supplied',async()=>{
  const f=await fixture((_,res)=>res.end('healthy'));
  try{
    const actual=f.backend.address().port;f.setTarget(0);assert.equal((await request(f)).status,503);
    f.setTarget(actual);assert.equal((await request(f)).body.toString(),'healthy');
  }finally{await f.close();}
});
