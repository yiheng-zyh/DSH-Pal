// USB-only startup regression fixture. Contains no DSH account or business data.
import http from 'node:http';
const delay=Number(process.argv[2]||105_000);
if(!Number.isInteger(delay)||delay<1_000||delay>300_000)throw Error('Delay must be 1000..300000 ms');
const stats={documents:0,downloads:0,aborted:0,completed:0,startedAt:Date.now()};
const server=http.createServer((req,res)=>{
  if(req.method==='HEAD'){res.writeHead(200);res.end();return;}
  if(req.url==='/__fixture_stats'){res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(stats));return;}
  if(req.url.startsWith('/__dsh_mobile/layout.css')){res.writeHead(200,{'content-type':'text/css'});res.end('/* USB fixture */');return;}
  if(req.url==='/plugins/slow-startup.js'){
    stats.downloads++;stats.downloadStartedAt=Date.now();
    res.writeHead(200,{'content-type':'text/javascript','cache-control':'no-store'});
    res.write('// Download remains active while the startup deadline passes.\n');
    const interval=setInterval(()=>res.write('// Still receiving fixture bytes.\n'),1000);
    const timer=setTimeout(()=>{stats.completed++;clearInterval(interval);res.end('document.body.innerHTML="<textarea style=\\"width:80vw;height:120px\\" placeholder=\\"USB test editor\\"></textarea>";');},delay);
    res.on('close',()=>{clearInterval(interval);clearTimeout(timer);if(!res.writableFinished)stats.aborted++;});
    return;
  }
  if(req.url==='/'||req.url==='/pair-app'){
    stats.documents++;
    res.writeHead(200,{'content-type':'text/html; charset=utf-8','cache-control':'no-store'});
    res.end('<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>USB slow-startup fixture</title><body><p>USB startup fixture: receiving resources</p><script src="/plugins/slow-startup.js"></script></body>');return;
  }
  res.writeHead(404);res.end();
});
server.listen(18765,'127.0.0.1',()=>console.log('USB startup fixture listening on loopback port 18765; delay='+delay));
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{server.closeAllConnections();server.close();});
