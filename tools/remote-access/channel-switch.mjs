import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {dataDirectory,backupEncrypted} from './windows.mjs';
import {validateConfig} from './config.mjs';
import {planChannelSwitch,currentChannel,Channel} from './channel-plan.mjs';

/**
 * 一键切换公网入口通道（国内 frp ⇄ Cloudflare）。
 *
 * 用法：
 *   node channel-switch.mjs --status
 *   node channel-switch.mjs --to cloudflare [--apply]
 *   node channel-switch.mjs --to domestic [--apply]
 *   node channel-switch.mjs --to domestic --origin https://xxx [--apply]
 *
 * 安全设计（对齐仓库既有做法）：
 *  - 默认 dry-run，必须显式 --apply 才写；
 *  - 写前 DPAPI 加密备份原文件；
 *  - 原子写（临时文件 + rename，先 wx 创建避免竞态）；
 *  - 写前再比对一次内容，检测并发修改；
 *  - 只改通道语义相关字段，不碰 trustedHosts / webserver host / port 等。
 */
const args=process.argv.slice(2);
const known=new Set(['--status','--to','--origin','--apply','--dry-run']);
for(let i=0;i<args.length;i++){
  const a=args[i];
  if(!known.has(a))throw Error(`Unknown argument: ${a}`);
  if(a==='--to'||a==='--origin')i++; // 消费其取值
}
const apply=args.includes('--apply');
const statusOnly=args.includes('--status');
const valueOf=flag=>{
  const i=args.indexOf(flag);
  return i>=0?args[i+1]:undefined;
};

const directory=dataDirectory();
const config=validateConfig(JSON.parse(await fs.readFile(path.join(directory,'config.json'),'utf8')));
if(!config.dshConfigPath)throw Error('Configure the DSH profile path first');

// 与 integrate-plugin.mjs 一致：新版只有 cordis.patch.yml，旧版可能还有 settings.yaml。
const profileDir=path.dirname(config.dshConfigPath);
const settingsPath=path.join(profileDir,'settings.yaml');
const candidates=[{file:config.dshConfigPath,settings:false}];
try{await fs.access(settingsPath);candidates.unshift({file:settingsPath,settings:true});}catch{}

const files=[];
for(const candidate of candidates){
  let bytes;
  try{bytes=await fs.readFile(candidate.file);}catch(error){
    if(error.code==='ENOENT')continue;
    throw error;
  }
  files.push({...candidate,bytes});
}
if(files.length===0)throw Error('No writable plugin configuration found; check dshConfigPath');

// 当前通道（以 profile patch 为准，它优先级更高）
const current=files.map(e=>({file:e.file,settings:e.settings,...currentChannel(e.bytes.toString('utf8'),{settings:e.settings})}));

if(statusOnly){
  console.log(JSON.stringify({
    channels:current,
    domesticOrigin:config.publicOrigin,
    note:'通道由插件 autoTunnel 开关决定；Cloudflare 的 cloudflared 由插件自行准备',
  },null,2));
  process.exit(0);
}

const target=valueOf('--to');
if(!target)throw Error('Usage: node channel-switch.mjs --to <domestic|cloudflare> [--apply]  |  --status');

/**
 * 切到国内通道时必须先确认自己的入口**真的可用**。
 * 否则会把二维码指向一个连不上的地址，用户从此扫不出可用入口 —— 比不切更糟。
 */
if(target===Channel.DOMESTIC){
  const probeOrigin=valueOf('--origin')??config.publicOrigin;
  let ok=false,detail='';
  try{
    // **必须显式声明 Accept-Encoding: identity**。
    // 默认 fetch 会带 "gzip, deflate, br"，而经 Sakura 中转回来的压缩流可能损坏，
    // Node 解压抛 Z_DATA_ERROR（真机 2026-11-04 实测：curl 200，fetch 却 terminated）。
    // 不要压缩就绕开了中转对 body 的破坏，预检结果也更贴近 App 的真实请求。
    const response=await fetch(probeOrigin+'/api/pair/status',{
      signal:AbortSignal.timeout(15000),
      redirect:'error',
      headers:{'accept-encoding':'identity'},
    });
    if(!response.ok)detail=`HTTP ${response.status}`;
    else{
      const status=await response.json();
      ok=status?.ok===true&&typeof status.paired==='boolean'&&typeof status.phase==='string';
      if(!ok)detail='入口未返回配对协议';
    }
  }catch(error){
    detail=String(error.cause?.code||error.cause?.message||error.message||'preflight failed');
  }
  if(!ok)throw Error(`国内入口预检失败（${detail}）；为避免把二维码指向不可用地址，本次不切换。先修好隧道再切：node show-pair.mjs`);
}

/**
 * 切到 Cloudflare 时，我们**不知道**插件会把 Quick 域名填成什么。
 * 允许两种情形：
 *  ① 已经观测到 CF 地址（--origin 传入）→ 一并写入；
 *  ② 未知 → 只翻 autoTunnel，让插件自己填 publicBaseUrl。
 * 情形 ② 之后必须重启/等待插件起隧道，再读回真实地址。
 */
const cloudflareOrigin=valueOf('--origin')??'';

const plans=files.map(entry=>({
  ...entry,
  plan:planChannelSwitch(entry.bytes.toString('utf8'),{
    settings:entry.settings,
    channel:target,
    domesticOrigin:valueOf('--origin')??config.publicOrigin,
    cloudflareOrigin,
  }),
}));

console.log(JSON.stringify({
  apply,
  target,
  changed:plans.map(p=>({file:p.file,settings:p.settings,fields:p.plan.fields})),
  current,
  cloudflareOriginKnown:target===Channel.CLOUDFLARE?(cloudflareOrigin!==''):null,
  notes:target===Channel.CLOUDFLARE
    ? ['插件将自行启动 cloudflared 并填充 publicBaseUrl','切换后需等待 ~20s 再读新入口；Quick 域名重启会变']
    : ['已确认国内入口可用','插件自带的 Cloudflare 隧道将被关闭'],
},null,2));

if(!apply){console.log('\n[dry-run] 未写入。确认无误后加 --apply。');process.exit(0);}

// 备份 → 并发校验 → 原子写
for(const entry of plans)if(entry.plan.changed)await backupEncrypted(entry.bytes);
for(const entry of plans)if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error('Configuration changed concurrently; nothing written');
let written=0;
for(const entry of plans){
  if(!entry.plan.changed)continue;
  if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error(`Concurrent configuration change; ${written} file(s) already updated`);
  const temporary=entry.file+'.dshpal-'+randomUUID()+'.tmp';
  try{
    await fs.writeFile(temporary,entry.plan.text,{flag:'wx',mode:0o600});
    await fs.rename(temporary,entry.file);
    written++;
  }finally{await fs.unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
}
console.log(JSON.stringify({updatedFiles:written,encryptedBackups:true,requiresRuntimeVerification:true}));
