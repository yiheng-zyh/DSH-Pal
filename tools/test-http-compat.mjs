import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {once} from 'node:events';
import zlib from 'node:zlib';
import {createGateway} from './cpolar-http-compat.mjs';
const original='fixture body '.repeat(30000),received=[],upgrades=new Set();
const upstream=http.createServer((req,res)=>{
  received.push(req.headers);
  if(req.headers['accept-encoding']!=='identity'){res.writeHead(200,{'content-encoding':'gzip'});res.write(Buffer.from('1f8b0800000000000003','hex'));return;}
  if(req.url==='/remote/api/read'){res.writeHead(403,{'content-type':'application/json'});res.end('{"error":"unpaired"}');return;}
  if(req.url==='/remote/json'){res.writeHead(200,{'content-type':'application/json','connection':'close','x-fixture':'kept'});res.end(JSON.stringify({text:original}));return;}
  if(req.url==='/remote/events'){res.writeHead(200,{'content-type':'text/event-stream'});res.write('data: first\n\n');setTimeout(()=>res.end('data: last\n\n'),10);return;}
  res.writeHead(200,{'content-type':req.url==='/'?'text/html':'text/javascript','content-length':Buffer.byteLength(original),'cache-control':'no-store','etag':'"fixture"','set-cookie':'seat=fixture; HttpOnly; Secure; SameSite=Lax','content-security-policy':"default-src 'self'"});res.end(original);
});
upstream.on('upgrade',(req,socket)=>{received.push(req.headers);upgrades.add(socket);socket.on('close',()=>upgrades.delete(socket));socket.on('end',()=>socket.end());socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');socket.on('data',data=>socket.write(data));});
upstream.listen(0,'127.0.0.1');await once(upstream,'listening');
const state={origin:'https://fixture.r1.cpolar.cn',targetPort:upstream.address().port};
const gateway=createGateway(()=>state,{compressAssets:true,minGzipBytes:0}),small=createGateway(()=>state,{maxBuffer:20}),normal=createGateway(()=>state);
for(const server of [gateway,small,normal]){server.listen(0,'127.0.0.1');await once(server,'listening');}
function request(server,path='/assets/fixture.js',extra={}){return new Promise((resolve,reject)=>{const req=http.get({host:'127.0.0.1',port:server.address().port,path,headers:{host:'fixture.r1.cpolar.cn',origin:state.origin,cookie:'seat=fixture','accept-encoding':'gzip',...extra}},res=>{const parts=[];res.on('data',part=>parts.push(part));res.on('end',()=>resolve({status:res.statusCode,headers:res.headers,body:Buffer.concat(parts)}));res.on('error',reject)});req.setTimeout(2000,()=>req.destroy(Error('request timed out')));req.on('error',reject)});}
try {
 const normalResponse=await request(normal);assert.equal(normalResponse.body.toString(),original);assert.equal(normalResponse.headers['content-encoding'],'identity');assert.equal(+normalResponse.headers['content-length'],Buffer.byteLength(original));assert.match(normalResponse.headers['cache-control'],/no-transform/);assert.match(normalResponse.headers.vary,/Accept-Encoding/i);assert.equal(normalResponse.headers.connection,'close');
 const html=await request(normal,'/');assert.equal(zlib.gunzipSync(html.body).toString(),original);assert.equal(+html.headers['content-length'],html.body.length);
 const compressed=await request(gateway);assert.equal(compressed.status,200);assert.equal(zlib.gunzipSync(compressed.body).toString(),original);assert.equal(+compressed.headers['content-length'],compressed.body.length);assert.equal(compressed.headers['transfer-encoding'],undefined);assert.equal(compressed.headers.etag,'W/"fixture"');assert.match(compressed.headers['set-cookie'][0],/HttpOnly; Secure/);assert.equal(compressed.headers['content-security-policy'],"default-src 'self'");
 assert.equal(received[0].host,'fixture.r1.cpolar.cn');assert.equal(received[0].origin,state.origin);assert.equal(received[0].cookie,'seat=fixture');assert.ok(received[0]['x-forwarded-for']);
 const json=await request(gateway,'/remote/json');assert.equal(JSON.parse(json.body).text,original);assert.equal(json.headers['x-fixture'],'kept');assert.equal(+json.headers['content-length'],json.body.length);
 const defaultPort=await request(gateway,'/',{host:'fixture.r1.cpolar.cn:443'});assert.equal(defaultPort.status,200);assert.equal(received.at(-1).host,'fixture.r1.cpolar.cn:443');
 const identity=await request(gateway,'/',{'accept-encoding':'gzip;q=0'});assert.equal(identity.headers['content-encoding'],'identity');assert.equal(identity.body.toString(),original);
 const bounded=await request(small);assert.equal(bounded.headers['content-encoding'],undefined);assert.equal(bounded.body.toString(),original);
 assert.equal((await request(gateway,'/remote/api/read')).status,403);assert.equal((await request(gateway,'/api/pair/issue')).status,403);assert.equal((await request(gateway,'/api')).status,403);assert.equal((await request(gateway,'/',{connection:'Host'})).status,403);assert.equal((await request(gateway,'/',{host:'other.r1.cpolar.cn'})).status,403);
 assert.equal((await request(gateway,'/remote/events')).body.toString(),'data: first\n\ndata: last\n\n');
 const socket=net.connect(gateway.address().port,'127.0.0.1');await once(socket,'connect');socket.write('GET /remote/api/remote.mux HTTP/1.1\r\nHost: fixture.r1.cpolar.cn\r\nOrigin: '+state.origin+'\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');assert.match((await once(socket,'data'))[0].toString(),/101 Switching/);socket.write('frame');assert.equal((await once(socket,'data'))[0].toString(),'frame');socket.destroy();
 console.log('PASS bounded gzip with fixed length, identity fallback, original authority/cookies/CSP, auth denial, SSE and WebSocket streams');
}finally{for(const socket of upgrades)socket.destroy();for(const server of [gateway,small,normal,upstream]){server.closeAllConnections();server.close();}}
