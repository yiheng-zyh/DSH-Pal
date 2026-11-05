import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const script=fs.readFileSync(new URL('../app/src/main/assets/page-ready.js',import.meta.url),'utf8');
function fixture({body=true,grant=false,title='',path='/',sizes=[]}={}){
  const messages=[];
  const document={title,body:body?{}:null,querySelectorAll(){return sizes.map(([width,height])=>({getBoundingClientRect:()=>({width,height})}));}};
  if(document.body)for(const property of ['innerText','textContent'])Object.defineProperty(document.body,property,{get(){throw Error('Full body text must not be scanned');}});
  const box={document,location:{pathname:path},DshNative:{onBridgeMessage:value=>messages.push(JSON.parse(value))}};
  box.window=box;if(grant)box.__DSH_REMOTE_HOST_GRANT__={};vm.createContext(box);
  return {messages,run:()=>vm.runInContext(script,box)};
}
let f=fixture();assert.equal(f.run(),false,'A bridge or empty document is not a usable workspace');
f=fixture({grant:true,body:false});assert.equal(f.run(),false);assert.equal(f.run(),false);assert.deepEqual(f.messages,[{kind:'pair-established'}],'Consume a valid grant once, before resources finish');
f=fixture({sizes:[[0,20],[200,0],[200,40]]});assert.equal(f.run(),true,'A visible editor is ready without reading body text');
f=fixture({sizes:[[0,20],[200,0]]});assert.equal(f.run(),false,'Hidden editors do not dismiss loading');
f=fixture({title:'Pairing link invalid',path:'/pair-app',sizes:[[100,30]]});assert.equal(f.run(),'auth-required');assert.equal(f.run(),'auth-required');assert.deepEqual(f.messages,[{kind:'auth-lost'}],'Expired pairing takes precedence and is reported once');
f=fixture({title:'Pairing link invalid',path:'/',sizes:[[100,30]]});assert.equal(f.run(),true,'Do not diagnose auth from unrelated page titles');
f=fixture({grant:true,title:'Pairing link invalid',path:'/pair-app',sizes:[[100,30]]});assert.equal(f.run(),true);assert.deepEqual(f.messages,[{kind:'pair-established'}]);
console.log('PASS visible-editor readiness, no body scan, early grant, hidden editor rejection and one-time authentication reporting');
