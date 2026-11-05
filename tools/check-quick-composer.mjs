import fs from 'node:fs';
const source=fs.readFileSync(new URL('../app/src/main/assets/quick-composer.js',import.meta.url),'utf8').replace('window.top!==window','false');
async function run(source){
 const rows=[];let frame;const assert=(name,ok)=>rows.push({name,passed:!!ok});
 async function mount({existing='',send=true,enabled=true}={}){
  frame?.remove();frame=document.createElement('iframe');frame.style.cssText='position:fixed;inset:0;width:100%;height:100%;z-index:2147483640;background:white';
  frame.srcdoc='<aside data-dsh-mobile-sidebar><div role="treeitem" aria-selected="true" data-row-key="fixture-session"></div></aside><main data-dsh-mobile-center><form><textarea style="width:200px;height:50px"></textarea>'+(send?'<button type="button" aria-label="发送消息">Send</button>':'')+'</form></main>';
  const loaded=new Promise(r=>frame.onload=r);document.body.append(frame);await loaded;const w=frame.contentWindow;w.eval(source);w.sent=[];
  const box=w.document.querySelector('textarea'),button=w.document.querySelector('button');box.value=existing;
  if(button){button.disabled=!enabled;button.onclick=()=>w.sent.push(box.value)}return w;
 }
 try{
  let w=await mount(),api=w.__DSH_QUICK_COMPOSER__,ctx=api.context();assert('context captures exactly one selected session',ctx.selection==='fixture-session');
  let changed={...ctx,selection:'other-session'};assert('wrong session does not insert or send',await api.submit('fixture text',changed,'1')==='context-changed'&&w.sent.length===0&&w.document.querySelector('textarea').value==='');
  w=await mount({existing:'existing draft'});api=w.__DSH_QUICK_COMPOSER__;assert('existing draft is preserved',await api.submit('fixture text',api.context(),'2')==='draft-occupied'&&w.document.querySelector('textarea').value==='existing draft');
  w=await mount({send:false});api=w.__DSH_QUICK_COMPOSER__;assert('unknown submit control leaves composer unchanged',await api.submit('fixture text',api.context(),'3')==='no-send'&&w.document.querySelector('textarea').value==='');
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;assert('explicit send dispatches exactly once',await api.submit('line1\n"line2"',api.context(),'4')==='dispatched'&&w.sent.length===1&&w.sent[0]==='line1\n"line2"');
  assert('replaying operation cannot duplicate delivery',await api.submit('fixture text',api.context(),'4')==='busy'&&w.sent.length===1);
  w=await mount({enabled:false});api=w.__DSH_QUICK_COMPOSER__;const before=api.context(),pending=api.submit('fixture text',before,'5');w.document.querySelector('[data-row-key]').setAttribute('data-row-key','changed');assert('session change during pending send prevents click',await pending==='context-changed'&&w.sent.length===0);
  w=await mount({enabled:false});api=w.__DSH_QUICK_COMPOSER__;assert('disabled submit retains text without implicit Enter',await api.submit('fixture text',api.context(),'6')==='staged'&&w.sent.length===0&&w.document.querySelector('textarea').value==='fixture text');
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;assert('different origin rejected before insert',await api.submit('fixture text',{...api.context(),origin:'https://other.invalid'},'7')==='context-changed'&&w.sent.length===0);
  w=await mount();w.document.querySelector('textarea').outerHTML='<div role="textbox" contenteditable="true" style="width:200px;height:50px"></div>';api=w.__DSH_QUICK_COMPOSER__;w.document.querySelector('button').onclick=()=>w.sent.push(w.document.querySelector('[role=textbox]').textContent);
  const rich=await api.submit('rich fixture',api.context(),'8');assert('rich composer uses real browser editing path',rich==='dispatched');assert('rich composer forwards exact inserted text',w.sent.length===1&&w.sent[0]==='rich fixture');
  w=await mount({enabled:false});api=w.__DSH_QUICK_COMPOSER__;const deferredBox=w.document.querySelector('textarea');deferredBox.oninput=()=>{const value=deferredBox.value;deferredBox.value='';setTimeout(()=>{deferredBox.value=value;w.document.querySelector('button').disabled=false},35)};
  assert('deferred framework input commit waits before sending',await api.submit('deferred fixture',api.context(),'9')==='dispatched'&&w.sent[0]==='deferred fixture');
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;assert('manual stage fills current editor without sending',await api.stage('manual fixture')==='staged'&&w.sent.length===0&&w.document.querySelector('textarea').value==='manual fixture');
  w=await mount({existing:'protected draft'});api=w.__DSH_QUICK_COMPOSER__;assert('manual stage also preserves existing draft',await api.stage('manual fixture')==='draft-occupied'&&w.document.querySelector('textarea').value==='protected draft'&&w.sent.length===0);
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;w.document.querySelector('textarea').outerHTML='<div role="textbox" contenteditable="true" style="width:200px;height:50px"></div>';assert('manual rich stage waits for focus and preserves inserted text',await api.stage('rich manual fixture')==='staged'&&w.document.querySelector('[role=textbox]').textContent==='rich manual fixture'&&w.sent.length===0);
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;w.document.querySelector('textarea').oninput=()=>{w.document.querySelector('textarea').value=''};assert('manual stage does not report success when framework discards input',await api.stage('discarded fixture')==='input-not-retained'&&w.sent.length===0);
  w=await mount();api=w.__DSH_QUICK_COMPOSER__;const staged=api.stage('changed fixture');w.document.querySelector('[data-row-key]').setAttribute('data-row-key','changed');assert('manual stage cancels if target changes during focus wait',await staged==='context-changed'&&w.document.querySelector('textarea').value==='');
  return rows;
 }finally{frame?.remove()}
}
const targets=await(await fetch('http://127.0.0.1:'+(process.env.DSH_CDP_PORT||9228)+'/json')).json();const target=targets.find(t=>t.type==='page'&&!t.url.includes('doubleclick'));
const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j});
const result=await new Promise((r,j)=>{ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id===1)r(m)};ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:'('+run.toString()+')('+JSON.stringify(source)+')',returnByValue:true,awaitPromise:true}}));setTimeout(()=>j(Error('timeout')),15000).unref()});ws.close();
if(result.result?.exceptionDetails)throw Error(JSON.stringify(result.result.exceptionDetails));const rows=result.result.result.value;console.log(JSON.stringify(rows,null,2));if(rows.some(r=>!r.passed))process.exitCode=1;
