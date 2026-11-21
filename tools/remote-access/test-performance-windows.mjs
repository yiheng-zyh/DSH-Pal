import test from 'node:test';
import assert from 'node:assert/strict';
import {createPerformanceMonitor} from './performance.mjs';
test('Windows hidden sampler keeps 0.5-second totals flowing with an idle control pipe',{skip:process.platform!=='win32',timeout:25000},async()=>{
 const monitor=createPerformanceMonitor();const seen=new Map();const start=Date.now();
 try {
  while(Date.now()-start<20000){
   const snapshot=monitor.snapshot({intervalMs:500,includeHistory:false,includeProcesses:false});
   if(snapshot.status==='ok')seen.set(snapshot.time,snapshot);
   if(seen.size>=9)break;
   await new Promise(r=>setTimeout(r,100));
  }
  const values=[...seen.values()];assert.ok(values.length>=9,'sampler stalled or did not warm up');
  assert.ok(values.slice(-6).every(value=>value.intervalMs===500));
  assert.ok(new Set(values.map(value=>value.processTime)).size>=2,'slow process snapshots did not advance');
  assert.ok(values.every(value=>Number.isFinite(value.cpu)&&value.memoryTotal>0));
 } finally {monitor.close();}
});
