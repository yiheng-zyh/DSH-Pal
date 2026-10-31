import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {validateConfig} from './config.mjs';
import {dataDirectory,loadSakuraToken} from './windows.mjs';
import {findDshPort} from './discovery.mjs';
import {createGateway} from './gateway.mjs';
import {applyTrustedHost} from './trust.mjs';

const directory=dataDirectory();
const config=validateConfig(JSON.parse(await fs.readFile(path.join(directory,'config.json'),'utf8')));
let targetPort=null,child=null,stopping=false,failures=0,restartAt=0,clientState='not-started';
let token;
let lastTrustCheck=0;
// Reuse only a previously recorded port as a discovery hint, never as a
// trusted target. findDshPort fingerprints it again before forwarding.
let preferredPort=config.dshPort;
if(preferredPort===undefined){
  try{
    const previous=JSON.parse(await fs.readFile(path.join(directory,'state.json'),'utf8'));
    if(previous.publicOrigin===config.publicOrigin&&Number.isInteger(previous.targetPort)&&previous.targetPort>=1024&&previous.targetPort<=65535)
      preferredPort=previous.targetPort;
  }catch{}
}
if(config.provider==='sakura'){
  const digest=createHash('sha256').update(await fs.readFile(config.frpcPath)).digest('hex');
  if(digest!==config.frpcSha256)throw Error('frpc executable changed; verify it before starting');
  token=await loadSakuraToken(directory);
}
const gateway=createGateway({publicOrigin:config.publicOrigin,getTargetPort:()=>targetPort});
// Bind first: duplicate launch fails before creating any provider client.
await gateway.listen(config.gatewayPort);
console.log('Loopback gateway started; this does not establish business readiness.');
function startClient(){
  if(config.provider!=='sakura'||child||stopping)return;
  const env={...process.env,NATFRP_TOKEN:token,NATFRP_TARGET:String(config.tunnelId)};
  for(const key of Object.keys(env))if(/^(https?|all|no)_proxy$/i.test(key))delete env[key];
  child=spawn(config.frpcPath,['--proxy','none','--disable_log_color','--no_check_update','--watch',String(process.pid)],{
    env,cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']
  });
  clientState='running-unverified';const began=Date.now();
  // The upstream client may log its access token. Never persist raw client output.
  for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{
    const text=bytes.toString('utf8');
    if(/start proxy success/i.test(text))clientState='tunnel-started-unverified';
    else if(/certificate.*(pending|issuing)|正在签发/i.test(text))clientState='certificate-pending';
    else if(/error|failed/i.test(text))clientState='client-reported-error';
  });
  child.once('error',()=>{clientState='spawn-failed';});
  child.once('exit',()=>{
    child=null;if(stopping)return;
    failures=Date.now()-began>120000?0:failures+1;
    restartAt=Date.now()+Math.min(60000,2000*2**Math.min(failures,5));
    clientState='waiting-to-retry';
  });
}
async function update(){
  if(config.dshConfigPath&&Date.now()-lastTrustCheck>60000){
    await applyTrustedHost(config.dshConfigPath,config.publicOrigin);lastTrustCheck=Date.now();
  }
  targetPort=await findDshPort({preferred:targetPort??preferredPort});
  if(targetPort!==null)preferredPort=targetPort;
  // Do not publish an external tunnel while initial DSH discovery is pending.
  if(targetPort!==null&&!child&&Date.now()>=restartAt)startClient();
  const value={updatedAt:new Date().toISOString(),pid:process.pid,targetAvailable:targetPort!==null,
    targetPort,clientState,publicOrigin:config.publicOrigin,businessVerified:false,...gateway.snapshot()};
  const temporary=path.join(directory,`state-${process.pid}.tmp`);
  await fs.writeFile(temporary,JSON.stringify(value,null,2));await fs.rename(temporary,path.join(directory,'state.json'));
}
async function stop(){
  if(stopping)return;stopping=true;if(child)child.kill();await gateway.close();
}
process.on('SIGINT',()=>{stop().finally(()=>process.exit(0));});
process.on('SIGTERM',()=>{stop().finally(()=>process.exit(0));});
try{
  while(!stopping){await update();await new Promise(resolve=>setTimeout(resolve,5000));}
}finally{await stop();}
