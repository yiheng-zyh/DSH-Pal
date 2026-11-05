import fs from 'node:fs/promises';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {dataDirectory} from './windows.mjs';
import {validateConfig} from './config.mjs';
import {advertisableAddresses,planDirectEntries} from './direct.mjs';
import {planTrustedHost} from './trust.mjs';

const execute=promisify(execFile);

/**
 * 直连入口（IPv6 / 局域网）的开关与自检。
 *
 * 设计原则：
 *  - 幂等：重复运行结果相同；--off 之后配置里没有残留开关。
 *  - 失败安全：只做「写 config.json 的 direct 段 + 只读自检报告」。不碰
 *    cordis.patch.yml 的 webserver（红线 1），不杀进程，不动 frpc/隧道。
 *  - 不让用户重启：网关每 5 秒热重载 direct 段，写完配置即生效。
 *  - 不谎报成功：绑定是否真的起来，要看 state.json 里 direct[].listening。
 *
 * 开关语义：命令行给出的就是完整期望集合，未提及的关闭。所以 `--ipv6` 只开
 * IPv6（会关掉之前可能开着的 --lan），`--ipv6 --lan` 两个都开。这样看命令
 * 就知道结果，也不会累积出用户不清楚的状态。
 *
 * 用法：
 *   node configure-direct.mjs --ipv6          只开 IPv6 直连
 *   node configure-direct.mjs --lan           只开局域网直连
 *   node configure-direct.mjs --ipv6 --lan    两个都开（推荐）
 *   node configure-direct.mjs --off           关闭（恢复只绑回环）
 *   node configure-direct.mjs --status        只看当前状态，不改任何文件
 */

const args=new Set(process.argv.slice(2));
const wantIPv6=args.has('--ipv6');
const wantLAN=args.has('--lan');
const wantOff=args.has('--off');
const statusOnly=args.has('--status');
if(wantOff&&(wantIPv6||wantLAN))throw Error('--off cannot be combined with --ipv6/--lan');
if(!statusOnly&&!wantOff&&!wantIPv6&&!wantLAN)
  throw Error('Usage: node configure-direct.mjs [--ipv6] [--lan] | --off | --status');

const directory=dataDirectory();
const configFile=path.join(directory,'config.json');
const config=validateConfig(JSON.parse(await fs.readFile(configFile,'utf8')));

/** 只读的 Windows 防火墙检查：报告状态，绝不自动创建宽泛规则。 */
async function firewallReport(){
  if(process.platform!=='win32')return {checked:false,note:'non-Windows: check your own firewall'};
  const command="$ErrorActionPreference='SilentlyContinue';"+
    "$p=(Get-NetFirewallApplicationFilter -Program '*node.exe*' | Get-NetFirewallRule | Where-Object {$_.Direction -eq 'Inbound' -and $_.Action -eq 'Allow' -and $_.Enabled -eq 'True'});"+
    "if($p){'allow-rule-present'}else{'no-node-allow-rule'}";
  try{
    const {stdout}=await execute('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{windowsHide:true,timeout:20000,maxBuffer:16384});
    return {checked:true,verdict:stdout.trim()||'unknown'};
  }catch{
    return {checked:false,note:'firewall query failed; verify manually'};
  }
}

/** 读网关自己写的 state.json，报告直连监听器的真实结果（不是我们的期望）。 */
async function liveDirectState(){
  try{
    const state=JSON.parse(await fs.readFile(path.join(directory,'state.json'),'utf8'));
    return Array.isArray(state.direct)?state.direct:[];
  }catch{return [];}
}

const addresses=advertisableAddresses();
// --status 报告当前配置；开启/关闭按命令行的完整期望集合规划。
const effectiveIPv6=statusOnly?config.direct?.ipv6===true:wantIPv6;
const effectiveLAN=statusOnly?config.direct?.lan===true:wantLAN;
const plan=planDirectEntries({
  enableIPv6:effectiveIPv6,
  enableLAN:effectiveLAN,
  port:config.gatewayPort,
  ipv6:addresses.ipv6,
  ipv4:addresses.ipv4,
});

if(statusOnly||wantOff){
  if(!statusOnly){
    // 关闭：清掉 direct 段，网关下一轮热重载后解绑直连监听器。
    const raw=JSON.parse(await fs.readFile(configFile,'utf8'));
    delete raw.direct;
    const temporary=configFile+'.dshpal-direct.tmp';
    await fs.writeFile(temporary,JSON.stringify(raw,null,2));
    await fs.rename(temporary,configFile);
    console.log(JSON.stringify({direct:'off',note:'gateway unbinds the direct listeners within ~5s; loopback and the tunnel are untouched'}));
  }else{
    const live=await liveDirectState();
    const firewall=await firewallReport();
    console.log(JSON.stringify({
      direct:config.direct??{enabled:false,ipv6:false,lan:false},
      planned:plan.entries.map(entry=>({kind:entry.kind,address:entry.address,port:entry.port,origin:entry.origin})),
      missing:plan.reasons,
      live:live.map(item=>({kind:item.kind,address:item.address,port:item.port,listening:item.listening,error:item.error??null,origin:item.origin})),
      firewall,
    },null,2));
  }
  process.exit(0);
}

// 开启：先算出将公布的入口，让用户在写配置前就看到地址（尤其 IPv6 很长）。
const backup=Buffer.from(await fs.readFile(configFile));
const raw=JSON.parse(backup.toString('utf8'));
raw.direct={enabled:true,ipv6:wantIPv6,lan:wantLAN};
const next=validateConfig(raw);
const temporary=configFile+'.dshpal-direct.tmp';
await fs.writeFile(temporary,JSON.stringify(next,null,2));
// 写入前确认文件没被并发改动（网关也在读它）。
if(!(await fs.readFile(configFile)).equals(backup))throw Error('config.json changed concurrently; no write performed');
await fs.rename(temporary,configFile);

// trustedHosts 预检：不写文件，只报告将要登记什么。实际写入由网关周期同步完成
// （它拥有并发保护与 DPAPI 加密备份），这里避免两处同时写同一个 YAML。
let trustedPreview=null;
if(config.dshConfigPath){
  try{
    const source=await fs.readFile(config.dshConfigPath,'utf8');
    trustedPreview=planTrustedHost(source,config.publicOrigin,plan.entries.map(entry=>entry.origin));
  }catch(error){trustedPreview={error:error instanceof Error?error.message:String(error)};}
}

const firewall=await firewallReport();
console.log(JSON.stringify({
  direct:next.direct,
  entries:plan.entries.map(entry=>({kind:entry.kind,address:entry.address,port:entry.port,origin:entry.origin,trustedHost:entry.trustedHost})),
  missing:plan.reasons,
  trustedHostsWillRegister:trustedPreview?.added??null,
  trustedHostsChanged:trustedPreview?.changed??null,
  firewall,
  next:'gateway picks this up within ~5s; verify with --status (direct[].listening must be true), then run show-pair.mjs --via ipv6|lan for a QR code',
},null,2));
