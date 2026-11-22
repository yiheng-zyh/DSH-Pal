import os from 'node:os';
import {performance} from 'node:perf_hooks';
import net from 'node:net';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
import {spawn} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline';
import {randomUUID} from 'node:crypto';

/** Hidden launcher + memory-only named pipe, not a console or an on-disk metrics log. */
function windowsWorker() {
 const worker=new EventEmitter(), output=new PassThrough(), sockets=new Set();
 worker.stdout=output;
 const name='DSHPalPerf-'+randomUUID();
 let closed=false,launcher,intervalMs=1000;
 const server=net.createServer(socket=>{
  if(closed||sockets.size){socket.destroy();return;}
  sockets.add(socket);socket.pipe(output);socket.write(intervalMs+"\n");
  socket.on('error',()=>{});
  socket.on('close',()=>{sockets.delete(socket);if(!closed)worker.emit('exit');});
 });
 worker.setIntervalMs=value=>{if(value===intervalMs)return;intervalMs=value;for(const socket of sockets)socket.write(intervalMs+"\n");};
 worker.kill=()=>{
  if(closed)return;closed=true;
  for(const socket of sockets)socket.destroy();sockets.clear();
  output.destroy();launcher?.kill();server.close();
 };
 server.on('error',()=>worker.emit('error',Error('Sampler pipe unavailable')));
 server.listen('\\\\.\\pipe\\'+name,()=>{
  if(closed){server.close();return;}
  launcher=spawn('wscript.exe',['//B','//Nologo',fileURLToPath(new URL('./performance-hidden.vbs',import.meta.url)),fileURLToPath(new URL('./performance-sampler.ps1',import.meta.url)),name],{windowsHide:true,stdio:'ignore'});
  launcher.on('error',()=>worker.emit('error',Error('Sampler launcher unavailable')));
  launcher.on('exit',code=>{if(code&&!closed)worker.emit('error',Error('Sampler launcher failed'));});
 });
 return worker;
}

export function cpuTimes(cpus) {
 return cpus.reduce((s,c)=>({idle:s.idle+c.times.idle,total:s.total+Object.values(c.times).reduce((a,b)=>a+b,0)}),{idle:0,total:0});
}
export function cpuUsage(previous,next) {
 const total=next.total-previous.total,idle=next.idle-previous.idle;
 return total>0&&idle>=0 ? Math.max(0,Math.min(100,100*(1-idle/total))) : null;
}
/** One bounded sampler per gateway, only while an authenticated viewer polls. */
export function createPerformanceMonitor({platform=process.platform,startWorker,now=()=>performance.now()}={}) {
 let worker,lines,latest,lastPoll=0,lastStart=-Infinity,previous,watchdog;
 const history=[],instance=randomUUID(),rates=new Map();
 function stop(){lines?.close();lines=null;worker?.kill();worker=null;clearInterval(watchdog);watchdog=null;previous=null;}
 function start(){
  if(worker||now()-lastStart<10000)return;
  lastStart=now();latest=null;history.length=0;
  const child=startWorker?startWorker():windowsWorker();
  worker=child;previous=cpuTimes(os.cpus());
  lines=createInterface({input:child.stdout});
  lines.on('line',line=>{
   if(worker!==child||line.length>1048576)return;
   try {
    const v=JSON.parse(line);if(!Array.isArray(v.gpus)||!Number.isFinite(v.time))return;
    const next=cpuTimes(os.cpus()),cpu=cpuUsage(previous,next);previous=next;
    latest={...v,processes:Array.isArray(v.processes)?v.processes.slice(0,1024):latest?.processes??[],processCount:v.processCount??latest?.processCount??0,schema:1,instance,cpu,memoryUsed:os.totalmem()-os.freemem(),memoryTotal:os.totalmem(),receivedAt:now()};
    latest.processes=latest.processes.slice(0,1024);
    history.push({time:v.time,cpu,memory:100*latest.memoryUsed/latest.memoryTotal,gpus:v.gpus});
    if(history.length>120)history.shift();
   } catch {}
  });
  child.on('error',()=>{if(worker===child)stop();});
  child.on('exit',()=>{if(worker===child)stop();});
  watchdog=setInterval(()=>{if(now()-lastPoll>15000||(!latest&&now()-lastStart>30000)||(latest&&now()-latest.receivedAt>15000))stop();},3000);watchdog.unref?.();
 }
 return {
  snapshot({includeHistory=true,includeProcesses=true,intervalMs=1000}={}){lastPoll=now();
   intervalMs=[500,1000,2000].includes(intervalMs)?intervalMs:1000;rates.set(intervalMs,now());
   for(const [rate,time] of rates)if(now()-time>15000)rates.delete(rate);
if(platform!=='win32')return {schema:1,status:'unsupported',message:'电脑性能采集目前支持 Windows 10/11'};
   start();worker?.setIntervalMs?.(Math.min(...rates.keys()));return latest?{...latest,status:now()-latest.receivedAt>12000?'stale':'ok',...(includeHistory?{history:[...history]}:{}),processes:includeProcesses==='top'?[...new Map(['cpu','gpu'].flatMap(key=>latest.processes.slice().sort((a,b)=>(b[key]??-1)-(a[key]??-1)).slice(0,5)).map(p=>[p.identity,p])).values()]:includeProcesses?latest.processes:undefined}:{schema:1,status:'warming',message:'正在读取电脑性能计数器，请稍候'};},
  close:stop,
 };
}
