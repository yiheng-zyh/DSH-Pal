import test from 'node:test';
import assert from 'node:assert/strict';
import {cpuUsage,createPerformanceMonitor} from './performance.mjs';
import {EventEmitter} from 'node:events';
import {PassThrough} from 'node:stream';
test('CPU percentage uses counter differences and rejects missing intervals',()=>{
 assert.equal(cpuUsage({total:100,idle:50},{total:200,idle:75}),75);
 assert.equal(cpuUsage({total:100,idle:50},{total:100,idle:50}),null);
});
test('unsupported platforms do not launch a sampler',()=>{
 let launched=false;const monitor=createPerformanceMonitor({platform:'linux',startWorker(){launched=true}});
 assert.equal(monitor.snapshot().status,'unsupported');assert.equal(launched,false);monitor.close();
});
test('one worker serves multiple viewers, bounded history and cleanup',async()=>{
 let count=0,killed=0,now=1000;
 const worker=new EventEmitter();worker.stdout=new PassThrough();worker.kill=()=>killed++;
 const monitor=createPerformanceMonitor({platform:'win32',now:()=>now,startWorker(){count++;return worker}});
 assert.equal(monitor.snapshot().status,'warming');monitor.snapshot();assert.equal(count,1);
 worker.stdout.write('invalid json\n');
 for(let i=0;i<125;i++){now+=3000;worker.stdout.write(JSON.stringify({time:now,gpus:[],processes:[]})+'\n');}
 await new Promise(r=>setImmediate(r));
 const result=monitor.snapshot();assert.equal(result.status,'ok');assert.equal(result.history.length,120);
 assert.equal(result.time,now);assert.equal('history' in monitor.snapshot({includeHistory:false}),false);monitor.close();assert.equal(killed,1);
});

test('fast summary frames retain the independent slow process snapshot',async()=>{
 const worker=new EventEmitter();worker.stdout=new PassThrough();worker.kill=()=>{};
 const rates=[];worker.setIntervalMs=value=>rates.push(value);
 const monitor=createPerformanceMonitor({platform:'win32',startWorker:()=>worker});
 monitor.snapshot({intervalMs:500});
 worker.stdout.write(JSON.stringify({time:1000,gpus:[],processes:[{pid:1,cpu:8}],processTime:1000})+'\n');
 worker.stdout.write(JSON.stringify({time:1500,gpus:[],processTime:1000})+'\n');
 await new Promise(r=>setImmediate(r));
 const value=monitor.snapshot({intervalMs:500,includeProcesses:true});assert.equal(value.processes[0].pid,1);assert.equal(value.time,1500);
 assert.equal(monitor.snapshot({intervalMs:500,includeProcesses:false}).processes,undefined);
 assert.ok(rates.every(value=>[500,1000,2000].includes(value)));monitor.close();
});

test('overlay top snapshots include GPU leaders outside the CPU top five',async()=>{
 const worker=new EventEmitter();worker.stdout=new PassThrough();worker.kill=()=>{};
 const monitor=createPerformanceMonitor({platform:'win32',startWorker:()=>worker});monitor.snapshot();
 const processes=Array.from({length:10},(_,i)=>({identity:String(i),cpu:100-i,gpu:i*10}));
 worker.stdout.write(JSON.stringify({time:1000,gpus:[],diskActive:73,diskRead:1024,processes})+'\n');
 await new Promise(r=>setImmediate(r));const result=monitor.snapshot({includeProcesses:'top'});
 assert.ok(result.processes.some(p=>p.identity==='9'));assert.equal(result.diskActive,73);monitor.close();
});
