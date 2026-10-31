import test from 'node:test';
import assert from 'node:assert/strict';
import {egoEventsOnly} from './ego-events.mjs';
async function filter(chunks){const t=egoEventsOnly(),out=[];t.on('data',c=>out.push(c));const done=new Promise((r,j)=>{t.on('end',r);t.on('error',j);});for(const c of chunks)t.write(c);t.end();await done;return Buffer.concat(out).toString();}
test('background ego probe drops JPEG frames and preserves status/tool events at every byte boundary',async()=>{
 const kept=':ok\n\nevent: tool-call\ndata: {"count":1}\n\nevent: spaces\ndata: ["工作区"]\n\n';
 const frame='event: frame\ndata: '+ 'J'.repeat(200000)+'\n\n';
 const input=Buffer.from(':ok\n\n'+frame+kept.slice(5));
 for(const size of [1,7,4096,65536,input.length]){const chunks=[];for(let i=0;i<input.length;i+=size)chunks.push(input.subarray(i,i+size));assert.equal(await filter(chunks),kept);}
});
test('CRLF multi-line status events and event ids remain unchanged',async()=>{
 const kept='id: 3\r\nevent: capture-status\r\ndata: one\r\ndata: two\r\n\r\n';
 assert.equal(await filter([Buffer.from('event: frame\r\ndata: jpeg\r\n\r\n'+kept)]),kept);
});
