import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {dataDirectory} from './windows.mjs';

export async function installClient(){
  if(process.arch!=='x64')throw Error('Automatic client installation currently supports Windows x64');
  const r=await fetch('https://api.natfrp.com/v4/system/clients',{signal:AbortSignal.timeout(20000),redirect:'error'});
  if(!r.ok)throw Error('Could not read official client metadata');
  const metadata=(await r.json()).frpc;
  if(!/^[0-9a-z.-]+$/i.test(metadata?.ver))throw Error('Invalid official version metadata');
  const entry=metadata.archs?.windows_amd64;
  const url=new URL(entry?.url);
  if(url.protocol!=='https:'||url.hostname!=='nya.globalslb.net'||!url.pathname.startsWith('/natfrp/client/frpc/')||
    !Number.isSafeInteger(entry.size)||entry.size<1000000||entry.size>100000000||!/^[a-f0-9]{32}$/i.test(entry.hash))
    throw Error('Unexpected official Windows client metadata');
  const directory=path.join(dataDirectory(),'bin');await fs.mkdir(directory,{recursive:true});
  const file=path.join(directory,`frpc-${metadata.ver}.exe`);
  let bytes;try{bytes=await fs.readFile(file);}catch(e){if(e.code!=='ENOENT')throw e;}
  let fresh=false;
  if(!bytes){
    const response=await fetch(url,{signal:AbortSignal.timeout(90000),redirect:'error'});
    if(!response.ok)throw Error('Official client download failed');
    const chunks=[];let size=0;
    for await(const chunk of response.body){size+=chunk.length;if(size>entry.size)throw Error('Client exceeds declared size');chunks.push(chunk);}
    bytes=Buffer.concat(chunks);fresh=true;
  }
  // Upstream publishes MD5 for accidental corruption; it is not a signature.
  if(bytes.length!==entry.size||createHash('md5').update(bytes).digest('hex')!==entry.hash.toLowerCase())
    throw Error('Client download differs from upstream size/checksum; existing file was not overwritten');
  const sha256=createHash('sha256').update(bytes).digest('hex');
  if(fresh)await fs.writeFile(file,bytes,{flag:'wx'});
  return {path:file,sha256,version:metadata.ver};
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))
  console.log(JSON.stringify(await installClient()));
