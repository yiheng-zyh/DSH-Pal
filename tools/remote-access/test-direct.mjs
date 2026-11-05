import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {once} from 'node:events';
import {
  bareAddress,isIPv6Literal,isGlobalIPv6,isPrivateIPv4,authorityOf,directOriginOf,
  advertisableAddresses,planDirectEntries,directEntry,
} from './direct.mjs';
import {validateConfig} from './config.mjs';
import {createGateway} from './gateway.mjs';
import {planTrustedHost} from './trust.mjs';

// 这些地址判据必须和 App 侧 core/ConnectionProfile.kt 保持一致，
// 否则「电脑生成了二维码、手机拒绝保存」。用例刻意与 Kotlin 测试同批地址。
test('global IPv6 is recognized and non-routable classes are rejected',()=>{
  for(const host of ['240a:42c6:e402:743:6504:f615:6326:cf3f','[240a:42c6:e402:743::1]','2408:8000::1','2606:4700::1','2001:db7::1','2002::1'])
    assert.equal(isGlobalIPv6(host),true,host);
  for(const host of ['::','::1','[::1]','::ffff:127.0.0.1','::ffff:192.168.1.1','fe80::1','fe9f::1','febf:ffff::1',
    'fc00::1','fd00::1','fd7a:115c:a1e0::c029:d60e','ff02::1','2001:db8::1','2001:0db8:1234::9',
    '192.168.1.1','example.org','2408:8000:::1','1:2:3:4:5:6:7','g408::1'])
    assert.equal(isGlobalIPv6(host),false,host);
  assert.equal(isIPv6Literal('[fd7a:115c:a1e0::1]%3'),false,'zone id 的方括号形式不是纯字面量');
  assert.equal(isIPv6Literal('fd7a:115c:a1e0::1%3'),true);
});

test('private IPv4 matches the App-side rule',()=>{
  for(const host of ['10.0.0.1','192.168.1.1','172.16.0.1','172.31.255.254']) assert.equal(isPrivateIPv4(host),true,host);
  for(const host of ['172.15.0.1','172.32.0.1','8.8.8.8','169.254.1.1','100.64.0.1','example.org']) assert.equal(isPrivateIPv4(host),false,host);
});

test('bareAddress strips brackets and zone ids',()=>{
  assert.equal(bareAddress('[2408:8000::1]'),'2408:8000::1');
  assert.equal(bareAddress('FE80::1%eth0'),'fe80::1');
  assert.equal(bareAddress(' fe80::1%25eth0 '),'fe80::1');
  assert.equal(bareAddress('192.168.1.1'),'192.168.1.1');
});

test('authority and origin bracket IPv6 but not IPv4',()=>{
  assert.equal(authorityOf('2408:8000::1',18772),'[2408:8000::1]:18772');
  assert.equal(authorityOf('[2408:8000::1]',18772),'[2408:8000::1]:18772');
  assert.equal(authorityOf('192.168.1.4',18772),'192.168.1.4:18772');
  assert.equal(directOriginOf('2408:8000::1',18772),'http://[2408:8000::1]:18772');
  assert.equal(directOriginOf('192.168.1.4',18772),'http://192.168.1.4:18772');
});

test('advertisableAddresses keeps global IPv6 and private IPv4, drops tunnels and link-local',()=>{
  const interfaces={
    'WLAN':[
      {address:'fe80::ca1b:4868:fd19:2283%3',family:'IPv6',internal:false},   // 链路本地 + zone，丢
      {address:'240a:42c6:e402:743:6504:f615:6326:cf3f',family:'IPv6',internal:false}, // 全局，留
      {address:'240a:42c6:e402:743:5033:5562:a171:b70b',family:'IPv6',internal:false}, // 第二个全局，留
      {address:'172.31.144.190',family:'IPv4',internal:false},                // 私有 IPv4，留
      {address:'127.0.0.1',family:'IPv4',internal:true},                      // internal，丢
    ],
    'Tailscale':[{address:'fd7a:115c:a1e0::c029:d60e',family:'IPv6',internal:false}], // 隧道接口名，丢
    'vEthernet (WSL)':[{address:'172.20.0.1',family:'IPv4',internal:false}],   // 虚拟网卡，丢
    'Ethernet':[{address:'8.8.8.8',family:'IPv4',internal:false}],             // 公网 IPv4，不当直连
  };
  const result=advertisableAddresses(interfaces);
  assert.deepEqual(result.ipv6.map(x=>x.address),['240a:42c6:e402:743:6504:f615:6326:cf3f','240a:42c6:e402:743:5033:5562:a171:b70b']);
  assert.deepEqual(result.ipv4.map(x=>x.address),['172.31.144.190']);
});

test('planDirectEntries reflects the toggles and reports missing kinds instead of throwing',()=>{
  const addresses=advertisableAddresses({'WLAN':[
    {address:'2408:8000::1',family:'IPv6',internal:false},{address:'192.168.1.4',family:'IPv4',internal:false}]});
  const both=planDirectEntries({enableIPv6:true,enableLAN:true,port:18772,ipv6:addresses.ipv6,ipv4:addresses.ipv4});
  assert.deepEqual(both.reasons,[]);
  assert.equal(both.entries.length,2);
  assert.equal(both.entries[0].trustedHost,'[2408:8000::1]:18772');
  assert.equal(both.entries[1].origin,'http://192.168.1.4:18772');
  // 关掉一个开关就少一类；两个都关时集合为空（等同改动前只绑回环）。
  assert.equal(planDirectEntries({enableIPv6:false,enableLAN:true,port:18772,ipv6:addresses.ipv6,ipv4:addresses.ipv4}).entries.length,1);
  const off=planDirectEntries({enableIPv6:false,enableLAN:false,port:18772,ipv6:addresses.ipv6,ipv4:addresses.ipv4});
  assert.deepEqual(off.entries,[]);assert.deepEqual(off.reasons,[]);
  // 请求了 IPv6 但本机没有 → 报 reason，不抛错（换网络后地址消失是常态，不能让网关起不来）。
  const noV6=planDirectEntries({enableIPv6:true,enableLAN:false,port:18772,ipv6:[],ipv4:addresses.ipv4});
  assert.deepEqual(noV6.entries,[]);assert.deepEqual(noV6.reasons,['ipv6-unavailable']);
  assert.throws(()=>planDirectEntries({enableIPv6:true,enableLAN:false,port:80,ipv6:[],ipv4:[]}),/1024/);
});

test('directEntry validates kind against address class',()=>{
  assert.equal(directEntry({kind:'ipv6',address:'2408:8000::1',port:18772}).trustedHost,'[2408:8000::1]:18772');
  assert.equal(directEntry({kind:'lan',address:'192.168.1.4',port:18772}).origin,'http://192.168.1.4:18772');
  assert.throws(()=>directEntry({kind:'ipv6',address:'fd7a:115c:a1e0::1',port:18772}),/routable global IPv6/);
  assert.throws(()=>directEntry({kind:'lan',address:'8.8.8.8',port:18772}),/private LAN IPv4/);
  assert.throws(()=>directEntry({kind:'wan',address:'8.8.8.8',port:18772}),/must be ipv6 or lan/);
  assert.throws(()=>directEntry({kind:'ipv6',address:'2408:8000::1',port:80}),/1024/);
});

test('config direct section is optional and defaults to the pre-change behaviour',()=>{
  const base={version:1,provider:'external',publicOrigin:'https://gateway.example.test:18443',gatewayPort:18772};
  assert.equal(validateConfig(base).direct,undefined,'没有 direct 段时不注入任何字段');
  assert.deepEqual(validateConfig({...base,direct:{enabled:false}}).direct,{enabled:false,ipv6:false,lan:false});
  assert.deepEqual(validateConfig({...base,direct:{enabled:true,ipv6:true,lan:false}}).direct,{enabled:true,ipv6:true,lan:false});
  // 开了 enabled 但两类都没选是配置错误，不能静默当成「什么都不绑」。
  assert.throws(()=>validateConfig({...base,direct:{enabled:true}}),/at least one/);
  assert.throws(()=>validateConfig({...base,direct:'ipv6'}),/must be an object/);
});

// ---- 网关直连监听（真实绑定，用 IPv6 回环 ::1 保证测试机一定有 IPv6 栈）----

async function directFixture(handler){
  const sockets=new Set();
  const backend=http.createServer(handler);
  backend.on('connection',socket=>{sockets.add(socket);socket.on('close',()=>sockets.delete(socket));});
  backend.listen(0,'127.0.0.1');await once(backend,'listening');
  const target=backend.address().port;
  const gateway=createGateway({
    publicOrigin:'https://gateway.example.test:18443',
    getTargetPort:()=>target,
    getDirectEntries:()=>gateway.liveDirectEntries().filter(entry=>entry.listening),
  });
  await gateway.listen(0);
  return {gateway,backend,target,
    close:async()=>{await gateway.close();for(const socket of sockets)socket.destroy();await new Promise(resolve=>backend.close(resolve));}};
}

test('a direct listener serves its own authority and reports it downstream',async()=>{
  let seen;
  const f=await directFixture((req,res)=>{seen={headers:req.headers};res.writeHead(200,{'content-type':'text/plain'});res.end('ok');});
  let directPort=0;
  try{
    // 绑 ::1 的空闲端口（0 由内核分配），entry 用 ::1 的 authority。
    const states=await f.gateway.syncDirect([{kind:'ipv6',address:'::1',port:0,origin:'http://[::1]:0',trustedHost:'[::1]:0'}]);
    // port:0 时 authority 需要按内核实际绑定端口重算，这里直接读回绑定结果。
    const bound=states.find(s=>s.address==='::1');
    assert.equal(bound.listening,true,bound.error);
    directPort=bound.port;
    const result=await new Promise((resolve,reject)=>{
      const req=http.request({host:'::1',port:directPort,path:'/api/pair/status',method:'GET',
        headers:{host:`[::1]:${directPort}`}},res=>{
        const parts=[];res.on('data',p=>parts.push(p));res.on('end',()=>resolve({status:res.statusCode,body:Buffer.concat(parts).toString()}));
      });
      req.setTimeout(3000,()=>req.destroy(Error('timeout')));req.on('error',reject);req.end();
    });
    assert.equal(result.status,200);
    // 关键：下游收到的 x-forwarded-host 是直连 authority，不是隧道域名；proto 是 http。
    assert.equal(seen.headers['x-forwarded-host'],`[::1]:${directPort}`);
    assert.equal(seen.headers['x-forwarded-proto'],'http');
    assert.equal(f.gateway.snapshot().directRequests,1);
  }finally{await f.close();}
});

test('an unregistered Host on a direct listener is refused, loopback authority still works',async()=>{
  const f=await directFixture((_,res)=>res.end('ok'));
  try{
    const states=await f.gateway.syncDirect([{kind:'ipv6',address:'::1',port:0,origin:'http://[::1]:0',trustedHost:'[::1]:0'}]);
    const directPort=states.find(s=>s.address==='::1').port;
    const dial=host=>new Promise((resolve,reject)=>{
      const req=http.request({host:'::1',port:directPort,path:'/api/pair/status',headers:{host}},res=>{
        res.resume();resolve(res.statusCode);});
      req.setTimeout(3000,()=>req.destroy(Error('timeout')));req.on('error',reject);req.end();
    });
    assert.equal(await dial('evil.example.test'),403);                   // 未登记 Host → 403
    assert.equal(await dial('gateway.example.test:18443'),200);          // 隧道 authority 仍被接受
    assert.equal(await dial(`[::1]:${directPort}`),200);                 // 已登记 → 200
    assert.equal(await dial('127.0.0.1:8080'),403);                      // 冒充回环也不行
  }finally{await f.close();}
});

test('closing direct entries unbinds them while the loopback gateway keeps serving',async()=>{
  const f=await directFixture((_,res)=>res.end('ok'));
  try{
    const states=await f.gateway.syncDirect([{kind:'ipv6',address:'::1',port:0,origin:'http://[::1]:0',trustedHost:'[::1]:0'}]);
    const directPort=states.find(s=>s.address==='::1').port;
    assert.equal(net.isIPv6('::1'),true);
    // 解绑
    const after=await f.gateway.syncDirect([]);
    assert.deepEqual(after,[]);
    // 直连端口已释放：连它会失败
    await assert.rejects(()=>new Promise((resolve,reject)=>{
      const socket=net.connect(directPort,'::1');
      socket.setTimeout(2000,()=>socket.destroy(Error('still listening')));
      socket.on('connect',()=>{socket.destroy();resolve();});
      socket.on('error',reject);socket.on('timeout',()=>reject(Error('still listening')));
    }));
    // 回环主监听不受影响
    const loopbackPort=f.gateway.server.address().port;
    const result=await new Promise((resolve,reject)=>{
      const req=http.request({host:'127.0.0.1',port:loopbackPort,path:'/api/pair/status',
        headers:{host:'gateway.example.test:18443'}},res=>{res.resume();resolve(res.statusCode);});
      req.setTimeout(3000,()=>req.destroy(Error('timeout')));req.on('error',reject);req.end();
    });
    assert.equal(result,200);
  }finally{await f.close();}
});

test('a bind failure is reported and the next round recovers without a restart',async()=>{
  const f=await directFixture((_,res)=>res.end('ok'));
  let squatter=null;
  try{
    // 取一个空闲端口并先占住，网关再绑就是 EADDRINUSE。
    squatter=http.createServer((_,res)=>res.end('busy'));
    await new Promise((resolve,reject)=>{squatter.once('error',reject);squatter.listen(0,'::1',resolve);});
    const directPort=squatter.address().port;
    const entry={kind:'ipv6',address:'::1',port:directPort,origin:`http://[::1]:${directPort}`,trustedHost:`[::1]:${directPort}`};
    const states=await f.gateway.syncDirect([entry]);
    const failed=states.find(s=>s.address==='::1');
    assert.equal(failed.listening,false);
    assert.match(failed.error,/EADDRINUSE/);
    // 失败的条目不进热路径白名单；回环主监听照常服务（换网络后地址消失不能拖垮本机 DSH）
    assert.deepEqual(f.gateway.liveDirectEntries().filter(e=>e.listening),[]);
    const loopbackPort=f.gateway.server.address().port;
    const stillWorking=await new Promise((resolve,reject)=>{
      const req=http.request({host:'127.0.0.1',port:loopbackPort,path:'/api/pair/status',
        headers:{host:'gateway.example.test:18443'}},res=>{res.resume();resolve(res.statusCode);});
      req.setTimeout(3000,()=>req.destroy(Error('timeout')));req.on('error',reject);req.end();
    });
    assert.equal(stillWorking,200);
    // 端口释放后下一轮 sync 自动绑上 —— 这就是 run.mjs 每 5 秒重规划的意义
    await new Promise(resolve=>squatter.close(resolve));squatter=null;
    const retried=await f.gateway.syncDirect([entry]);
    assert.equal(retried.find(s=>s.address==='::1').listening,true);
  }finally{if(squatter)await new Promise(resolve=>squatter.close(resolve));await f.close();}
});

test('WebSocket upgrade works over a direct IPv6 listener',async()=>{
  const f=await directFixture((_,res)=>res.end());
  let seen;
  f.backend.on('upgrade',(req,socket)=>{seen=req.headers;socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n');socket.on('data',bytes=>socket.write(bytes));});
  let socket=null;
  try{
    const states=await f.gateway.syncDirect([{kind:'ipv6',address:'::1',port:0,origin:'http://[::1]:0',trustedHost:'[::1]:0'}]);
    const directPort=states.find(s=>s.address==='::1').port;
    socket=net.connect(directPort,'::1');await once(socket,'connect');
    socket.write(`GET /remote/api/remote.mux HTTP/1.1\r\nHost: [::1]:${directPort}\r\nCookie: seat=fixture\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n`);
    assert.match((await once(socket,'data'))[0].toString(),/101 Switching Protocols/);
    // 任务流的 Cookie 原样透传，下游看到的是直连 authority 与 http
    assert.equal(seen.cookie,'seat=fixture');
    assert.equal(seen['x-forwarded-host'],`[::1]:${directPort}`);
    assert.equal(seen['x-forwarded-proto'],'http');
    const payload=Buffer.from([0x81,3,1,2,3]);socket.write(payload);assert.deepEqual((await once(socket,'data'))[0],payload);
    assert.equal(f.gateway.snapshot().taskMuxAccepted,1);
  }finally{socket?.destroy();await f.close();}
});

test('trustedHosts registers direct IPv6 authority with brackets alongside publicOrigin',()=>{
  const yaml=[
    'plugins:','- name: "@linxin666/dsh-remote-web-ui"','  config:','    trustedHosts:',
    '      - user.example.org  # user entry',
  ].join('\n');
  const plan=planTrustedHost(yaml,'https://gateway.example.test:18443',['http://[2408:8000::1]:18772','http://192.168.1.4:18772']);
  assert.equal(plan.changed,true);
  // 用户手写的条目保留；本工具新增的三条（publicOrigin host、IPv6 authority、LAN authority）都在。
  assert.match(plan.text,/user\.example\.org/);
  assert.match(plan.text,/\[2408:8000::1\]:18772/);
  assert.match(plan.text,/192\.168\.1\.4:18772/);
  assert.match(plan.text,/gateway\.example\.test/);
  // IPv6 条目必须带方括号：插件的 matchesAuthority 走 new URL('http://'+entry)，
  // 无方括号会让 URL 构造抛异常、Host 校验永远失败。
  assert.doesNotThrow(()=>new URL('http://'+'[2408:8000::1]:18772'));
});
