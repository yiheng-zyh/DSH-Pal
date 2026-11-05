(function(){
 // 用 session/list 拉**全部可见会话**（不是只读侧栏渲染出来的那几条）。
 //
 // 源码依据（asar 检索）：
 //   "session/list": {
 //     description: "Read all visible Session rows without resuming an Agent.",
 //     parameters: [{ name:"_request", jsonSchema:{ properties:{cursor:{type:"string"}},
 //                    required: [], additionalProperties:false } }],
 //     result: { description:"visible Session summaries ordered by activity.",
 //       items: [{ sessionId, updatedAt, running, blank, parentSessionId,
 //                 agentAvailable, formatStatus, origin, ... }] }
 //   }
 // 侧栏 DOM 只渲染可见/展开的会话（真机只读到 8 个，而工作区里有 160+），
 // 所以列表必须以本接口为准，DOM 只用来补标题。
 if(window.__DSH_ARCHIVE_LIST__)return;

 function rpc(method,args){
  return JSON.stringify({
   type:'client-request',
   rpcId:'archive-'+Date.now()+'-'+Math.floor(Math.random()*1e6),
   method:method,
   payload:{args:args||{}}
  });
 }

 /** 拉全部会话（自动翻页）。回调式返回，避免 evaluateJavascript 不等待 Promise。 */
 window.__DSH_ARCHIVE_LIST__=async function(){
  var all=[];
  var cursor=null;
  var seen={};
  for(var i=0;i<50;i++){
   var args={};
   if(cursor)args.cursor=cursor;
   var res;
   try{
    var r=await fetch('/api/session/list',{
     method:'POST',
     headers:{'content-type':'application/json'},
     credentials:'same-origin',
     body:rpc('session/list',{_request:args})
    });
    if(!r.ok)return {ok:false,reason:'http-'+r.status};
    var t=await r.text();
    res=JSON.parse(t);
   }catch(e){ return {ok:false,reason:'throw:'+(e&&e.message)}; }
   if(!res||!res.result)return {ok:false,reason:'unparsable'};
   if(res.result.ok===false){
    var err=res.result.error||{};
    return {ok:false,reason:(err.code||'')+' '+(err.message||'')};
   }
   var value=res.result.value||{};
   var items=value.items||[];
   for(var j=0;j<items.length;j++){
    var it=items[j];
    if(!it||!it.sessionId)continue;
    if(seen[it.sessionId])continue;
    seen[it.sessionId]=1;
    // 标题与工作区直接从接口取（源码 schema 确认：
    //   items[].projections.values.title            会话标题（最新 session/title，string|null）
    //   items[].projections.values.workingDirectory 该会话的工作目录
    //   items[].cwd                                 同上的顶层副本
    // 早先这里只取 id 与时间、标题留给侧栏 DOM 补 —— 但 DOM 补标题要在 JavaBridge
    // 线程里调 evaluateJavascript（非法线程），异常被吞掉，结果 340 条**全部没有标题**。
    // 接口本身就带标题，根本不需要 DOM。
    var proj=it.projections||{};
    var vals=proj.values||{};
    var title=typeof vals.title==='string'?vals.title:'';
    var cwd=typeof vals.workingDirectory==='string'?vals.workingDirectory:
            (typeof it.cwd==='string'?it.cwd:'');
    all.push({
     sessionId:it.sessionId,
     updatedAt:typeof it.updatedAt==='number'?it.updatedAt:0,
     running:!!it.running,
     blank:!!it.blank,
     parentSessionId:it.parentSessionId||'',
     origin:it.origin||'',
     title:title,
     cwd:cwd
    });
   }
   cursor=value.cursor||value.nextCursor||null;
   if(!cursor)break;
  }
  return {ok:true,count:all.length,sessions:all};
 };
})();
