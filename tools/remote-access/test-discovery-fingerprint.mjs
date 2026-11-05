import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {
  checkDshPort,checkViaDshAuth,checkViaPairStatus,findDshPort,isDshAuthRejection,isDshStatus,
} from './discovery.mjs';

/**
 * 起一个可控的本地服务，返回 {port, setMode, close}。
 *
 * 模式：
 *   pair      —— 旧插件风格 /api/pair/status，200 + 完整配对协议体
 *   dsh-auth  —— DSH 本体未鉴权：401 + "dsh web authentication required..."
 *   dsh-api   —— DSH 本体 /api/* 未鉴权：401 + "unauthorized"
 *   plain     —— 普通 200 服务
 *   plain401  —— 401 但不是 DSH（文案不符）
 */
async function serve(){
  const sockets=new Set();
  let mode='pair';
  const server=http.createServer((req,res)=>{
    const manual={ok:true,paired:false,requirePairingForLan:true,phase:'connected',lanAvailable:false};
    if(mode==='pair'){
      res.setHeader('Content-Type','application/json');
      res.end(JSON.stringify(req.url==='/api/pair/status'?manual:{ok:true}));
      return;
    }
    if(mode==='dsh-auth'){
      // 根路径给完整文案，其余给短语 —— 复现 DSH 的真实行为。
      res.writeHead(401);res.end(req.url==='/'?'dsh web authentication required; reopen the URL printed by dsh web.':'unauthorized');
      return;
    }
    if(mode==='dsh-api'){
      res.writeHead(401);res.end('unauthorized');
      return;
    }
    if(mode==='plain401'){
      res.writeHead(401);res.end('nope');
      return;
    }
    res.writeHead(200);res.end('hello world');
  });
  server.on('connection',s=>{sockets.add(s);s.once('close',()=>sockets.delete(s));});
  server.listen(0,'127.0.0.1');await once(server,'listening');
  return {
    port:server.address().port,
    setMode:next=>{mode=next;},
    close:async()=>{for(const s of sockets)s.destroy();await new Promise(r=>server.close(r));},
  };
}

test('isDshAuthRejection recognises only DSH own rejection text',async()=>{
  // 正例：DSH 本体的完整文案（尾部可能追加提示，只认前缀）
  assert.equal(isDshAuthRejection('dsh web authentication required; reopen the URL printed by dsh web.'),true);
  assert.equal(isDshAuthRejection('dsh web authentication required'),true);
  assert.equal(isDshAuthRejection('  DSH WEB AUTHENTICATION REQUIRED  '),true);
  // 正例：/api/* 的短语
  assert.equal(isDshAuthRejection('unauthorized'),true);
  // 反例：别的 401 文案不能被当成 DSH
  assert.equal(isDshAuthRejection('nope'),false);
  assert.equal(isDshAuthRejection('401 Unauthorized'),false);
  assert.equal(isDshAuthRejection(''),false);
  assert.equal(isDshAuthRejection(undefined),false);
  assert.equal(isDshAuthRejection(null),false);
});

test('discovery survives the retired plugin: DSH body fingerprint is enough',async()=>{
  const srv=await serve();
  try{
    // 第 1 层在位：旧插件风格，行为与历史一致。
    srv.setMode('pair');
    assert.equal(await checkViaPairStatus(srv.port),true);
    assert.equal(await checkDshPort(srv.port),true);

    // 旧插件退役后：/api/pair/status 不再存在，只剩 DSH 本体的鉴权拒绝。
    srv.setMode('dsh-auth');
    assert.equal(await checkViaPairStatus(srv.port),false,'旧插件指纹应当失效');
    assert.equal(await checkViaDshAuth(srv.port),true,'DSH 本体指纹应当接管');
    assert.equal(await checkDshPort(srv.port),true,'整体识别必须仍成立');

    // 只有 /api/* 短语也够（某些路径下 DSH 只回简短串）。
    srv.setMode('dsh-api');
    assert.equal(await checkDshPort(srv.port),true);
  }finally{await srv.close();}
});

test('discovery never mistakes an unrelated service for DSH',async()=>{
  const srv=await serve();
  try{
    srv.setMode('plain');
    assert.equal(await checkDshPort(srv.port),false,'普通 200 服务不能误判');

    srv.setMode('plain401');
    assert.equal(await checkDshPort(srv.port),false,'非 DSH 的 401 不能误判');
  }finally{await srv.close();}
});

test('findDshPort falls back to scanning when preferred is wrong',async()=>{
  const srv=await serve();
  try{
    srv.setMode('dsh-auth');
    // preferred 指向一个未监听端口 -> 必须走 discoverPorts 回退
    const found=await findDshPort({preferred:1,discoverPorts:async()=>[srv.port]});
    assert.equal(found,srv.port);
  }finally{await srv.close();}
});
