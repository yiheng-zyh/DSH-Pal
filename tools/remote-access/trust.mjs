import fs from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import YAML,{isMap,isSeq,Scalar,YAMLSeq} from 'yaml';
import {origin} from './config.mjs';
import {backupEncrypted} from './windows.mjs';

const marker='DSH Pal remote-access managed';
export function remotePluginConfig(doc){
  const candidates=[];
  function visit(node){
    if(isMap(node)){
      if(node.get('name')==='@linxin666/dsh-remote-web-ui'||node.get('id')==='remote-web-ui'){
        const config=node.get('config');if(isMap(config))candidates.push(config);
      }
      for(const pair of node.items)visit(pair.value);
    }else if(isSeq(node))for(const child of node.items)visit(child);
  }
  visit(doc.contents);
  if(candidates.length!==1)throw Error('Expected exactly one remote-web-ui plugin; no configuration changed');
  return candidates[0];
}
export function planTrustedHost(source,publicOrigin){
  const url=new URL(origin(publicOrigin));
  const doc=YAML.parseDocument(source,{keepSourceTokens:true});
  if(doc.errors.length)throw Error('DSH configuration is not valid YAML');
  const config=remotePluginConfig(doc);let hosts=config.get('trustedHosts');
  if(hosts===undefined){hosts=new YAMLSeq();config.set('trustedHosts',hosts);}
  if(!isSeq(hosts)||hosts.items.some(item=>!(item instanceof Scalar)||typeof item.value!=='string'))
    throw Error('trustedHosts must be an explicit list of hostnames');
  const wanted=[...new Set([url.hostname,url.host])];
  // Only replace entries previously created by this tool. User entries remain.
  const previous=hosts.items.map(item=>({value:item.value,comment:item.comment}));
  hosts.items=hosts.items.filter(item=>item.comment?.trim()!==marker);
  for(const host of wanted){
    if(hosts.items.some(item=>item.value===host))continue;
    const item=new Scalar(host);item.comment=marker;hosts.items.push(item);
  }
  const next=hosts.items.map(item=>({value:item.value,comment:item.comment}));
  if(JSON.stringify(previous)===JSON.stringify(next))return {changed:false,text:source};
  return {changed:true,text:doc.toString({lineWidth:0}),added:wanted};
}
export async function applyTrustedHost(file,publicOrigin,{backup=backupEncrypted}={}){
  const bytes=await fs.readFile(file),source=bytes.toString('utf8');
  const plan=planTrustedHost(source,publicOrigin);if(!plan.changed)return {changed:false};
  const backupFile=await backup(bytes);
  if(!(await fs.readFile(file)).equals(bytes))throw Error('Configuration changed concurrently; encrypted backup retained, no write performed');
  const temporary=file+`.dshpal-${randomUUID()}.tmp`;
  try{
    await fs.writeFile(temporary,plan.text,{flag:'wx',mode:0o600});
    if(!(await fs.readFile(file)).equals(bytes))throw Error('Configuration changed concurrently; no write performed');
    await fs.rename(temporary,file);
  }finally{await fs.unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
  return {changed:true,backupFile};
}
