import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {checkDshPort,findDshPort,isDshStatus} from './discovery.mjs';
test('discovery fingerprints DSH rather than accepting any HTTP 200',async()=>{
  assert.equal(isDshStatus({ok:true}),false);
  const manual={ok:true,paired:false,requirePairingForLan:true,phase:'connected',lanAvailable:false,tunnel:{state:'running'}};
  assert.equal(isDshStatus(manual),true);
  assert.equal(isDshStatus({...manual,relay:{state:'off'}}),true);
  assert.equal(isDshStatus({...manual,relay:{unexpected:true}}),false);
  const sockets=new Set();let mode='valid';
  const server=http.createServer((req,res)=>{
    if(mode==='hang'){res.writeHead(200);res.write('{');return;}
    if(mode==='large'){res.end(' '.repeat(70000));return;}
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify(mode==='valid'?manual:{ok:true}));
  });
  server.on('connection',s=>{sockets.add(s);s.once('close',()=>sockets.delete(s));});
  server.listen(0,'127.0.0.1');await once(server,'listening');const port=server.address().port;
  try{
    assert.equal(await checkDshPort(port),true);
    assert.equal(await findDshPort({preferred:port,discoverPorts:()=>{throw Error('Should reuse verified target')}}),port);
    mode='other';assert.equal(await checkDshPort(port),false);
    mode='large';assert.equal(await checkDshPort(port),false);
    mode='hang';const start=Date.now();assert.equal(await checkDshPort(port),false);assert.ok(Date.now()-start<3500);
    assert.equal(await checkDshPort(0),false);
  }finally{for(const s of sockets)s.destroy();await new Promise(r=>server.close(r));}
});
