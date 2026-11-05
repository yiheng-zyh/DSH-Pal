(function(){
 // 精准探测 session/page —— DSH 源码里明确写着的接口：
 //   "session/page — Read one cold-safe, message-aligned Session history page."
 // 这就是完整消息接口。之前混在回退链里，失败原因被日志截断看不见，
 // 现在单独探测、单独回报，每次尝试都记一条。
 if(window.__DSH_ARCHIVE_FETCH__)return;

 function body(method,args){
  return JSON.stringify({
   type:'client-request',
   rpcId:'archive-'+Date.now(),
   method:method,
   payload:{args:{request:args}}
  });
 }

 async function probe(ep,method,args){
  try{
   var r=await fetch(ep,{
    method:'POST',
    headers:{'content-type':'application/json'},
    credentials:'same-origin',
    body:body(method,args)
   });
   var t='';
   try{ t=await r.text(); }catch(e){}
   return {status:r.status,body:t};
  }catch(e){
   return {status:0,body:'',err:String(e&&e.message)};
  }
 }

 window.__DSH_ARCHIVE_PROBE_PAGE__=async function(sessionId){
  var out=[];
  var eps=[
   ['/api/session/page','session/page'],
   ['/sidebar/api/session.page','session/page']
  ];
  var argSets=[
   ['plain',{sessionId:sessionId}],
   ['cwd',{sessionId:sessionId,cwd:''}],
   ['cursor',{sessionId:sessionId,cursor:{type:'start'}}],
   ['from',{sessionId:sessionId,from:{type:'start'}}],
   ['limit',{sessionId:sessionId,limit:1000}]
  ];
  for(var i=0;i<eps.length;i++){
   for(var j=0;j<argSets.length;j++){
    var res=await probe(eps[i][0],eps[i][1],argSets[j][1]);
    var snippet=(res.body||'').replace(/\s+/g,' ').slice(0,220);
    out.push(eps[i][0]+' arg='+argSets[j][0]+' -> '+res.status+' '+(res.err?res.err+' ':'')+snippet);
   }
  }
  return out;
 };
})();
