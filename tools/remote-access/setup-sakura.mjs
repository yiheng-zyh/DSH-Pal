import fs from 'node:fs/promises';
import path from 'node:path';
import {dataDirectory,loadSakuraToken} from './windows.mjs';
import {sakuraRequest,freeMainlandNodes} from './sakura.mjs';

// Explicit node choice; never silently switch providers, create paid resources,
// replace other tunnels, or retry an ambiguous POST.
const nodeId=Number(process.argv[2]);
if(!Number.isSafeInteger(nodeId)||nodeId<1)throw Error('Usage: node setup-sakura.mjs <free-mainland-node-id>');
const directory=dataDirectory();await fs.mkdir(directory,{recursive:true});
const token=await loadSakuraToken();
const nodes=freeMainlandNodes(await sakuraRequest(token,'/nodes'));
if(!nodes.some(n=>n.id===nodeId))throw Error('Selected node is not an available free mainland node');
const owner='DSHPal_remote_v1';
let tunnels=await sakuraRequest(token,'/tunnels');
let tunnel=tunnels.find(t=>t.name===owner);
if(tunnel&&(tunnel.node!==nodeId||tunnel.type!=='tcp'||tunnel.local_port!==18772||tunnel.local_ip!=='127.0.0.1'))
  throw Error('Existing owned tunnel differs; inspect it before changing anything');
let domain=await sakuraRequest(token,'/domain/get');
if(!domain.domain){
  if(!domain.acquire?.random)throw Error('No free random subdomain option returned');
  await sakuraRequest(token,'/domain/acquire',{method:'POST',form:{type:'random'}});
  domain=await sakuraRequest(token,'/domain/get');
}
if(!tunnel){
  await sakuraRequest(token,'/tunnels',{method:'POST',form:{
    name:owner,type:'tcp',node:String(nodeId),local_ip:'127.0.0.1',local_port:'18772',
    note:'DSH Pal private authenticated workspace',extra:'auto_https = auto\n'
  }});
  tunnels=await sakuraRequest(token,'/tunnels');tunnel=tunnels.find(t=>t.name===owner);
}
if(!tunnel)throw Error('Creation response was ambiguous; inspect the provider panel before rerunning');
const record=domain.words?.includes('www')?'www':null;
if(!record)throw Error('Provider did not offer the expected www record');
const existing=domain.records.find(r=>r.rr===record&&r.type==='CNAME');
if(existing&&existing.tunnel!==tunnel.id)throw Error('Existing domain record belongs to another tunnel');
if(!existing)await sakuraRequest(token,'/domain/bind',{method:'POST',form:{rr:record,tunnel:String(tunnel.id),domain:domain.domain,type:'CNAME'}});
const info={tunnelId:tunnel.id,nodeId,domain:`${record}.${domain.domain}`,remote:tunnel.remote,localPort:18772};
// No credential, certificate key, or pairing material is written here.
await fs.writeFile(path.join(directory,'sakura-provisioned.json'),JSON.stringify(info,null,2),{mode:0o600});
console.log(JSON.stringify(info));
