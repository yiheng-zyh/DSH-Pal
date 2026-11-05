;(function(){
  if(window.top!==window||window.__DSH_PROBE_PAGE__)return;
  window.__DSH_PROBE_PAGE__=async function(origin,id){
    var start=Date.now(),controller=new AbortController(),timer;
    function report(status,error){try{DshNative.onBridgeMessage(JSON.stringify({kind:'page-probe-result',payload:{id:id,status:status,error:error,elapsedMs:Date.now()-start}}));}catch(_){}}
    if(location.origin!==origin){report(null,'context-changed');return;}
    timer=setTimeout(function(){controller.abort();},12000);
    try{
      // Same Chromium transport/cookie jar as the page. No grant URL or body read.
      var response=await fetch(origin+'/',{method:'HEAD',credentials:'same-origin',cache:'no-store',signal:controller.signal});
      report(response.status||null,response.status?null:'opaque-response');
    }catch(error){report(null,error.name==='AbortError'?'timeout':'browser-network-error');}
    finally{clearTimeout(timer);}
  };
})();
