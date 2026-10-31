import http from 'node:http';
import net from 'node:net';
import {pipeline} from 'node:stream';
import {origin,port} from './config.mjs';

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
export function createGateway({publicOrigin,getTargetPort}){
  const allowedOrigin=origin(publicOrigin),publicUrl=new URL(allowedOrigin);
  const agent=new http.Agent({keepAlive:true,maxSockets:24,maxFreeSockets:6});
  const liveSockets=new Set();
  const statistics={requests:0,completed:0,active:0,failed:0,cancelled:0,responseBytes:0,remoteResponses:0,remoteErrors:0,upgrades:0,webSocketAccepted:0,taskMuxAccepted:0,upgradeClientBytes:0};
  function allowed(req,upgrade=false){
    if(authority(req.headers.host)!==allowedOrigin)return false;
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
  function upstreamHeaders(req,upgrade=false){
    const headers=upgrade?{...req.headers}:stripHop(req.headers);
    // The original public Host/Origin/Cookie remain intact. Never impersonate loopback.
    headers.host=req.headers.host;
    headers['x-forwarded-host']=publicUrl.host;
    headers['x-forwarded-proto']='https';
    headers['x-forwarded-for']='unknown';
    delete headers.forwarded;
    delete headers['proxy-authorization'];delete headers['proxy-authenticate'];
    return headers;
  }
  const server=http.createServer((req,res)=>{
    statistics.requests++;
    if(!allowed(req)){res.writeHead(403,{'cache-control':'no-store'});res.end('Forbidden');return;}
    const localPort=target();
    if(!localPort){res.writeHead(503,{'cache-control':'no-store','retry-after':'5'});res.end('Local DSH is not available');return;}
    statistics.active++;
    let cancelled=false;
    const started=Date.now();
    res.once('close',()=>{statistics.active--;statistics.completed++;statistics.lastDurationMs=Date.now()-started;});
    const upstream=http.request({host:'127.0.0.1',port:localPort,path:req.url,method:req.method,headers:upstreamHeaders(req),agent},reply=>{
      if(pathname(req.url)?.startsWith('/remote/')){
        statistics.remoteResponses++;if(reply.statusCode>=400)statistics.remoteErrors++;
      }
      // Preserve status, body, compression, cookies, CSP, cache policy and response length.
      res.writeHead(reply.statusCode||502,stripHop(reply.headers));
      reply.on('data',chunk=>statistics.responseBytes+=chunk.length);
      pipeline(reply,res,()=>{});
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
  server.on('connection',socket=>{liveSockets.add(socket);socket.once('close',()=>liveSockets.delete(socket));});
  server.on('upgrade',(req,socket,head)=>{
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
  server.on('clientError',(_,socket)=>socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'));
  server.once('close',()=>agent.destroy());
  return {
    server,
    snapshot:()=>({...statistics}),
    listen:listenPort=>new Promise((resolve,reject)=>{
      server.once('error',reject);
      server.listen(listenPort,'127.0.0.1',()=>{server.removeListener('error',reject);resolve(server.address());});
    }),
    close:()=>new Promise(resolve=>{for(const socket of liveSockets)socket.destroy();agent.destroy();server.close(resolve);}),
  };
}
