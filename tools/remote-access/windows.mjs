import path from 'node:path';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {spawn} from 'node:child_process';
import {randomUUID} from 'node:crypto';

export function dataDirectory(){
  if(process.platform!=='win32'||!process.env.LOCALAPPDATA)throw Error('This launcher currently requires Windows; the gateway itself is portable');
  return path.join(process.env.LOCALAPPDATA,'DSHPal','remote-access');
}
const helper=fileURLToPath(new URL('./secure-store.ps1',import.meta.url));
function protectCommand(action,destination,input){
  return new Promise((resolve,reject)=>{
    const child=spawn('powershell.exe',['-NoProfile','-NonInteractive','-File',helper,'-Action',action,'-Path',destination],{windowsHide:true,stdio:['pipe','pipe','pipe']});
    const parts=[];let size=0;
    child.stdout.on('data',bytes=>{size+=bytes.length;if(size>4096){child.kill();return;}parts.push(bytes);});
    // Do not expose child output in errors: a credential read returns sensitive stdout.
    child.stderr.resume();child.once('error',()=>reject(Error('Windows credential helper could not start')));
    child.once('exit',code=>code===0&&size<=4096?resolve(Buffer.concat(parts)):reject(Error('Windows credential operation failed')));
    child.stdin.on('error',()=>{});child.stdin.end(input);
  });
}
export async function loadSakuraToken(directory=dataDirectory()){
  const file=path.join(directory,'sakura-token.dpapi');await fs.access(file);
  const bytes=await protectCommand('ReadToken',file);
  try{
    const token=bytes.toString('utf8').trim();
    if(!/^[A-Za-z0-9_.-]{8,256}$/.test(token))throw Error('Stored Sakura credential has an invalid format');
    return token;
  }finally{bytes.fill(0);}
}
export async function backupEncrypted(bytes,directory=dataDirectory()){
  await fs.mkdir(path.join(directory,'backups'),{recursive:true});
  const file=path.join(directory,'backups',`dsh-config-${Date.now()}-${randomUUID()}.dpapi`);
  await protectCommand('ProtectBytes',file,bytes);return file;
}
