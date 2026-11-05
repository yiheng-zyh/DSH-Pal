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
/**
 * 二维码指向哪个入口。默认（无 --via）是隧道 publicOrigin，与改动前一致。
 * --via ipv6 / --via lan 指向直连入口：地址从网关的 state.json 读**实际绑定成功**
 * 的那一条，而不是重新枚举网卡 —— 绑定失败的地址生成二维码只会让用户扫了连不上。
 */
const args=process.argv.slice(2);
const viaIndex=args.indexOf('--via');
const via=viaIndex>=0?args[viaIndex+1]:undefined;
/** 只输出配对链接与深链后退出，不打开二维码窗口。供自动化与教程使用。 */
const printUrl=args.includes('--print-url');
/**
 * 多网卡时指定用哪个直连地址。一台电脑常有多个局域网地址（自己的热点接口、
 * 上游网段接口），PC 无法知道手机在哪个网段，所以把选择权交给调用方：
 * `--via lan --address 172.31.144.190`。不指定时用网关实际绑定的第一个。
 */
const addressIndex=args.indexOf('--address');
const wantedAddress=addressIndex>=0?args[addressIndex+1]:undefined;
let entryOrigin=config.publicOrigin;
if(via!==undefined){
  if(!['ipv6','lan'].includes(via))throw Error('--via must be ipv6 or lan');
  let live=[];
  try{
    const state=JSON.parse(await fs.readFile(path.join(directory,'state.json'),'utf8'));
    live=Array.isArray(state.direct)?state.direct:[];
  }catch{live=[];}
  const candidates=live.filter(item=>item.listening===true&&item.kind===via);
  if(candidates.length===0)throw Error(`No listening ${via} direct entry. Run: node configure-direct.mjs --${via==='ipv6'?'ipv6':'lan'} and wait ~5s, then retry.`);
  const bare=value=>String(value).replace(/^\[/,'').replace(/\]$/,'').split('%')[0].toLowerCase();
  const entry=wantedAddress===undefined?candidates[0]:candidates.find(item=>bare(item.address)===bare(wantedAddress));
  if(!entry)throw Error(`No listening ${via} entry matches --address ${wantedAddress}. Available: ${candidates.map(item=>item.address).join(', ')}`);
  entryOrigin=entry.origin;
}
// Only the real loopback control plane issues a pairing token. The public
// gateway cannot issue, list, revoke or read pairing credentials.
const r=await fetch(`http://127.0.0.1:${targetPort}/api/pair/issue`,{
  method:'POST',headers:{'Content-Type':'application/json'},body:'{}',signal:AbortSignal.timeout(10000)
});
if(!r.ok)throw Error(`Local pairing issuance refused: HTTP ${r.status}`);
const issued=await r.json();
if(!issued.ok||typeof issued.token!=='string'||!/^[a-zA-Z0-9_-]{16,256}$/.test(issued.token))throw Error('Invalid pairing issuance response');
const url=entryOrigin+'/pair-accept?pair='+encodeURIComponent(issued.token);
if(printUrl){
  // 深链让手机 App 直接接管配对（WebView 的 Cookie 与系统浏览器不共享，
  // 用浏览器打开配对页不会把凭据给到 App）。token 在有效期内可重复 accept。
  const deep='dshmobile://open?url='+encodeURIComponent(url);
  console.log(JSON.stringify({origin:entryOrigin,url,deepLink:deep,expiresAt:issued.expiresAt}));
  process.exit(0);
}
const png=await QRCode.toBuffer(url,{type:'png',width:420,margin:3,errorCorrectionLevel:'M'});
const ui=fileURLToPath(new URL('./pair-window.ps1',import.meta.url));
const child=spawn('powershell.exe',['-NoProfile','-STA','-File',ui],{windowsHide:false,stdio:['pipe','ignore','ignore']});
child.on('error',()=>{console.error('Pairing window could not open');process.exitCode=1;});
child.stdin.on('error',()=>{});
// Neither command line nor file contains the pairing token.
child.stdin.end(JSON.stringify({png:png.toString('base64'),origin:entryOrigin,expiresAt:issued.expiresAt}));
child.on('exit',code=>{if(code)process.exitCode=1;});
