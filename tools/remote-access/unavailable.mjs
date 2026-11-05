import {createHash} from 'node:crypto';

// Only a bare, repeatable navigation can recover automatically. Never replay
// pair/grant queries, POSTs, resource downloads, or arbitrary plugin routes.
export function canRecoverLanding(method,url){
  return method==='GET'&&(url==='/'||url==='/pair-app');
}
export const recoveryScript=`(function(){
  var key='dsh-local-unavailable-recovery',attempts=0;
  try{var previous=JSON.parse(sessionStorage.getItem(key)||'null');if(previous&&previous.until>Date.now())attempts=previous.count||0;if(attempts>=3)return;}catch(_){}
  var probes=0;
  function check(){
    if(++probes>12)return;
    var controller=new AbortController(),timeout=setTimeout(function(){controller.abort();},4000);
    fetch('/api/pair/status',{credentials:'same-origin',cache:'no-store',signal:controller.signal})
      .then(function(r){return r.ok?r.json():null;})
      .then(function(s){
        if(s&&s.ok===true&&typeof s.paired==='boolean'&&typeof s.phase==='string'){
          try{sessionStorage.setItem(key,JSON.stringify({count:attempts+1,until:Date.now()+120000}));}catch(_){}
          location.replace('/pair-app');return;
        }
        setTimeout(check,5000);
      }).catch(function(){setTimeout(check,5000);}).finally(function(){clearTimeout(timeout);});
  }
  setTimeout(check,2000);
})();`;
const hash=createHash('sha256').update(recoveryScript).digest('base64');
export function unavailableLanding(){
  return {
    headers:{'content-type':'text/html; charset=utf-8','cache-control':'no-store','retry-after':'5',
      'content-security-policy':`default-src 'none'; script-src 'sha256-${hash}'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'`},
    body:'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>正在等待电脑连接</title><h1>正在等待电脑连接</h1><p>电脑服务暂时未就绪，正在检查恢复。请保持电脑上的 DSH 运行。</p><p>恢复后将返回工作区。若仍未恢复，请稍后重试。</p><script>'+recoveryScript+'</script></html>'
  };
}
