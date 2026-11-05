import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import {pipeline} from 'node:stream';
import {origin,port} from './config.mjs';
import {egoEventsOnly} from './ego-events.mjs';
import {canRecoverLanding,unavailableLanding} from './unavailable.mjs';

/**
 * 本机实际拥有的地址集合（用于区分「外部来访」与「本机自环」）。
 *
 * 为什么不能只判 127.0.0.1/::1：本机用**自己的公网地址**访问自己（自测、或网关
 * 自己的探测）时，来源地址就是本机网卡地址，会被误判成"外部可达"。那会让面板
 * 把"自己能连自己"报成"外面的人能连进来"。所以必须与本机全部地址比对。
 * 网卡地址会变，60 秒刷新一次。
 */
let localAddressCache = { at: 0, set: new Set(['127.0.0.1', '::1']) };
function localAddresses() {
  if (Date.now() - localAddressCache.at < 60000) return localAddressCache.set;
  const set = new Set(['127.0.0.1', '::1']);
  try {
    for (const list of Object.values(os.networkInterfaces())) {
      for (const item of list ?? []) set.add(String(item.address || '').toLowerCase());
    }
  } catch {
    // 取不到网卡就只认回环；宁可少算外部，也不能把自环当外部。
  }
  localAddressCache = { at: Date.now(), set };
  return set;
}
/** 来源地址是否来自本机之外（去掉 ::ffff: 前缀与 zone id）。 */
function isExternalRemote(remote) {
  const text = String(remote || '').toLowerCase().split('%')[0];
  if (!text) return false;
  const bare = text.startsWith('::ffff:') ? text.slice(7).split('%')[0] : text;
  const local = localAddresses();
  if (local.has(bare) || local.has(text)) return false;
  if (bare === '127.0.0.1' || bare.startsWith('127.')) return false;
  return true;
}

const protectedHeaders=new Set(['host','origin','cookie','authorization','x-forwarded-host','x-forwarded-for','x-forwarded-proto']);
const hopHeaders=['connection','keep-alive','proxy-connection','proxy-authorization','proxy-authenticate','transfer-encoding','te','trailer','upgrade'];
function stripHop(input){
  const output={...input};
  for(const name of String(input.connection||'').split(','))delete output[name.trim().toLowerCase()];
  for(const name of hopHeaders)delete output[name];
  return output;
}
function authority(value){
  if(typeof value!=='string'||/[\s/@\\?#]/.test(value))return null;
  try{return new URL('https://'+value).origin;}catch{return null;}
}
/** 规范化 Host 头为 `host[:port]`；IPv6 保留方括号（URL 语义）。无法解析返回 null。 */
function authorityHost(value){
  if(typeof value!=='string'||/[\s/@\\?#]/.test(value))return null;
  try{return new URL('https://'+value).host;}catch{return null;}
}
/** 从 (address,port) 构造 authority：IPv6 字面量加方括号，与 App 侧 authorityOf 一致。 */
function authorityOfHost(address,port){
  const bare=String(address).includes('%')?String(address).split('%')[0]:String(address);
  return bare.includes(':')?`[${bare}]:${String(port)}`:`${bare}:${String(port)}`;
}
function pathname(value){
  if(typeof value!=='string'||!value.startsWith('/')||value.startsWith('//')||/[\\\x00-\x20\x7f]/.test(value))return null;
  let path=value.split('?')[0];
  try{
    for(let i=0;i<4;i++){
      const next=decodeURIComponent(path);
      if(next===path)break;
      path=next;
      if(i===3)return null;
    }
    if(/[\\\x00-\x20\x7f]/.test(path)||path.startsWith('//'))return null;
    return new URL(path,'https://path.invalid').pathname;
  }catch{return null;}
}
/** A streaming boundary for an authenticated DSH remote plugin, never a general local proxy. */
export function createGateway({publicOrigin,getTargetPort,getDirectEntries,getTunnelState}){
  const allowedOrigin=origin(publicOrigin),publicUrl=new URL(allowedOrigin);
  /**
   * 当前有效的直连 authority（`host[:port]`，IPv6 带方括号）→ 它自己的 scheme。
   * 每轮由 run.mjs 用实测到的本机地址刷新：IPv6 隐私扩展会让地址变化，
   * 网络切换后地址也会消失，所以不能启动时算一次就固定。
   */
  const directEntries=typeof getDirectEntries==='function'?getDirectEntries:()=>[];
  function directAuthorityOf(hostHeader){
    const host=authorityHost(hostHeader);
    if(host===null)return null;
    const lowered=host.toLowerCase();
    for(const entry of directEntries()){
      if(typeof entry?.trustedHost!=='string')continue;
      if(entry.trustedHost.toLowerCase()===lowered)return entry;
    }
    return null;
  }
  const agent=new http.Agent({keepAlive:true,maxSockets:24,maxFreeSockets:6});
  const liveSockets=new Set();
  // Fixed categories only: never retain user paths, resource tokens or queries.
  const traffic={};
  function trafficKind(url){
    let p=pathname(url)||'';
    if(p.startsWith('/remote/'))p=p.slice('/remote'.length);
    if(p.startsWith('/wallpaper-engine/'))return 'wallpaper:'+p.split('/')[2];
    if(p.startsWith('/api/')){
      const name=p.slice('/api/'.length);
      const namespace=name.split('/')[0];
      if(['session','workspace','settings','llm','skills','credentials','commands','server','dsh','dynamicCordisRunner','ego','agentPresets','account','permissionPresets','experimental-inspector','schedule','remote.events','remote.stream','events','changes.summary','restart-button.status'].includes(namespace)&&/^[A-Za-z./-]{1,80}$/.test(name))return 'business:'+name;
      return 'business:other';
    }
    if(p.startsWith('/plugins/'))return 'plugins';
    if(p.startsWith('/assets/'))return 'assets';
    return 'other';
  }
  const statistics={requests:0,completed:0,active:0,failed:0,cancelled:0,responseBytes:0,remoteResponses:0,remoteErrors:0,upgrades:0,webSocketAccepted:0,taskMuxAccepted:0,upgradeClientBytes:0,directRequests:0};
  function allowed(req,upgrade=false){
    const hostHeader=req.headers.host;
    // 隧道入口：Host 必须等于 publicOrigin（原有行为，一字未变）。
    // 直连入口：Host 必须等于已登记的某个直连 authority。两者之外一律 403，
    // 所以「绑到公网 IPv6」不等于「任何人都能进」，未登记的 Host 仍然被拒。
    const direct=directAuthorityOf(hostHeader);
    if(direct===null&&authority(hostHeader)!==allowedOrigin)return false;
    if(String(req.headers.connection||'').split(',').some(name=>protectedHeaders.has(name.trim().toLowerCase())))return false;
    const path=pathname(req.url);if(path===null)return false;
    if(path==='/remote-bridge'||path.startsWith('/remote-bridge/'))return false;
    if(path==='/api'||path.startsWith('/api/')){
      const phonePairRoute=path==='/api/pair/status'&&['GET','HEAD'].includes(req.method)||
        ['/api/pair/accept','/api/pair/heartbeat'].includes(path)&&req.method==='POST';
      if(!phonePairRoute)return false;
    }
    return !upgrade||req.method==='GET'&&path.startsWith('/remote/');
  }
  function target(){try{return port(getTargetPort(),'DSH port');}catch{return null;}}
  /** 隧道客户端的当前状态（由 run.mjs 注入；未注入时返回 unknown，绝不谎报正常）。 */
  function tunnelSnapshot(){try{return getTunnelState?.()||{};}catch{return {};}}

  /**
   * 当前可供手机使用的入口清单（含状态与故障分类）。
   *
   * App 侧据此做自动换路：当前入口不通时按优先级换下一个，并把原因原样告诉用户。
   * 排序在 App 侧做（用户可自定义优先级），这里只如实报告事实。
   *
   * category 语义：
   *  - ok                 入口可用
   *  - provider-flaky     中转服务侧故障（Sakura/Cloudflare 波动）——不是用户的问题
   *  - config-broken      本机服务未就绪（DSH 没起、端口不对）
   *  - unknown            还没探测过
   */
  function channelCandidates(){
    const out=[];
    const t=tunnelSnapshot();
    // 公网隧道（frp / Cloudflare 二选一，由插件 publicBaseUrl + autoTunnel 决定）
    const tunnelOk=t.publicOk===true;
    out.push({
      id:'tunnel',
      kind:'tunnel',
      origin:String(publicUrl?.origin||''),
      available:tunnelOk,
      category:tunnelOk?'ok':(t.clientState==='client-rejected'||t.clientState==='running-unverified'
        ? 'provider-flaky'   // 客户端在跑/被拒都说明是通道侧，不是本机配置
        : (t.clientState==='waiting-to-retry'?'provider-flaky':'unknown')),
      detail:String(t.clientReason||t.publicDetail||''),
    });
    // 直连入口（IPv6 / LAN）：这些都是网关自己绑上的，能绑上就说明本机侧没问题。
    // 用第 52 行已经归一化好的 directEntries（注入函数或空数组），
    // 不要在这里再解构一次参数名 —— 早先那么写导致直连通道永远进不了清单。
    try{
      for(const entry of directEntries()){
        if(!entry?.listening)continue;
        out.push({
          id:String(entry.kind||'direct'),
          kind:String(entry.kind||'direct'),
          origin:String(entry.origin||''),
          available:true,
          category:'ok',
          detail:'',
        });
      }
    }catch{}
    return out;
  }
  function upstreamHeaders(req,upgrade=false){
    const headers=upgrade?{...req.headers}:stripHop(req.headers);
    // The original public Host/Origin/Cookie remain intact. Never impersonate loopback.
    const direct=directAuthorityOf(req.headers.host);
    headers.host=req.headers.host;
    // 报告「访客实际到达的 authority」：隧道入口下它就等于 publicUrl.host（行为不变），
    // 直连入口下必须是 IPv6/LAN 自己，否则插件会按隧道域名判断 origin 与重定向落地。
    headers['x-forwarded-host']=direct!==null?authorityHost(req.headers.host):publicUrl.host;
    headers['x-forwarded-proto']=direct!==null?'http':'https';
    headers['x-forwarded-for']='unknown';
    delete headers.forwarded;
    delete headers['proxy-authorization'];delete headers['proxy-authenticate'];
    // /api/pair/status 会被下面 injectDiagnostics 重新序列化成 JSON 并改写 body。
    // 那一步必须拿到**未压缩的** JSON：早先版本直接对 gzip 字节流做了
    // Buffer.toString('utf8')，非法字节被替换成 U+FFFD（EF BF BD），于是
    // 头里仍写着 content-encoding: gzip、体却已经是 151 字节的废数据。
    // 手机侧 OkHttp 声明 gzip 并透明解压，拿到的正是
    // `ID1ID2: actual 0x00001fef != expected 0x00001f8b`（gzip 魔数 1f 8b 的
    // 第二字节被换掉），App 的隧道状态因此恒为 UNKNOWN，每 10 秒记一条 warn。
    // 这里让上游对这一个端点回明文，注入逻辑就工作在真正的 JSON 上；
    // 其余请求的压缩照旧透传，一行不改。
    if(!upgrade&&pathname(req.url)==='/api/pair/status'){
      headers['accept-encoding']='identity';
      delete headers['content-encoding'];
    }
    return headers;
  }
  /** 同一个请求处理器挂到主监听与每个直连监听；scheme 只影响统计与日志口径。 */
  function attach(targetServer,isDirect,stats){
    targetServer.on('request',(req,res)=>{
      if(isDirect){
        statistics.directRequests++;
        /**
         * 外部来访证据：直连入口上出现"非本机地址"的请求，是「入站真的通了」
         * 唯一可靠的证据 —— 它不依赖任何第三方探测服务，对所有用户都成立：
         * 手机只要通过直连入口连过一次（扫码配对或日常使用），这里就会记录。
         * 本机自环（curl 自己的地址）会被 isExternalRemote 排除，不算数。
         */
        if(stats&&isExternalRemote(req.socket?.remoteAddress)){
          stats.externalHits=(stats.externalHits??0)+1;
          stats.lastExternalAt=new Date().toISOString();
          stats.lastExternalRemote=String(req.socket?.remoteAddress??'');
        }
      }
      statistics.requests++;
      let trafficKey=trafficKind(req.url);
      if(!Object.hasOwn(traffic,trafficKey)&&Object.keys(traffic).length>=32)trafficKey='other';
      const meter=traffic[trafficKey]??(traffic[trafficKey]={requests:0,responseBytes:0,requestBytes:0});
      meter.requests++;req.on('data',chunk=>meter.requestBytes+=chunk.length);
      if(!allowed(req)){res.writeHead(403,{'cache-control':'no-store'});res.end('Forbidden');return;}
      const localPort=target();
      if(!localPort){
        if(canRecoverLanding(req.method,req.url)){const page=unavailableLanding();res.writeHead(503,page.headers);res.end(page.body);}
        else{res.writeHead(503,{'cache-control':'no-store','retry-after':'5'});res.end('Local DSH is not available');}
        return;
      }
      statistics.active++;
      let cancelled=false;
      const started=Date.now();
      const parsed=new URL(req.url,allowedOrigin);
      const eventBudget=/^\/(?:remote\/)?api\/ego\/stream$/.test(parsed.pathname)&&parsed.searchParams.get('dsh_events_only')==='1';
      if(eventBudget)parsed.searchParams.delete('dsh_events_only');
      res.once('close',()=>{statistics.active--;statistics.completed++;statistics.lastDurationMs=Date.now()-started;});
      // /api/pair/status 是 App 唯一会主动拉取的诊断端点（配对前后都会请求）。
      // 在这里注入网关与隧道的真实状态，用户才能在 App 里看到「隧道挂了 + 原因」，
      // 而不是只看得到「已收到业务响应」却不知道外部通道其实没工作。
      // 只改写这一个 JSON 响应，其余请求照旧透传，不影响 DSH 自身协议。
      const injectDiagnostics=parsed.pathname==='/api/pair/status'&&req.method!=='HEAD';
      const upstream=http.request({host:'127.0.0.1',port:localPort,path:eventBudget?parsed.pathname+parsed.search:req.url,method:req.method,headers:upstreamHeaders(req),agent},reply=>{
        if(pathname(req.url)?.startsWith('/remote/')){
          statistics.remoteResponses++;if(reply.statusCode>=400)statistics.remoteErrors++;
        }
        if(injectDiagnostics&&reply.statusCode===200){
          const chunks=[];
          reply.on('data',c=>chunks.push(c));
          reply.on('end',()=>{
            const raw=Buffer.concat(chunks);
            // 保险丝：上游若仍压缩（可能忽略 accept-encoding: identity），
            // 绝不能把压缩字节当 UTF-8 文本处理 —— 那样会产出「头说 gzip、
            // 体是废码」的响应，手机侧解压必失败（实际症状：
            // ID1ID2: actual 0x00001fef != expected 0x00001f8b，隧道状态恒 UNKNOWN）。
            // 此时按字节原样透传：宁可少一段诊断信息，也不给客户端一个坏 body。
            const encoding=String(reply.headers['content-encoding']||'').toLowerCase();
            if(encoding&&encoding!=='identity'){
              const passed=stripHop(reply.headers);
              passed['content-length']=String(raw.length);
              res.writeHead(reply.statusCode,passed);
              res.end(raw);
              return;
            }
            let body=raw.toString('utf8');
            try{
              const value=JSON.parse(body);
              if(value&&typeof value==='object'){
                // tunnel 段与 discovery.mjs 的校验逻辑兼容（state 为字符串）。
                value.tunnel={
                  state:String(tunnelSnapshot().clientState||'unknown'),
                  reason:String(tunnelSnapshot().clientReason||''),
                  provider:String(tunnelSnapshot().provider||''),
                  // 实测的入口可用性：frpc 说"成功"也可能 TLS 不通，必须分开报。
                  publicOk:tunnelSnapshot().publicOk??null,
                  publicDetail:String(tunnelSnapshot().publicDetail||''),
                };
                value.gateway={requests:statistics.requests,directRequests:statistics.directRequests};
                // 通道清单：让 App 知道"还有哪些入口可用、各自什么状态"，
                // 才能在当前通道挂掉时自动换路。
                //
                // category 的值是给用户看的判断依据，必须能区分「通道侧故障」
                // 与「本机配置问题」—— 用户明确要求：查实是隧道波动就报告，
                // 不要让他去查自己的电脑。
                value.channels=channelCandidates();
                body=JSON.stringify(value);
              }
            }catch{}
            const headers=stripHop(reply.headers);
            delete headers['content-length'];
            res.writeHead(reply.statusCode,{...headers,'content-length':Buffer.byteLength(body)});
            res.end(body);
          });
          return;
        }
        // Preserve status, body, compression, cookies, CSP, cache policy and response length.
        const filtered=eventBudget&&reply.statusCode===200&&String(reply.headers['content-type']).startsWith('text/event-stream')&&!reply.headers['content-encoding'];
        const headers=stripHop(reply.headers);if(filtered)delete headers['content-length'];
        res.writeHead(reply.statusCode||502,headers);
        const count=chunk=>{statistics.responseBytes+=chunk.length;meter.responseBytes+=chunk.length;};
        if(filtered){const budget=egoEventsOnly();budget.on('data',count);pipeline(reply,budget,res,()=>{});}
        else{reply.on('data',count);pipeline(reply,res,()=>{});}
      });
      upstream.on('error',()=>{
        if(cancelled||res.destroyed)return;
        statistics.failed++;
        if(res.headersSent){res.destroy();return;}
        res.writeHead(502,{'cache-control':'no-store'});res.end('Local DSH connection failed');
      });
      function cancel(){if(!cancelled){cancelled=true;statistics.cancelled++;}upstream.destroy();}
      req.on('aborted',cancel);
      res.on('close',()=>{if(!res.writableFinished)cancel();else upstream.destroy();});
      pipeline(req,upstream,()=>{});
    });
    targetServer.on('connection',socket=>{liveSockets.add(socket);socket.once('close',()=>liveSockets.delete(socket));});
    targetServer.on('upgrade',(req,socket,head)=>{
      if(!allowed(req,true)){socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');return;}
      const localPort=target();
      if(!localPort){socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n');return;}
      statistics.upgrades++;
      let statusLine='',inspected=false;
      socket.on('data',bytes=>statistics.upgradeClientBytes+=bytes.length);
      const upstream=net.connect(localPort,'127.0.0.1',()=>{
        const headers=upstreamHeaders(req,true);
        upstream.write(`${req.method} ${req.url} HTTP/1.1\r\n`+Object.entries(headers).map(([name,value])=>`${name}: ${Array.isArray(value)?value.join(', '):value}`).join('\r\n')+'\r\n\r\n');
        if(head.length)upstream.write(head);
        socket.pipe(upstream).pipe(socket);
      });
      upstream.on('data',bytes=>{
        if(inspected)return;
        statusLine+=bytes.subarray(0,256).toString('ascii');
        const lineEnd=statusLine.indexOf('\r\n');
        if(lineEnd<0&&statusLine.length<256)return;
        inspected=true;
        if(/^HTTP\/1\.[01] 101(?: |\r)/.test(statusLine)){
          statistics.webSocketAccepted++;
          if(pathname(req.url)==='/remote/api/remote.mux')statistics.taskMuxAccepted++;
        }
        statusLine='';
      });
      upstream.on('error',()=>socket.destroy());upstream.on('close',()=>socket.destroy());
      socket.on('error',()=>upstream.destroy());socket.on('close',()=>upstream.destroy());
    });
    targetServer.on('clientError',(_,socket)=>socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  }
  const server=http.createServer();
  attach(server,false);
  /**
   * 每个直连地址一个独立 server。绑定失败（EADDRNOTAVAIL：换网络后地址没了；
   * EADDRINUSE：端口被占）只丢掉那一条边缘，loopback 主监听与本机 DSH 不受影响。
   * 这与插件 lan-forward.ts 的设计一致：绝不因为直连不可用而让主机起不来。
   */
  const directListeners=new Map();
  const directState=[];
  async function syncDirect(entries){
    const wanted=new Map();
    for(const entry of entries??[]){
      if(typeof entry?.address!=='string'||!Number.isInteger(entry.port))continue;
      wanted.set(`${entry.address}:${entry.port}`,entry);
    }
    for(const [key,listener] of [...directListeners])if(!wanted.has(key)){
      directListeners.delete(key);
      await new Promise(resolve=>{
        for(const socket of listener.sockets)socket.destroy();
        listener.server.close(resolve);listener.server.closeAllConnections?.();
      });
    }
    const states=[];
    for(const [key,entry] of wanted){
      const existing=directListeners.get(key);
      if(existing){states.push(existing.state);continue;}
      const state=await bindDirect(entry,key);
      states.push(state);
    }
    directState.length=0;directState.push(...states);
    return directState.map(withExternal);
  }
  /** 把某条直连入口的「外部来访」统计合并进快照（列表本身只存绑定结果）。 */
  function withExternal(entry){
    const definition=directListeners.get(`${entry.address}:${entry.port}`);
    if(!definition?.stats)return entry;
    return {...entry,externalHits:definition.stats.externalHits??0,
      lastExternalAt:definition.stats.lastExternalAt??null};
  }
  function bindDirect(entry,key){
    return new Promise(resolve=>{
      // 每条直连入口独立统计「外部来访」，用来如实回答「外面到底能不能连进来」。
      const stats={externalHits:0,lastExternalAt:null,lastExternalRemote:null};
      const directServer=http.createServer();
      attach(directServer,true,stats);
      const sockets=new Set();
      directServer.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));});
      let settled=false;
      const settle=next=>{if(settled)return;settled=true;resolve(next);};
      directServer.on('error',error=>{
        const reason=error.code===undefined?error.message:`${error.code}: ${error.message}`;
        const existing=directListeners.get(key);
        if(existing&&existing.server===directServer)directListeners.delete(key);
        for(const socket of sockets)socket.destroy();
        settle({kind:entry.kind,address:entry.address,port:entry.port,listening:false,error:reason,origin:entry.origin,trustedHost:entry.trustedHost});
        if(!directServer.listening)return;
        directServer.close(()=>undefined);
      });
      // IPv6 字面量必须原样传给 listen（node 自己处理方括号之外的形式）。
      directServer.listen(entry.port,entry.address,()=>{
        const bound=directServer.address();
        const boundAddress=typeof bound==='object'&&bound!==null?bound.address:entry.address;
        const boundPort=typeof bound==='object'&&bound!==null?bound.port:entry.port;
        // authority 按内核**实际绑定**的地址与端口重算，不用调用方算好的那份：
        // 端口 0 会被内核换成真实端口，地址也可能被解析成另一种写法。若沿用传入值，
        // Host 白名单就会对不上，表现为「监听起来了但所有请求都 403」。
        const trustedHost=authorityOfHost(boundAddress,boundPort);
        const state={kind:entry.kind,address:boundAddress,port:boundPort,listening:true,error:undefined,
          origin:`http://${trustedHost}`,trustedHost,
          externalHits:stats.externalHits??0,lastExternalAt:stats.lastExternalAt??null};
        directListeners.set(key,{server:directServer,sockets,state,stats});
        settle(state);
      });
    });
  }
  server.once('close',()=>agent.destroy());
  return {
    server,
    snapshot:()=>({...statistics,traffic:structuredClone(traffic)}),
    // 快照用于写 state.json，克隆保证外部读到的是稳定副本。
    // 合并「外部来访」统计：插件据此区分「只是本机绑上了」与「外面真的连进来过」。
    directSnapshot:()=>structuredClone(directState.map(withExternal)),
    // 热路径（每个请求的 Host 校验）用只读实时视图，不做克隆。
    liveDirectEntries:()=>directState,
    syncDirect,
    listen:listenPort=>new Promise((resolve,reject)=>{
      server.once('error',reject);
      server.listen(listenPort,'127.0.0.1',()=>{server.removeListener('error',reject);resolve(server.address());});
    }),
    close:()=>new Promise(resolve=>{
      for(const socket of liveSockets)socket.destroy();
      const pending=[...directListeners.values()].map(listener=>new Promise(done=>{
        for(const socket of listener.sockets)socket.destroy();
        listener.server.close(done);listener.server.closeAllConnections?.();
      }));
      directListeners.clear();directState.length=0;
      Promise.all(pending).then(()=>{agent.destroy();server.close(resolve);});
    }),
  };
}
