import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {validateConfig} from './config.mjs';
import {dataDirectory,loadSakuraToken} from './windows.mjs';
import {findDshPort} from './discovery.mjs';
import {createGateway} from './gateway.mjs';
import {applyTrustedHost} from './trust.mjs';
import {advertisableAddresses,planDirectEntries} from './direct.mjs';
import tls from 'node:tls';

const directory=dataDirectory();
const config=validateConfig(JSON.parse(await fs.readFile(path.join(directory,'config.json'),'utf8')));
let targetPort=null,child=null,stopping=false,failures=0,restartAt=0,clientState='not-started';
// 隧道客户端被服务端拒绝时的脱敏原因（如「[400] frpc 版本号无效」）。
// 没有它，用户只能看到 running-unverified，无法判断隧道到底有没有在工作。
let clientReason='';
// 外部入口的**实测**可用性。frpc 报告 "隧道启动成功" 只证明它连上了节点，
// 不证明 publicOrigin 真能握手：auto_https 没生效时服务端只回 501 明文，
// 浏览器侧就是 ERR_SSL_PROTOCOL_ERROR（2026-11-04 两次复现）。
// 所以必须自己探一次 TLS，把真实结果报给 App。
let publicProbe={ok:null,detail:'',at:0};
let token;
let lastTrustCheck=0;
let lastTrustKey='';
let lastDirectKey='';
// Reuse only a previously recorded port as a discovery hint, never as a
// trusted target. findDshPort fingerprints it again before forwarding.
let preferredPort=config.dshPort;
if(preferredPort===undefined){
  try{
    const previous=JSON.parse(await fs.readFile(path.join(directory,'state.json'),'utf8'));
    if(previous.publicOrigin===config.publicOrigin&&Number.isInteger(previous.targetPort)&&previous.targetPort>=1024&&previous.targetPort<=65535)
      preferredPort=previous.targetPort;
  }catch{}
}
if(config.provider==='sakura'){
  const digest=createHash('sha256').update(await fs.readFile(config.frpcPath)).digest('hex');
  if(digest!==config.frpcSha256)throw Error('frpc executable changed; verify it before starting');
  token=await loadSakuraToken(directory);
}
// 注意：getDirectEntries 的闭包里**不要**直接写 gateway.liveDirectEntries()。
// 该闭包在 createGateway 求值时就会被引用，而那时 gateway 还没赋值 ——
// 任何提前求值都会撞上 TDZ（ReferenceError），表现为"进程在跑但端口没监听"。
// 用 let 声明 + 延迟到实际调用时才读 gateway，是安全的。
let gatewayRef=null;
const gateway=createGateway({publicOrigin:config.publicOrigin,getTargetPort:()=>targetPort,
  getDirectEntries:()=>(gatewayRef?gatewayRef.liveDirectEntries():[]).filter(entry=>entry.listening),
  // 把隧道客户端的真实状态交给网关，用于 /api/pair/status 的诊断注入。
  // 用户在 App 里就能看到「隧道被拒绝 + 原因」，不必再靠命令行排查。
  getTunnelState:()=>({clientState,clientReason,provider:config.provider,
    // 实测的入口可用性：frpc 报"成功"但 TLS 不通时，这里会说 false + 原因。
    publicOk:publicProbe.ok,publicDetail:publicProbe.detail})});
// Bind first: duplicate launch fails before creating any provider client.
// 先接上引用再绑定端口：网关内部读直连清单时依赖 gatewayRef 已就绪。
gatewayRef=gateway;
await gateway.listen(config.gatewayPort);
console.log('Loopback gateway started; this does not establish business readiness.');
function startClient(){
  if(config.provider!=='sakura'||child||stopping)return;
  const env={...process.env,NATFRP_TOKEN:token,NATFRP_TARGET:String(config.tunnelId)};
  for(const key of Object.keys(env))if(/^(https?|all|no)_proxy$/i.test(key))delete env[key];
  child=spawn(config.frpcPath,['--proxy','none','--disable_log_color','--no_check_update','--watch',String(process.pid)],{
    env,cwd:directory,windowsHide:true,stdio:['ignore','pipe','pipe']
  });
  clientState='running-unverified';const began=Date.now();
  // 隧道是否曾成功启动过。用于区分「从未成功」和「成功过又报错」：
  // frpc 只在启动时输出一次 "start proxy success"，之后网络抖动可能刷出 error
  // 字样再自行重连，若让 error 无条件覆盖，状态会永久钉死在 client-reported-error，
  // 而进程其实一直在正常转发（2026-11-03 实测：frpc 活了 16 小时、网关监听正常，
  // state.json 却报 error）。真正的致命失败走 exit 分支的 waiting-to-retry。
  let tunnelStarted=false;
  // The upstream client may log its access token. Never persist raw client output.
  for(const stream of [child.stdout,child.stderr])stream.on('data',bytes=>{
    const text=bytes.toString('utf8');
    if(/start proxy success/i.test(text)){clientState='tunnel-started-unverified';tunnelStarted=true;}
    else if(/certificate.*(pending|issuing)|正在签发/i.test(text))clientState='certificate-pending';
    // frpc 被服务端拒绝时会直接打印形如 "[400] frpc 版本号无效" 的一行就退出，
    // 里面既没有 error 也没有 failed 字样 —— 早先的匹配规则完全漏掉这类致命拒绝，
    // 状态会一直停在 running-unverified，用户看不出隧道其实没工作。
    // 这里把「[4xx]/[5xx] + 任意原因」以及常见的拒绝措辞都识别为致命错误。
    // 注意必须排在通用 error 之前：拒绝信息里常同时带 error 字样，先匹配会被误判成普通抖动。
    else if(/^\s*\[\d{3}\]/m.test(text)||/版本号无效|invalid (client )?version|token .*(invalid|expired)|unauthori[sz]ed|forbidden/i.test(text)){
      clientState=tunnelStarted?'tunnel-degraded':'client-rejected';
      // 只留一行脱敏后的原因，供界面与诊断使用；绝不落地原始输出（可能含 token）。
      const line=text.split('\n').map(s=>s.trim()).filter(Boolean)[0]||'';
      clientReason=line.replace(/[A-Za-z0-9]{24,}/g,'***').slice(0,200);
    }
    // 最后才是通用 error/failed。但**正在重连中的抖动不要覆盖状态**：
    // frpc 的 --watch 会自行重连，日志里常有 "connection reset, retrying" 这类行，
    // 那时隧道实际在转发（2026-11-04 实测：requests 2.5s 涨了 52 次，状态却是
    // client-reported-error）。把"有实测流量"当更强的证据，日志只作参考。
    else if(/error|failed/i.test(text)){
      if(tunnelStarted||publicProbe.ok===true)clientState='tunnel-started-unverified';
      else clientState='client-reported-error';
    }
  });
  child.once('error',()=>{clientState='spawn-failed';});
  child.once('exit',code=>{
    child=null;if(stopping)return;
    failures=Date.now()-began>120000?0:failures+1;
    restartAt=Date.now()+Math.min(60000,2000*2**Math.min(failures,5));
    // 保留已判定出的致命原因（如服务端拒绝），不要用通用状态盖掉它 ——
    // 否则界面上只剩 waiting-to-retry，用户不知道是"网络抖"还是"被服务端拒绝"。
    if(clientState!=='client-rejected')clientState='waiting-to-retry';
    if(code!==0&&!clientReason)clientReason=`客户端退出，code=${code}`;
  });
}
/**
 * 探测 publicOrigin 是否真能完成 TLS 握手。
 *
 * 只做握手就断开：不发送业务请求，不消耗配对授予，也不触发认证流程。
 * 结果用于告诉用户"外部入口到底能不能用"，而不是让 frpc 的"启动成功"误导人。
 * @returns {ok:boolean|null, detail:string}  null 表示还没探过
 */
function probePublicOrigin(){
  let host, port;
  try{
    const url=new URL(config.publicOrigin);
    host=url.hostname;
    port=Number(url.port||443);
  }catch{ return Promise.resolve({ok:false,detail:'入口地址无效'}); }
  return new Promise(resolve=>{
    let settled=false;
    // 先声明再连接：finish 里会引用 socket，早先把它写在 connect 之后，
    // 同步错误路径会撞上 TDZ（ReferenceError）。
    let socket;
    const finish=result=>{ if(settled)return; settled=true; try{socket?.destroy();}catch{} resolve(result); };
    socket=tls.connect({host,port,servername:host,rejectUnauthorized:false,timeout:10000},()=>{
      finish({ok:true,detail:''});
    });
    socket.on('error',e=>{
      // 典型：ERR_SSL_WRONG_VERSION_NUMBER = 对端说的是明文 HTTP，隧道 TLS 没起来
      finish({ok:false,detail:String(e.code||e.message||'TLS 失败').slice(0,120)});
    });
    socket.on('timeout',()=>finish({ok:false,detail:'TLS 握手超时'}));
  });
}

async function update(){
  // 主动探测外部入口的 TLS 可用性（每 30 秒一次，失败不影响其它逻辑）。
  // 为什么要自己探：frpc 只保证"连上节点"，auto_https 没生效时服务端仍只回
  // 明文 501，浏览器侧表现为 ERR_SSL_PROTOCOL_ERROR（2026-11-04 两次复现）。
  // 不探这一下，网关就只能看着 frpc 报"成功"，而用户实际连不上。
  if(Date.now()-publicProbe.at>30000){
    publicProbe.at=Date.now();
    probePublicOrigin().then(r=>{publicProbe.ok=r.ok;publicProbe.detail=r.detail;}).catch(()=>{});
  }
  // frpc 已经不在跑时，入口不可能可用：把上一次的探测结论作废，
  // 避免"进程被杀了，界面还说入口正常"（2026-11-04 实测踩到）。
  if(!child&&publicProbe.ok!==false){
    publicProbe.ok=false;
    publicProbe.detail=publicProbe.detail||'外部通道客户端未运行';
  }
  // direct 开关支持热重载：用户跑 configure-direct.mjs 后不必重启网关（自启任务由
  // 计划任务托管，重启会打断已有隧道会话）。只重载 direct 段 —— provider/frpc/
  // publicOrigin 变了必须重启，那是另一码事，不在这里悄悄生效。
  // 失败安全：读不到或校验不过就保留现值，一次写坏的 JSON 不能拖垮网关。
  try{
    const reloaded=validateConfig(JSON.parse(await fs.readFile(path.join(directory,'config.json'),'utf8')));
    const next=reloaded.direct??{enabled:false,ipv6:false,lan:false};
    const current=config.direct??{enabled:false,ipv6:false,lan:false};
    if(next.enabled!==current.enabled||next.ipv6!==current.ipv6||next.lan!==current.lan)config.direct=next;
  }catch{}
  // 直连入口（IPv6 / 局域网）每轮重新规划：地址变化是常态（隐私扩展、换网络、
  // DHCP 续期），只在期望集合变化时才触碰监听器与配置，避免 5 秒一次的无谓重绑。
  let directStates=[];
  if(config.direct?.enabled===true){
    const addresses=advertisableAddresses();
    const plan=planDirectEntries({
      enableIPv6:config.direct.ipv6===true,
      enableLAN:config.direct.lan===true,
      port:config.gatewayPort,
      ipv6:addresses.ipv6,
      ipv4:addresses.ipv4,
    });
    const key=plan.entries.map(entry=>`${entry.kind}|${entry.address}|${entry.port}`).join(',');
    if(key!==lastDirectKey){
      lastDirectKey=key;
      directStates=await gateway.syncDirect(plan.entries);
    }else directStates=gateway.directSnapshot();
  }else if(lastDirectKey!==''){
    lastDirectKey='';
    directStates=await gateway.syncDirect([]);
  }
  const directOrigins=directStates.filter(item=>item.listening).map(item=>item.origin);
  // 首次配对要过插件的 Host 校验，而插件的动态信任只认私有地址、不认全局 IPv6，
  // 所以每个直连 authority 都必须显式进 trustedHosts，且与 publicOrigin 同一次写入。
  // 节流：仅当直连 origin 集合变化或超过 60s 才写，避免每 5s 重写配置文件。
  const directKey=directOrigins.join(',');
  if(config.dshConfigPath&&(directKey!==lastTrustKey||Date.now()-lastTrustCheck>60000)){
    try{await applyTrustedHost(config.dshConfigPath,config.publicOrigin,{extraOrigins:directOrigins});
      lastTrustKey=directKey;lastTrustCheck=Date.now();}catch{}
  }
  targetPort=await findDshPort({preferred:targetPort??preferredPort});
  if(targetPort!==null)preferredPort=targetPort;
  // Do not publish an external tunnel while initial DSH discovery is pending.
  if(targetPort!==null&&!child&&Date.now()>=restartAt)startClient();
  const value={updatedAt:new Date().toISOString(),pid:process.pid,targetAvailable:targetPort!==null,
    targetPort,clientState,clientReason,publicOrigin:config.publicOrigin,businessVerified:false,...gateway.snapshot(),
    direct:directStates};
  const temporary=path.join(directory,`state-${process.pid}.tmp`);
  await fs.writeFile(temporary,JSON.stringify(value,null,2));await fs.rename(temporary,path.join(directory,'state.json'));
}
async function stop(){
  if(stopping)return;stopping=true;if(child)child.kill();await gateway.close();
}
process.on('SIGINT',()=>{stop().finally(()=>process.exit(0));});
process.on('SIGTERM',()=>{stop().finally(()=>process.exit(0));});
try{
  while(!stopping){await update();await new Promise(resolve=>setTimeout(resolve,5000));}
}finally{await stop();}
