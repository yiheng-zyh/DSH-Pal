(function(){
 // 抓「页面自己实际调用了哪些接口」—— 这是找到完整消息接口最可靠的方式，
 // 不用猜路径：页面渲染对话时必然调用过它，我们只需记录 URL。
 if(window.__DSH_ARCHIVE_SPY__)return;

 var hits={};
 var nativeFetch=window.fetch;
 if(typeof nativeFetch==='function'){
  window.fetch=function(input,init){
   try{
    var url=typeof input==='string'?input:(input&&input.url)||'';
    if(/\/api\//.test(url)){
     var key=url.split('?')[0];
     hits[key]=(hits[key]||0)+1;
    }
   }catch(e){}
   return nativeFetch.apply(this,arguments);
  };
 }
 // XHR 也记（有些客户端用 XHR）
 var nativeOpen=window.XMLHttpRequest&&window.XMLHttpRequest.prototype.open;
 if(nativeOpen){
  window.XMLHttpRequest.prototype.open=function(method,url){
   try{
    var s=String(url);
    if(/\/api\//.test(s)){
     var key=s.split('?')[0];
     hits[key]=(hits[key]||0)+1;
    }
   }catch(e){}
   return nativeOpen.apply(this,arguments);
  };
 }

 window.__DSH_ARCHIVE_SPY__={
  hits:function(){return hits;},
  reset:function(){hits={};return true;}
 };
})();
