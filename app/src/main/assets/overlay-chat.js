(function(){
 if(window.__DSH_OVERLAY_CHAT__)return;
 let controls=new Map(),sequence=0,current=null;
 const visible=e=>e&&e.isConnected&&e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0&&getComputedStyle(e).visibility!=='hidden';
 function context(){let rows=document.querySelectorAll('[data-dsh-mobile-sidebar] [role="treeitem"][aria-selected="true"][data-row-key]');if(rows.length!==1)return null;return location.origin+'|'+location.pathname+'|'+rows[0].getAttribute('data-row-key');}
 function question(){
  let submits=[...document.querySelectorAll('button')].filter(e=>visible(e)&&/^(提交|Submit)$/.test(e.innerText.trim()));
  if(submits.length!==1)return null;
  let root=submits[0].parentElement;
  for(let n=0;root&&n<7;n++,root=root.parentElement){
   if(root===document.body)break;
   if(/跳过|Skip/.test(root.innerText)&&root.querySelectorAll('button').length>=2)return root;
  }
  return null;
 }
 function read(){
  const ctx=context(),center=document.querySelector('[data-dsh-mobile-center]');
  if(!ctx||!center){controls.clear();current=null;return {ready:false,text:'当前对话尚未就绪，请在主界面确认连接。'};}
  const root=question();if(!current||current.ctx!==ctx||current.root!==root){controls.clear();current={ctx,root,token:String(++sequence)};}
  const clone=center.cloneNode(true);
  clone.querySelectorAll('textarea,input,[contenteditable="true"],script,style,[data-shimmer-decoration]').forEach(e=>e.remove());
  const text=(clone.textContent||'').trim().slice(-100000);
  // innerText on the live center preserves paragraphs; remove the composer text separately.
  const display=(center.innerText||text).trim().slice(-100000);
  const actions=[];controls.clear();
  if(root){
   [...root.querySelectorAll('button,input:not([type="hidden"]),textarea,[role="radio"],[role="checkbox"]')].filter(visible).slice(0,40).forEach((e,i)=>{
    const input=e.matches('textarea,input:not([type="radio"]):not([type="checkbox"])');
    const label=input?(e.getAttribute('placeholder')||'自定义回答'):(e.innerText||e.getAttribute('aria-label')||e.value||'').trim();
    if(!label)return;const id=String(i);controls.set(id,{e,label,input});actions.push({id,label:label.slice(0,1000),input,enabled:!e.disabled,value:input?e.value:undefined});
   });
  }
  return {ready:true,context:ctx,token:current.token,text:display,question:root?root.innerText.slice(0,16000):'',actions};
 }
 function act(ctx,token,id,value){
  if(!current||current.ctx!==ctx||context()!==ctx||current.token!==token||question()!==current.root)return 'context-changed';
  const c=controls.get(id);if(!c||!visible(c.e)||c.e.disabled)return 'unavailable';
  if(c.input){if(typeof value!=='string'||value.length>8000)return 'invalid';const proto=c.e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(c.e,value);c.e.dispatchEvent(new Event('input',{bubbles:true}));return 'edited';}
  if((c.e.innerText||c.e.getAttribute('aria-label')||c.e.value||'').trim()!==c.label)return 'context-changed';
  c.e.click();controls.clear();return 'dispatched';
 }
 function older(ctx){
  if(context()!==ctx)return 'context-changed';
  let center=document.querySelector('[data-dsh-mobile-center]');
  let candidates=[center,...center.querySelectorAll('*')].filter(e=>e.scrollHeight>e.clientHeight+30&&/auto|scroll/.test(getComputedStyle(e).overflowY));
  const target=candidates.sort((a,b)=>b.clientHeight-a.clientHeight)[0];
  if(!target)return 'no-history-scroll';
  target.scrollTop=0;target.dispatchEvent(new Event('scroll',{bubbles:true}));return 'requested';
 }
 window.__DSH_OVERLAY_CHAT__={read,act,older};
})();
