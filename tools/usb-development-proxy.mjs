// Local development only. No config writes, credential logging, LAN listeners or TLS bypass.
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const targetPort = Number(process.argv[2]);
const listenPort = 18766;
const deviceOrigin = 'http://dsh-usb.localhost:18765';
const targetOrigin = `http://127.0.0.1:${targetPort}`;
const diagnostics={requests:0,pairRequests:0,pending:0,errors:0,lastStatus:0,pairStatus:0,pairRedirect:'none'};
if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) throw new Error('Invalid target port');
function headersFor(req) {
  const headers = {...req.headers, host: `127.0.0.1:${targetPort}`};
  for (const name of ['origin', 'referer']) {
    if (headers[name]?.startsWith(deviceOrigin)) headers[name] = targetOrigin + headers[name].slice(deviceOrigin.length);
  }
  return headers;
}
const server = http.createServer(async (req, res) => {
  if (req.url === '/__dsh_mobile/layout.css' && req.method === 'GET') {
    try {
      const css = await fs.readFile(fileURLToPath(new URL('../app/src/main/assets/mobile-layout.css', import.meta.url)));
      res.writeHead(200, {'content-type':'text/css; charset=utf-8','cache-control':'no-store'});
      res.end(css);
    } catch { res.writeHead(503); res.end('Development stylesheet unavailable'); }
    return;
  }
  if (req.url === '/__dsh_usb_health') {
    res.writeHead(200, {'content-type':'application/json','cache-control':'no-store'});
    res.end(JSON.stringify({service:'dsh-usb-development',targetPort,version:5,pid:process.pid,...diagnostics})); return;
  }
  diagnostics.requests++;diagnostics.pending++;
  if(req.url?.startsWith('/pair-'))diagnostics.pairRequests++;
  let finished=false;
  const finish=()=>{if(!finished){finished=true;diagnostics.pending--;}};
  res.on('close',finish);
  const upstream = http.request({host:'127.0.0.1',port:targetPort,path:req.url,method:req.method,headers:headersFor(req)}, response => {
    diagnostics.lastStatus=response.statusCode||502;
    const headers = {...response.headers};
    if (typeof headers.location === 'string' && headers.location.startsWith(targetOrigin)) headers.location = deviceOrigin + headers.location.slice(targetOrigin.length);
    if(req.url?.startsWith('/pair-')) {
      diagnostics.pairStatus=response.statusCode||502;
      diagnostics.pairRedirect=typeof headers.location!=='string'?'none':headers.location.startsWith(deviceOrigin)?'usb':headers.location.startsWith('/')?'relative':'external';
    }
    if (headers['access-control-allow-origin'] === targetOrigin) headers['access-control-allow-origin'] = deviceOrigin;
    res.writeHead(response.statusCode || 502, headers);
    response.pipe(res);
  });
  // Pairing must settle, but event streams and large resource downloads may
  // legitimately stay idle. Never apply a blanket timeout to live workflows.
  if(req.url?.startsWith('/pair-'))upstream.setTimeout(30_000,()=>upstream.destroy(new Error('Upstream timeout')));
  upstream.on('error', () => { if(res.destroyed)return; diagnostics.errors++; if (!res.headersSent) res.writeHead(502); res.end('USB development upstream unavailable'); });
  req.on('aborted', () => upstream.destroy());
  res.on('close', () => upstream.destroy());
  req.pipe(upstream);
});
server.on('upgrade', (req, socket, head) => {
  const upstream = net.connect(targetPort, '127.0.0.1', () => {
    const headers = headersFor(req);
    upstream.write(`${req.method} ${req.url} HTTP/1.1\r\n` + Object.entries(headers).map(([k,v])=>`${k}: ${Array.isArray(v)?v.join(', '):v}`).join('\r\n') + '\r\n\r\n');
    if (head.length) upstream.write(head);
    socket.pipe(upstream).pipe(socket);
  });
  upstream.on('error', () => socket.destroy());
  socket.on('error', () => upstream.destroy());
  socket.on('close', () => upstream.destroy());
});
server.listen(listenPort, '127.0.0.1', () => process.stdout.write('USB development proxy ready on loopback.\n'));
