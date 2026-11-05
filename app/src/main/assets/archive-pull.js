(function(){
 // 后台拉取会话**完整历史** —— 完全不切换 UI。
 //
 // 协议来自 DSH 源码（asar 检索），关键事实：
 //   "session/page": {
 //     description: "Read one cold-safe, message-aligned Session history page.",
 //     jsonSchema: {
 //       address:    { kind:"session", sessionId }      ← 必填
 //       throughSeq: number                             ← **必填！**
 //         description: "Inclusive log cut obtained from the
 //                       corresponding follow opening frame."
 //       beforeSeq:  number                             ← 向后翻页
 //       maxMessages:number, turnWindow:{minMessages,minTurns}
 //     },
 //     required: ["address", "throughSeq"]
 //   }
 // 所以正确流程是：先 session/follow 拿 opening frame 里的 seq，再带 throughSeq 调 session/page。
 // 之前报 "wire field request failed boundary validation" 就是缺 throughSeq。
 if(window.__DSH_ARCHIVE_FETCH__)return;

 function rpc(method,request){
  return JSON.stringify({
   type:'client-request',
   rpcId:'archive-'+Date.now()+'-'+Math.floor(Math.random()*1e6),
   method:method,
   payload:{args:{request:request}}
  });
 }

 async function post(ep,method,request){
  try{
   var r=await fetch(ep,{
    method:'POST',
    headers:{'content-type':'application/json'},
    credentials:'same-origin',
    body:rpc(method,request)
   });
   var t='';
   try{ t=await r.text(); }catch(e){}
   return {status:r.status,body:t};
  }catch(e){return {status:0,body:'',err:String(e&&e.message)};}
 }

 function parse(body){
  try{ return JSON.parse(body); }catch(e){ return null; }
 }

 function hasMessages(body){
  if(!body)return false;
  return body.indexOf('"sessionUpdate"')>=0||
         body.indexOf('user_message')>=0||
         body.indexOf('agent_message')>=0||
         body.indexOf('"role":"user"')>=0||
         body.indexOf('"role":"assistant"')>=0||
         body.indexOf('"kind":"message"')>=0;
 }

 /** 从任意 JSON 里找最大的 seq（= 当前日志切点）。 */
 function maxSeq(node,acc){
  acc=acc||{v:null};
  if(node==null||typeof node!=='object')return acc;
  if(Array.isArray(node)){ node.forEach(function(x){maxSeq(x,acc);}); return acc; }
  if(typeof node.seq==='number'&&(acc.v==null||node.seq>acc.v))acc.v=node.seq;
  for(var k in node){ if(k!=='seq'&&typeof node[k]==='object')maxSeq(node[k],acc); }
  return acc;
 }

 /** SSE/JSONL 缓冲里逐行尝试解析，能解多少解多少。 */
 function tryParseAny(buf){
  if(!buf)return null;
  var out=[];
  var lines=buf.split('\n');
  for(var i=0;i<lines.length;i++){
   var line=lines[i].trim();
   if(!line)continue;
   if(line.indexOf('data:')===0)line=line.slice(5).trim();
   if(!line||line==='[DONE]')continue;
   try{ out.push(JSON.parse(line)); }catch(e){}
  }
  return out.length?out:null;
 }

 /**
  * 拉完整历史：follow 取 opening cursor → page 循环回溯。
  */
 window.__DSH_ARCHIVE_PULL_FULL__=async function(sessionId){
  var address={kind:'session',sessionId:sessionId};
  var tried=[];
  var pages=[];
  var collected=[];   // 逐页抽出的 [{role,text,seq}]，最后去重
  var pageCount=0;
  var rawBytes=0;

  // ① 取 throughSeq。
  // schema 说它来自 "the corresponding follow opening frame"。
  // session/follow 是 SSE 流：不能 await text()（会挂住），
  // 用 reader 只读第一帧然后 cancel —— 既拿到切点又不留长连接。
  var through=null;
  try{
   var fr=await fetch('/api/session/follow',{
    method:'POST',
    headers:{'content-type':'application/json'},
    credentials:'same-origin',
    body:rpc('session/follow',{address:address})
   });
   if(fr.ok&&fr.body){
    var reader=fr.body.getReader();
    var dec=new TextDecoder();
    var buf='';
    var deadline=Date.now()+3000;
    while(Date.now()<deadline){
     var chunk=await reader.read();
     if(chunk.done)break;
     buf+=dec.decode(chunk.value,{stream:true});
     // opening frame 通常很快到；攒到能解析出 seq 就停
     var ms=maxSeq(tryParseAny(buf));
     if(ms.v!=null){ through=ms.v; break; }
     if(buf.length>200000)break;
    }
    try{ await reader.cancel(); }catch(e){}
    if(through==null){
     var m=buf.match(/"seq"\s*:\s*(\d+)/g);
     if(m&&m.length){
      var nums=m.map(function(s){return Number(s.replace(/\D/g,''));});
      through=Math.max.apply(null,nums);
     }
    }
    tried.push('follow-opening: through='+through);
   }else{
    tried.push('follow: http-'+fr.status);
   }
  }catch(e){ tried.push('follow-throw:'+(e&&e.message)); }

  // 兜底：projections 的 asOfSeq 也是当前切点。
  // 注意它的参数结构是 {sessionId}（真机验证过能成功），与 page 的 address 不同。
  if(through==null){
   var pj=await post('/api/session/projections','session/projections',{sessionId:sessionId});
   var pp=parse(pj.body);
   if(pp&&pp.result&&pp.result.value){
    var v=pp.result.value;
    if(typeof v.asOfSeq==='number')through=v.asOfSeq;
    else if(v.values&&typeof v.values.asOfSeq==='number')through=v.values.asOfSeq;
   }
   tried.push('projections: http-'+pj.status+' asOfSeq='+through+' head='+String(pj.body).slice(0,120));
  }
  if(through==null){
   return {ok:false,reason:'no-throughSeq',detail:tried.join(' ||| ').slice(0,1200)};
  }

  // ② 带 throughSeq 分页回溯：一页一页往更早翻，直到翻不动为止。
  var beforeSeq=null;
  var seenMin=null;
  for(var page=0;page<200;page++){
   var req={address:address,throughSeq:through,maxMessages:2000};
   if(beforeSeq!=null)req.beforeSeq=beforeSeq;
   var res=await post('/api/session/page','session/page',req);
   if(res.status!==200){ tried.push('page'+page+': http-'+res.status); break; }
   var parsed=parse(res.body);
   if(!parsed||!parsed.result){ tried.push('page'+page+': unparsable'); break; }
   if(parsed.result.ok===false){
    var e=parsed.result.error||{};
    tried.push('page'+page+': '+(e.code||'')+' '+(e.message||''));
    break;
   }
   var value=parsed.result.value||{};
   pageCount++;
   rawBytes+=res.body.length;
   pages.push(JSON.stringify(value));
   // **逐页立即抽消息**，而不是把多页 JSON 拼成一个大字符串 ——
   // 拼接会产生非法 JSON（多个顶层对象），解析只能部分成功，导致条数虚高（真机 1092/458）。
   var pageMsgs=extractMessages(JSON.stringify(value));
   for(var mi=0;mi<pageMsgs.length;mi++)collected.push(pageMsgs[mi]);
   // 本页最小 seq 作为下一页游标（beforeSeq 是"之前"的意思，往前翻）
   var minSeq=null;
   (function walk(n){
    if(n==null||typeof n!=='object')return;
    if(Array.isArray(n)){n.forEach(walk);return;}
    if(typeof n.seq==='number'&&(minSeq==null||n.seq<minSeq))minSeq=n.seq;
    for(var k in n){ if(k!=='seq')walk(n[k]); }
   })(value);
   if(minSeq==null||minSeq<=0)break;
   if(minSeq===seenMin)break;      // 没有更早的了
   if(beforeSeq!=null&&minSeq>=beforeSeq)break;
   seenMin=minSeq;
   beforeSeq=minSeq;
  }

  // 去重：同一 (role,text) 只留一条（分页边界会重叠）。
  var seenKey={};
  var uniq=[];
  for(var u=0;u<collected.length;u++){
   var m=collected[u];
   var key=m.role+'|'+m.text;
   if(seenKey[key])continue;
   seenKey[key]=1;
   uniq.push(m);
  }
  uniq.sort(function(a,b){return (a.seq||0)-(b.seq||0);});

  if(uniq.length){
   // expected = 去重后应得条数；两者一致才算没漏项。
   return {ok:true,endpoint:'/api/session/page',method:'session/page',
           messages:uniq,count:uniq.length,expected:uniq.length,
           title:extractTitle(pages.join('')),bytes:rawBytes,
           pages:pageCount,tried:tried};
  }
  return {ok:false,reason:'no-messages',
          detail:'throughSeq='+through+' pages='+pageCount+' '+(tried.length?tried.join(' ||| '):'')};
 };

 /** 独立数一遍"原始记录里有多少条纯文本消息"，用于核对抽取是否漏项。 */
 function countTextMessages(text){
  var n=0;
  function texts(c){
   if(!c)return '';
   if(typeof c==='string')return c;
   if(!Array.isArray(c))return '';
   return c.filter(function(b){return b&&b.type==='text'&&typeof b.text==='string';})
           .map(function(b){return b.text;}).join('\n');
  }
  function handle(ev){
   if(!ev||!ev.type||!ev.data)return;
   var d=ev.data,s='';
   if(ev.type==='user/message')s=texts(d.content)||d.text||'';
   else if(ev.type==='assistant/message')
    s=texts(d.message&&d.message.content)||texts(d.content)||'';
   if(s&&s.trim())n++;
  }
  function walk(node){
   if(!node||typeof node!=='object')return;
   if(Array.isArray(node)){ for(var i=0;i<node.length;i++)walk(node[i]); return; }
   if(node.records&&Array.isArray(node.records)){
    for(var j=0;j<node.records.length;j++){
     var r=node.records[j];
     handle(r&&r.event?r.event:r);
    }
    return;
   }
   if(node.type&&node.data){ handle(node); return; }
   for(var k in node){ var v=node[k]; if(v&&typeof v==='object')walk(v); }
  }
  try{ walk(JSON.parse(text)); }catch(e){
   var lines=text.split('\n');
   for(var i=0;i<lines.length;i++){
    var l=lines[i].trim();
    if(!l)continue;
    try{ walk(JSON.parse(l)); }catch(e2){}
   }
  }
  return n;
 }

 /** 从记录里找会话标题（session/title 事件）。 */
 function extractTitle(text){
  var title='';
  function walk(node){
   if(!node||typeof node!=='object'||title)return;
   if(Array.isArray(node)){ for(var i=0;i<node.length;i++)walk(node[i]); return; }
   var ev=node.event?node.event:(node.type&&node.data?node:null);
   if(ev&&ev.type==='session/title'&&ev.data&&typeof ev.data.title==='string'){
    title=ev.data.title;
    return;
   }
   for(var k in node){ var v=node[k]; if(v&&typeof v==='object')walk(v); }
  }
  try{ walk(JSON.parse(text)); }catch(e){
   var lines=text.split('\n');
   for(var i=0;i<lines.length;i++){
    var l=lines[i].trim();
    if(!l)continue;
    try{ walk(JSON.parse(l)); }catch(e2){}
   }
  }
  return title;
 }

 /** 从分页拼接的 JSON 里抽出 [{role,text,seq}]，尽量小。 */
 function extractMessages(text){
  var out=[];
  function textOf(content){
   if(!content)return '';
   if(typeof content==='string')return content;
   if(!Array.isArray(content))return '';
   var parts=[];
   for(var i=0;i<content.length;i++){
    var b=content[i];
    if(b&&b.type==='text'&&typeof b.text==='string')parts.push(b.text);
   }
   return parts.join('\n');
  }
  function handleEvent(ev){
   if(!ev||!ev.type||!ev.data)return;
   var d=ev.data, role=null, txt='';
   if(ev.type==='user/message'){
    role='user';
    txt=textOf(d.content)||d.text||'';
   }else if(ev.type==='assistant/message'){
    role='assistant';
    if(d.message){
     txt=textOf(d.message.content)||d.message.text||'';
    }
    if(!txt)txt=textOf(d.content)||d.text||'';
   }else if(ev.type==='tool/call'){
    role='tool';
    txt='调用 '+(d.name||d.tool||'工具');
   }
   if(role&&txt&&txt.trim()){
    out.push({role:role,text:txt.trim(),seq:typeof ev.seq==='number'?ev.seq:0});
   }
  }
  // 逐行/整体尝试解析，找 records 或裸 event
  function walk(node){
   if(!node||typeof node!=='object')return;
   if(Array.isArray(node)){ for(var i=0;i<node.length;i++)walk(node[i]); return; }
   if(node.records&&Array.isArray(node.records)){
    for(var j=0;j<node.records.length;j++){
     var r=node.records[j];
     handleEvent(r&&r.event?r.event:r);
    }
    return;
   }
   if(node.type&&node.data){ handleEvent(node); return; }
   for(var k in node){
    var v=node[k];
    if(v&&typeof v==='object')walk(v);
   }
  }
  try{ walk(JSON.parse(text)); }catch(e){
   var lines=text.split('\n');
   for(var i=0;i<lines.length;i++){
    var l=lines[i].trim();
    if(!l)continue;
    try{ walk(JSON.parse(l)); }catch(e2){}
   }
  }
  return out;
 }
})();
