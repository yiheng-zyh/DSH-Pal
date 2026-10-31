import http from 'node:http';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const execute=promisify(execFile);

export function isDshStatus(value){
  // A disabled Relay is omitted by the plugin in manual-public-origin mode.
  // Fingerprint the stable pairing protocol, not optional provider status.
  return !!value&&value.ok===true&&typeof value.paired==='boolean'&&
    typeof value.requirePairingForLan==='boolean'&&typeof value.phase==='string'&&typeof value.lanAvailable==='boolean'&&
    (value.tunnel===undefined||typeof value.tunnel?.state==='string')&&
    (value.relay===undefined||typeof value.relay?.state==='string');
}
export function checkDshPort(localPort){
  if(!Number.isInteger(localPort)||localPort<1024||localPort>65535)return Promise.resolve(false);
  return new Promise(resolve=>{
    let bytes=0,parts=[];
    const req=http.get({host:'127.0.0.1',port:localPort,path:'/api/pair/status',agent:false},res=>{
      if(res.statusCode!==200){res.resume();resolve(false);return;}
      res.on('data',chunk=>{bytes+=chunk.length;if(bytes>65536){req.destroy();return;}parts.push(chunk);});
      res.on('end',()=>{try{resolve(isDshStatus(JSON.parse(Buffer.concat(parts).toString('utf8'))));}catch{resolve(false);}});
      res.on('error',()=>resolve(false));
    });
    req.setTimeout(2000,()=>req.destroy());req.on('error',()=>resolve(false));req.on('close',()=>resolve(false));
  });
}
export async function findDshPort({preferred,discoverPorts=windowsDshPorts}={}){
  if(Number.isInteger(preferred)&&preferred>=1024&&preferred<=65535&&await checkDshPort(preferred))return preferred;
  for(const candidate of await discoverPorts())if(await checkDshPort(candidate))return candidate;
  return null;
}
export async function windowsDshPorts(){
  if(process.platform!=='win32')return [];
  // Only inspect ports owned by DSH, never scan unrelated local services.
  const command="$owners=@(Get-CimInstance Win32_Process -Filter \"Name='DeepSeek Harness.exe'\" | Select-Object -ExpandProperty ProcessId); if($owners.Count){Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$owners -contains $_.OwningProcess -and $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::')} | Select-Object -ExpandProperty LocalPort -Unique | ConvertTo-Json -Compress}";
  try{
    const {stdout}=await execute('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{windowsHide:true,timeout:12000,maxBuffer:65536});
    if(!stdout.trim())return [];
    const value=JSON.parse(stdout);return (Array.isArray(value)?value:[value]).filter(p=>Number.isInteger(p)&&p>=1024&&p<=65535);
  }catch{return [];}
}
