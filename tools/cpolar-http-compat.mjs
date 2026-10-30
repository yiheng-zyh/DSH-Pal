// Same-authority transport compatibility. No auth bypass, cookie storage or body logs.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import zlib from 'node:zlib';
import {fileURLToPath} from 'node:url';

export function createGateway(readState,{maxBuffer=32*1024*1024,compressAssets=true,minGzipBytes=64*1024}={}) {
  function stateFor(req) {
    try {
      const state=readState(),origin=new URL(state.origin);
      if(origin.protocol!=='https:'||!origin.hostname.endsWith('.cpolar.cn')&&!origin.hostname.endsWith('.cpolar.top'))return null;
      const authority=String(req.headers.host||'').toLowerCase();
      if(![origin.host,origin.hostname+':443'].includes(authority)||!Number.isInteger(state.targetPort)||state.targetPort<1||state.targetPort>65535)return null;
      if(String(req.headers.connection||'').split(',').some(v=>['host','origin','cookie','x-forwarded-for'].includes(v.trim().toLowerCase())))return null;
      if(!req.url?.startsWith('/')||req.url.startsWith('//')||req.url.includes('\\'))return null;
      const path=decodeURIComponent(new URL(req.url,origin).pathname);
      if((path==='/api'||path.startsWith('/api/'))&&path!=='/api/pair/status')return null;
      return state;
    }catch{return null;}
  }
  const agent=new http.Agent({keepAlive:true,maxSockets:24,maxFreeSockets:8});
  function stripHop(headers) {
    for(const name of String(headers.connection||'').split(','))delete headers[name.trim().toLowerCase()];
    for(const name of ['connection','keep-alive','proxy-connection','transfer-encoding','te','trailer','upgrade'])delete headers[name];
    return headers;
  }
  function headersFor(req,upgrade=false) {
    const headers={...req.headers,'accept-encoding':'identity'};
    // Keep the original Host/Origin/Cookie. Never impersonate a local desktop request.
    headers['x-forwarded-for']=headers['x-forwarded-for']||'tunnel';
    delete headers['proxy-authorization'];delete headers['proxy-authenticate'];
    return upgrade?headers:stripHop(headers);
  }
  const active=new Set(),recent=[];
  const server=http.createServer((req,res)=>{
    res.shouldKeepAlive=false;
    if(req.url==='/__compat_health'&&req.headers.host?.startsWith('127.0.0.1:')&&!req.headers['x-forwarded-for']){
      res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify({service:'cpolar-http-compat',ready:true,active:[...active].map(r=>({...r,ms:Date.now()-r.start})),recent}));return;
    }
    const state=stateFor(req);if(!state){res.writeHead(403);res.end('Forbidden');return;}
    const started=Date.now(),route={method:req.method,path:req.url.split('?')[0].replace(/\/remote\/api\/.*/, '/remote/api/*'),start:started,acceptEncoding:req.headers['accept-encoding']||'missing'};active.add(route);
    res.on('close',()=>{active.delete(route);recent.push({method:route.method,path:route.path,acceptEncoding:route.acceptEncoding,status:res.statusCode,ms:Date.now()-started,bytes:route.bytes||0,rawBytes:route.rawBytes||0,streamFallback:!!route.streamFallback});if(recent.length>30)recent.shift();});
    const upstream=http.request({host:'127.0.0.1',port:state.targetPort,path:req.url,method:req.method,headers:headersFor(req),agent},reply=>{
      reply.on('error',()=>res.destroy());
      const headers=stripHop({...reply.headers});
      const vary=String(headers.vary||'').split(',').map(v=>v.trim()).filter(Boolean);
      if(!vary.some(v=>v.toLowerCase()==='accept-encoding')&&!vary.includes('*'))vary.push('Accept-Encoding');
      headers.vary=vary.join(', ');
      const type=String(headers['content-type']||'').split(';')[0];
      const gzip=String(req.headers['accept-encoding']||'').split(',').some(v=>/^\s*gzip\s*(?:;\s*q=(?!0(?:\.0*)?\s*$)[0-9.]+)?\s*$/i.test(v));
      const eligible=req.method!=='HEAD'&&reply.statusCode===200&&!headers['content-encoding']&&!headers['content-range']&&/^(?:text\/(?:html|css|javascript)|application\/(?:javascript|x-javascript|json))$/.test(type);
      if(!eligible){res.writeHead(reply.statusCode||502,headers);reply.pipe(res);return;}
      let parts=[],size=0,streaming=false;
      reply.on('data',chunk=>{
        size+=chunk.length;route.rawBytes=size;
        if(streaming){res.write(chunk);return;}
        parts.push(chunk);
        if(size>maxBuffer){streaming=true;route.streamFallback=true;res.writeHead(reply.statusCode,headers);for(const part of parts)res.write(part);parts=[];}
      });
      reply.on('end',()=>{
        if(streaming){res.end();return;}
        const body=Buffer.concat(parts);parts=[];
        const sendIdentity=()=>{route.bytes=body.length;headers['content-encoding']='identity';headers['cache-control']=String(headers['cache-control']||'no-store')+', no-transform';headers['content-length']=String(body.length);res.writeHead(reply.statusCode,headers);res.end(body);};
        if(!gzip||(type!=='text/html'&&(!compressAssets||body.length<256*1024||!/^\/(?:assets|plugins)\//.test(req.url)))){sendIdentity();return;}
        zlib.gzip(body,(error,encoded)=>{
          if(res.destroyed)return;
          if(error||(type!=='text/html'&&encoded.length<minGzipBytes)){sendIdentity();return;}
          delete headers['transfer-encoding'];delete headers['content-md5'];delete headers.digest;
          route.bytes=encoded.length;headers['content-encoding']='gzip';headers['content-length']=String(encoded.length);
          if(headers.etag&&!headers.etag.startsWith('W/'))headers.etag='W/'+headers.etag;
          res.writeHead(reply.statusCode,headers);res.end(encoded);
        });
      });
    });
    upstream.on('error',()=>{if(res.headersSent){res.destroy();return;}res.writeHead(502);res.end('Upstream unavailable');});
    req.on('aborted',()=>upstream.destroy());res.on('close',()=>upstream.destroy());req.pipe(upstream);
  });
  server.on('upgrade',(req,socket,head)=>{
    const state=stateFor(req);
    if(!state||!new URL(req.url,state.origin).pathname.startsWith('/remote/')){socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');return;}
    const upstream=net.connect(state.targetPort,'127.0.0.1',()=>{
      upstream.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`+Object.entries(headersFor(req,true)).map(([name,value])=>`${name}: ${Array.isArray(value)?value.join(', '):value}`).join('\r\n')+'\r\n\r\n');
      if(head.length)upstream.write(head);socket.pipe(upstream).pipe(socket);
    });
    upstream.on('error',()=>socket.destroy());upstream.on('close',()=>socket.destroy());socket.on('error',()=>upstream.destroy());socket.on('close',()=>upstream.destroy());
  });
  server.on('close',()=>agent.destroy());
  return server;
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
  const stateFile=process.argv[2],port=Number(process.argv[3]);
  if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid loopback listen port');
  createGateway(()=>JSON.parse(fs.readFileSync(stateFile,'utf8').replace(/^\uFEFF/,''))).listen(port,'127.0.0.1');
}
