import net from 'node:net';
import path from 'node:path';

export function port(value,name){
  if(!Number.isInteger(value)||value<1024||value>65535)throw Error(`${name} must be an integer in 1024..65535`);
  return value;
}
export function origin(value){
  let url;try{url=new URL(value);}catch{throw Error('publicOrigin must be an HTTPS origin');}
  if(url.protocol!=='https:'||url.username||url.password||url.pathname!=='/'||url.search||url.hash)
    throw Error('publicOrigin must contain only https://host[:port]');
  if(url.hostname==='localhost'||url.hostname.endsWith('.localhost')||net.isIP(url.hostname))
    throw Error('publicOrigin must use the hostname covered by a trusted TLS certificate');
  return url.origin;
}
export function validateConfig(raw){
  if(!raw||raw.version!==1)throw Error('Expected remote-access configuration version 1');
  const value={version:1,publicOrigin:origin(raw.publicOrigin),gatewayPort:port(raw.gatewayPort??18772,'gatewayPort')};
  if(raw.dshPort!==undefined)value.dshPort=port(raw.dshPort,'dshPort');
  if(value.dshPort===value.gatewayPort)throw Error('Gateway and DSH ports must differ');
  if(!['sakura','external'].includes(raw.provider))throw Error('provider must be sakura or external');
  value.provider=raw.provider;
  if(raw.dshConfigPath!==undefined){
    if(typeof raw.dshConfigPath!=='string'||!path.isAbsolute(raw.dshConfigPath))throw Error('dshConfigPath must be absolute');
    value.dshConfigPath=raw.dshConfigPath;
  }
  if(raw.provider==='sakura'){
    if(typeof raw.frpcPath!=='string'||!raw.frpcPath.trim())throw Error('frpcPath is required');
    if(typeof raw.frpcSha256!=='string'||!/^[a-f0-9]{64}$/i.test(raw.frpcSha256))throw Error('frpcSha256 is required');
    if(!Number.isSafeInteger(raw.tunnelId)||raw.tunnelId<1)throw Error('tunnelId must be a positive integer');
    value.frpcPath=raw.frpcPath;value.frpcSha256=raw.frpcSha256.toLowerCase();value.tunnelId=raw.tunnelId;
  }
  for(const name of ['token','password','authtoken','secret'])if(Object.hasOwn(raw,name))throw Error('Secrets belong in the encrypted credential store, not configuration JSON');
  return value;
}
