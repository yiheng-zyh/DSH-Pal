import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {dataDirectory,backupEncrypted} from './windows.mjs';
import {validateConfig} from './config.mjs';
import {planPluginPrimary} from './plugin-primary.mjs';

const apply=process.argv.includes('--apply');
if(process.argv.slice(2).some(arg=>!['--apply','--dry-run'].includes(arg)))throw Error('Usage: node integrate-plugin.mjs [--dry-run|--apply]');
const config=validateConfig(JSON.parse(await fs.readFile(path.join(dataDirectory(),'config.json'),'utf8')));
if(!config.dshConfigPath)throw Error('Configure the DSH profile path first');
// A valid certificate and an actual public pairing surface must exist before
// retiring the previous provider. HTTP 200 at an unrelated page is not enough.
const response=await fetch(config.publicOrigin+'/api/pair/status',{signal:AbortSignal.timeout(15000),redirect:'error'});
if(!response.ok)throw Error(`Domestic pairing preflight failed: HTTP ${response.status}`);
const status=await response.json();
if(status.ok!==true||typeof status.paired!=='boolean'||typeof status.phase!=='string')throw Error('Domestic endpoint did not return the pairing protocol');
const files=[{file:path.join(path.dirname(config.dshConfigPath),'settings.yaml'),settings:true},{file:config.dshConfigPath,settings:false}];
for(const entry of files){
  entry.bytes=await fs.readFile(entry.file);
  entry.plan=planPluginPrimary(entry.bytes.toString('utf8'),config.publicOrigin,{settings:entry.settings});
}
console.log(JSON.stringify({apply,files:files.map(e=>({kind:e.settings?'plugin settings':'profile patch',changed:e.plan.changed,fields:e.plan.fields})),
  effects:['publish domestic HTTPS as official pairing origin','stop plugin-owned Cloudflare and Relay transports','retain device pairings and other plugins']}));
if(apply){
  for(const entry of files)if(entry.plan.changed)await backupEncrypted(entry.bytes);
  for(const entry of files)if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error('Configuration changed concurrently; nothing written');
  let written=0;
  for(const entry of files){
    if(!entry.plan.changed)continue;
    if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error(`Concurrent configuration change; ${written} file(s) already updated; inspect before retrying`);
    const temporary=entry.file+'.dshpal-'+randomUUID()+'.tmp';
    try{await fs.writeFile(temporary,entry.plan.text,{flag:'wx',mode:0o600});await fs.rename(temporary,entry.file);written++;}
    finally{await fs.unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
  }
  console.log(JSON.stringify({updatedFiles:written,encryptedBackups:true,requiresRuntimeVerification:true}));
}
