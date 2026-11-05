import http from 'node:http';

/**
 * DSH 实例指纹识别 —— 用于在不知道端口的情况下发现本机运行的 DSH。
 *
 * 【为什么需要这个文件】
 * 历史上 `findDshPort` 只认一个信号：旧远程插件 `@linxin666/dsh-remote-web-ui`
 * 提供的 `/api/pair/status`。该插件已被上游作者弃用（2026-11），并明确声明
 * 「浏览器远控能力已退役、不再迁移」，新包 `@linxin666/dsh-remote-bridge`
 * 不提供 `/api/pair` 系列端点。一旦旧插件失效，只靠它反查端口会让
 * `findDshPort` 恒返回 null，进而让整条隧道对所有请求回 503 —— 这比
 * 手机端白屏更早坏，且坏的是电脑端。
 *
 * 【解决方案】分层指纹
 *   第 1 层（优先）：旧插件的 `/api/pair/status` —— 信息最全，行为与历史一致。
 *   第 2 层（回退）：DSH 本体的鉴权拒绝文案 —— 与任何插件无关，由 `dsh web`
 *                    自己的鉴权中间件发出，只要 DSH 在监听就一定存在。
 *
 * 第 2 层的证据（2026-11-05 实测，DSH 0.2.1-rc.5）：
 *   GET http://127.0.0.1:<port>/  →  401
 *   body: "dsh web authentication required; reopen the URL printed by dsh web."
 *   该文案在旧插件包里搜索结果为 0 命中 —— 确认是 DSH 本体发出。
 */

/** DSH 本体未鉴权时返回的文案（去掉首尾空白后精确匹配前缀）。 */
const DSH_AUTH_REQUIRED = 'dsh web authentication required';

/** `/api/*` 未鉴权时 DSH 本体返回的简短语。 */
const DSH_API_UNAUTHORIZED = 'unauthorized';

/**
 * 判断一个 `/api/pair/status` 响应体是否来自旧远控插件。
 *
 * 保持原有判据不变 —— 这是与历史完全一致的第一层指纹。
 */
export function isDshStatus(value){
  // A disabled Relay is omitted by the plugin in manual-public-origin mode.
  // Fingerprint the stable pairing protocol, not optional provider status.
  return !!value&&value.ok===true&&typeof value.paired==='boolean'&&
    typeof value.requirePairingForLan==='boolean'&&typeof value.phase==='string'&&typeof value.lanAvailable==='boolean'&&
    (value.tunnel===undefined||typeof value.tunnel?.state==='string')&&
    (value.relay===undefined||typeof value.relay?.state==='string');
}

/**
 * 判断响应体文本是否带有 DSH 本体的鉴权拒绝特征。
 *
 * 只认前缀，不做全文匹配 —— 后续可能追加提示（例如如何取得带令牌的 URL），
 * 前缀稳定而尾部易变。
 */
export function isDshAuthRejection(body){
  if(typeof body!=='string')return false;
  const text=body.trim().toLowerCase();
  if(!text)return false;
  return text.startsWith(DSH_AUTH_REQUIRED)||text===DSH_API_UNAUTHORIZED;
}

/**
 * 发一个 GET 请求并读取响应（限制体积与耗时）。
 *
 * @param {number} localPort
 * @param {string} requestPath
 * @param {{timeoutMs?:number,maxBytes?:number}} [options]
 * @returns {Promise<{statusCode:number,body:string}|null>}
 */
function readLocal(localPort,requestPath,{timeoutMs=2000,maxBytes=8192}={}){
  return new Promise(resolve=>{
    let settled=false;
    const done=value=>{if(!settled){settled=true;resolve(value);}};
    let bytes=0;const parts=[];
    const req=http.get({host:'127.0.0.1',port:localPort,path:requestPath,agent:false},res=>{
      res.on('data',chunk=>{
        bytes+=chunk.length;
        if(bytes>maxBytes){req.destroy();done(null);return;}
        parts.push(chunk);
      });
      res.on('end',()=>done({statusCode:res.statusCode,body:Buffer.concat(parts).toString('utf8')}));
      res.on('error',()=>done(null));
    });
    req.setTimeout(timeoutMs,()=>{req.destroy();done(null);});
    req.on('error',()=>done(null));
  });
}

/**
 * 第 1 层指纹：旧远控插件的 `/api/pair/status`。
 *
 * @param {number} localPort
 * @returns {Promise<boolean>}
 */
export async function checkViaPairStatus(localPort){
  const res=await readLocal(localPort,'/api/pair/status',{maxBytes:65536});
  if(!res||res.statusCode!==200)return false;
  try{return isDshStatus(JSON.parse(res.body));}catch{return false;}
}

/**
 * 第 2 层指纹：DSH 本体的鉴权拒绝文案。
 *
 * 这一层不依赖任何插件，只要 `dsh web` 在监听就成立。
 * 两个候选路径并行探测，避免串行把耗时翻倍。
 *
 * @param {number} localPort
 * @returns {Promise<boolean>}
 */
export async function checkViaDshAuth(localPort){
  const results=await Promise.all(['/','/api/'].map(async requestPath=>{
    const res=await readLocal(localPort,requestPath);
    // 401/403 才有资格是鉴权拒绝；200 说明无鉴权或不是 DSH，交给别的判据。
    if(!res||![401,403].includes(res.statusCode))return false;
    return isDshAuthRejection(res.body);
  }));
  return results.some(Boolean);
}

/**
 * 判断某端口是否是 DSH 实例（分层回退）。
 *
 * 两层并行发出，不串行等待 —— 串行会让一个无响应的端口把每次探测拖到
 * 2 倍超时（历史上单端口预算是 2s，串行后变 4s+），超出调用方预期。
 * 并行时总耗时由最慢的一层决定，仍等价于单层的 2s 预算。
 *
 * @param {number} localPort
 * @returns {Promise<boolean>}
 */
export async function checkDshPort(localPort){
  if(!Number.isInteger(localPort)||localPort<1024||localPort>65535)return false;
  const [pairStatus,dshAuth]=await Promise.all([
    checkViaPairStatus(localPort),
    checkViaDshAuth(localPort),
  ]);
  return pairStatus||dshAuth;
}

/**
 * 在候选端口里找出真正的 DSH。
 *
 * @param {{preferred?:number,discoverPorts?:()=>Promise<number[]>}} [options]
 * @returns {Promise<number|null>}
 */
export async function findDshPort({preferred,discoverPorts=windowsDshPorts}={}){
  if(Number.isInteger(preferred)&&preferred>=1024&&preferred<=65535&&await checkDshPort(preferred))return preferred;
  for(const candidate of await discoverPorts())if(await checkDshPort(candidate))return candidate;
  return null;
}

/**
 * 列出本机由 DSH 进程持有的监听端口。
 *
 * 只检查 DSH 自己的进程，绝不扫描无关的本地服务。
 */
export async function windowsDshPorts(){
  if(process.platform!=='win32')return [];
  const {execFile}=await import('node:child_process');
  const {promisify}=await import('node:util');
  const execute=promisify(execFile);
  // Only inspect ports owned by DSH, never scan unrelated local services.
  const command="$owners=@(Get-CimInstance Win32_Process -Filter \"Name='DeepSeek Harness.exe'\" | Select-Object -ExpandProperty ProcessId); if($owners.Count){Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object {$owners -contains $_.OwningProcess -and $_.LocalAddress -in @('127.0.0.1','::1','0.0.0.0','::')} | Select-Object -ExpandProperty LocalPort -Unique | ConvertTo-Json -Compress}";
  try{
    const {stdout}=await execute('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{windowsHide:true,timeout:12000,maxBuffer:65536});
    if(!stdout.trim())return [];
    const value=JSON.parse(stdout);return (Array.isArray(value)?value:[value]).filter(p=>Number.isInteger(p)&&p>=1024&&p<=65535);
  }catch{return [];}
}
