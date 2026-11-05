// Explicit integration check using an isolated Edge profile and local fixture.
// Usage: node verify-recovery-browser.mjs <absolute-existing-artifact-directory>
import fs from 'node:fs/promises';
import path from 'node:path';
import http from 'node:http';
import {spawn} from 'node:child_process';
import {once} from 'node:events';
import {createGateway} from './gateway.mjs';

const output=process.argv[2];
if(!output||!path.isAbsolute(output)||(await fs.stat(output)).isDirectory()!==true)throw Error('An existing absolute artifact directory is required');
const profile=await fs.mkdtemp(path.join(output,'edge-recovery-'));
const edge=path.join(process.env['ProgramFiles(x86)']||'C:/Program Files (x86)','Microsoft/Edge/Application/msedge.exe');await fs.access(edge);
const local=http.createServer((req,res)=>{
  if(req.url==='/api/pair/status'){res.setHeader('content-type','application/json');res.end(JSON.stringify({ok:true,paired:true,phase:'connected'}));}
  else{res.setHeader('content-type','text/html');res.end('<!doctype html><title>Recovery passed</title><div id="recovered">Local fixture restored</div>');}
});
local.listen(0,'127.0.0.1');await once(local,'listening');
const reserve=http.createServer();reserve.listen(0,'127.0.0.1');await once(reserve,'listening');const gatewayPort=reserve.address().port;await new Promise(r=>reserve.close(r));
let target=null,errorDocuments=0;
const gateway=createGateway({publicOrigin:`https://recovery.example.test:${gatewayPort}`,getTargetPort:()=>target});await gateway.listen(gatewayPort);
gateway.server.on('request',(_,res)=>res.once('finish',()=>{if(res.statusCode===503){errorDocuments++;target=local.address().port;}}));
let browser,socket;
try{
  browser=spawn(edge,['--headless=new','--no-first-run','--disable-background-networking','--disable-extensions','--no-proxy-server','--remote-debugging-port=0',`--user-data-dir=${profile}`,'--host-resolver-rules=MAP recovery.example.test 127.0.0.1','about:blank'],{windowsHide:true,stdio:'ignore'});
  const exited=new Promise(r=>browser.once('exit',r));
  let port;
  for(let i=0;i<100;i++){
    try{port=Number((await fs.readFile(path.join(profile,'DevToolsActivePort'),'utf8')).split('\n')[0]);if(port)break;}catch{}
    if(browser.exitCode!==null)throw Error('Isolated browser exited before debugging was available');
    await new Promise(r=>setTimeout(r,100));
  }
  if(!port)throw Error('Isolated browser did not expose debugging');
  const targets=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j;});
  let sequence=0;const pending=new Map();socket.onmessage=e=>{const message=JSON.parse(e.data);if(message.id){pending.get(message.id)?.(message);pending.delete(message.id);}};
  const call=(method,params={})=>new Promise((r,j)=>{const id=++sequence;const timeout=setTimeout(()=>{pending.delete(id);j(Error('Browser command timed out'));},5000);pending.set(id,value=>{clearTimeout(timeout);r(value);});socket.send(JSON.stringify({id,method,params}));});
  await call('Page.navigate',{url:`http://recovery.example.test:${gatewayPort}/pair-app`});
  let recovered=false;
  for(let i=0;i<60;i++){
    const result=await call('Runtime.evaluate',{expression:'!!document.getElementById("recovered")',returnByValue:true});
    if(result.result?.result?.value===true){recovered=true;break;}
    await new Promise(r=>setTimeout(r,200));
  }
  const result={recovered,errorDocuments,requests:gateway.snapshot().requests,usedUserProfile:false,contactedRealDsh:false};
  await fs.writeFile(path.join(output,'browser-recovery-result.json'),JSON.stringify(result,null,2));
  if(!recovered||errorDocuments!==1)throw Error('Browser did not recover from exactly one local 503');
  console.log(JSON.stringify(result));
  await call('Browser.close').catch(()=>{});await Promise.race([exited,new Promise(r=>setTimeout(r,2000))]);
}finally{
  socket?.close();if(browser&&browser.exitCode===null)browser.kill();
  await gateway.close();local.closeAllConnections();await new Promise(r=>local.close(r));
  // Retain the isolated test profile as evidence; never remove user directories.
}
