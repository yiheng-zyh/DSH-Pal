import {execFile} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import {fileURLToPath} from 'node:url';
export const name='dsh-mobile-tailscale-serve';
export const inject=['webServer'];
const ownerFile=fileURLToPath(new URL('../.local/tailscale-serve-owner.json',import.meta.url));
const originHash=origin=>createHash('sha256').update(origin).digest('hex');
export function restoredPort(record,origin,fallback) {
  return record?.version===1 && record.originHash===originHash(origin) && Number.isInteger(record.port) && record.port>0 && record.port<=65535 ? record.port : fallback;
}

export function planServeChange(configuration,origin,port,previousPort) {
  const authority=new URL(origin).host;
  const target=`http://127.0.0.1:${port}`;
  const handlers=configuration?.Web?.[authority+':443']?.Handlers;
  const existing=handlers?.['/']?.Proxy;
  const listener=configuration?.TCP?.['443'];
  if(listener && !listener.HTTPS)return 'conflict';
  if(configuration?.AllowFunnel?.[authority+':443'])return 'conflict';
  if(existing===target)return 'unchanged';
  // Fail closed: never take over another application's root or another HTTPS host.
  if(Object.keys(configuration?.Web??{}).some(key=>key!==authority+':443'))return 'conflict';
  if(handlers && Object.keys(handlers).some(key=>key!=='/'))return 'conflict';
  if(existing && existing!==`http://127.0.0.1:${previousPort}`)return 'conflict';
  if(handlers?.['/'] && !existing)return 'conflict';
  return 'update';
}

export function apply(ctx,config={}) {
  const origin=new URL(config.origin);
  if(origin.protocol!=='https:' || !origin.hostname.endsWith('.ts.net'))throw Error('Expected Tailscale HTTPS origin');
  const binary=config.tailscaleBin||(process.platform==='win32'?'C:\\Program Files\\Tailscale\\tailscale.exe':'tailscale');
  let record;try{record=JSON.parse(fs.readFileSync(ownerFile,'utf8'));}catch{}
  let stopped=false,busy=false,previousPort=restoredPort(record,origin.origin,Number(config.previousPort)),lastMessage='';
  const logger=ctx.logger?.(name)||console;
  function report(message){if(message!==lastMessage){lastMessage=message;logger.info?.(message);}}
  function call(args){return new Promise((resolve,reject)=>execFile(binary,args,{windowsHide:true,timeout:8000,maxBuffer:1048576},(error,stdout)=>error?reject(error):resolve(stdout)));}
  async function reconcile() {
    if(stopped||busy)return;
    const port=Number(ctx.webServer?.port);
    if(!Number.isInteger(port)||port<1||port>65535)return;
    busy=true;
    try {
      const state=JSON.parse(await call(['status','--json']));
      if(state.BackendState!=='Running'){report('Tailscale is not logged in or connected; existing settings retained');return;}
      if('https://'+String(state.Self?.DNSName||'').replace(/\.$/,'')!==origin.origin){report('Tailscale account or device name changed; review connection configuration');return;}
      const current=JSON.parse(await call(['serve','status','--json']));
      const plan=planServeChange(current,origin.origin,port,previousPort);
      if(plan==='conflict'){report('Serve configuration belongs to another route; no changes made');return;}
      if(plan==='update'&&!stopped)await call(['serve','--bg','--yes','--https=443',`http://127.0.0.1:${port}`]);
      if(!stopped){
        if(record?.port!==port || record?.originHash!==originHash(origin.origin)) {
          const next={version:1,originHash:originHash(origin.origin),port};
          fs.mkdirSync(new URL('../.local/',import.meta.url),{recursive:true});
          fs.writeFileSync(ownerFile+'.tmp',JSON.stringify(next));fs.renameSync(ownerFile+'.tmp',ownerFile);record=next;
        }
        previousPort=port;report('Tailscale Serve points to the current DSH loopback port; binding unchanged');
      }
    }catch(error){report('Tailscale Serve check failed ('+String(error.code||error.name)+'); will retry without restarting DSH');}
    finally{busy=false;}
  }
  ctx.effect(()=>{
    const timer=setInterval(()=>void reconcile(),15000);timer.unref?.();void reconcile();
    return ()=>{stopped=true;clearInterval(timer);};
  });
}
