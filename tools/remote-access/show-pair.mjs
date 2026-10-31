import fs from 'node:fs/promises';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import QRCode from 'qrcode';
import {dataDirectory} from './windows.mjs';
import {validateConfig} from './config.mjs';
import {findDshPort} from './discovery.mjs';

const directory=dataDirectory();
const config=validateConfig(JSON.parse(await fs.readFile(path.join(directory,'config.json'),'utf8')));
const targetPort=await findDshPort({preferred:config.dshPort});
if(!targetPort)throw Error('DSH pairing service is not running');
// Only the real loopback control plane issues a pairing token. The public
// gateway cannot issue, list, revoke or read pairing credentials.
const r=await fetch(`http://127.0.0.1:${targetPort}/api/pair/issue`,{
  method:'POST',headers:{'Content-Type':'application/json'},body:'{}',signal:AbortSignal.timeout(10000)
});
if(!r.ok)throw Error(`Local pairing issuance refused: HTTP ${r.status}`);
const issued=await r.json();
if(!issued.ok||typeof issued.token!=='string'||!/^[a-zA-Z0-9_-]{16,256}$/.test(issued.token))throw Error('Invalid pairing issuance response');
const url=config.publicOrigin+'/pair-accept?pair='+encodeURIComponent(issued.token);
const png=await QRCode.toBuffer(url,{type:'png',width:420,margin:3,errorCorrectionLevel:'M'});
const ui=fileURLToPath(new URL('./pair-window.ps1',import.meta.url));
const child=spawn('powershell.exe',['-NoProfile','-STA','-File',ui],{windowsHide:false,stdio:['pipe','ignore','ignore']});
child.on('error',()=>{console.error('Pairing window could not open');process.exitCode=1;});
child.stdin.on('error',()=>{});
// Neither command line nor file contains the pairing token.
child.stdin.end(JSON.stringify({png:png.toString('base64'),origin:config.publicOrigin,expiresAt:issued.expiresAt}));
child.on('exit',code=>{if(code)process.exitCode=1;});
