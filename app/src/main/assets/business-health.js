(function(){
  if(window.top!==window||window.__DSH_BUSINESS_HEALTH__)return;
  var pending=new Set(),slow=new Set(),resources=new Set(),restricted=new Set(),loading=new Map(),samples=[],lastSuccess=0,lastLatency=0,sequence=0,writes=0;
  window.__DSH_PENDING_WRITES__=function(){return writes>0;};
  function snapshot(){return{sampleCount:samples.length,failurePercent:samples.length?Math.round(samples.filter(x=>!x).length*100/samples.length):-1,slowPending:slow.size,resourceFailures:resources.size,restrictedPlugins:restricted.size,lastSuccessAgeMs:lastSuccess?Math.round(performance.now()-lastSuccess):-1,lastLatencyMs:lastLatency,loadingResources:loading.size,oldestResourceMs:loading.size?Math.round(performance.now()-Math.min.apply(null,Array.from(loading.values()))):0};}
  function publish(){try{DshNative.onBridgeMessage(JSON.stringify({kind:'business-health',payload:snapshot()}));}catch(_){}}
  function record(ok){samples.push(ok);if(samples.length>10)samples.shift();}
  function category(raw){try{var url=new URL(raw,location.href),path=url.pathname;if(url.origin!==location.origin)return null;
    if(/^\/(?:remote\/)?api\//.test(path)&&!/(?:\/stream|\/events|restart-button|telemetry|ingest)(?:[/.]|$)/.test(path))return 'api';
    if(/\.(?:m?js|css)$/.test(path)||/^\/(?:remote\/)?plugins\/$/.test(path)&&url.search.startsWith('??'))return 'resource';
    if(/^\/(?:remote\/)?plugins\//.test(path)&&!path.endsWith('/events'))return 'plugin';
  }catch(_){}return null;}
  var original=window.fetch;
  if(original){window.fetch=function(input,init){
    var raw=typeof input==='string'?input:input&&input.url||input&&input.href,kind=category(raw);
    if(!kind)return original.apply(this,arguments);
    raw=new URL(raw,location.href).href;
    var id=++sequence,start=performance.now(),method=String(init&&init.method||input&&input.method||'GET').toUpperCase(),write=kind!=='resource'&&!['GET','HEAD'].includes(method),timer;
    if(write)writes++;
    if(kind==='api'){pending.add(id);timer=setTimeout(function(){if(pending.has(id)){slow.add(id);publish();}},8000);}
    function complete(response,error){
      clearTimeout(timer);pending.delete(id);slow.delete(id);if(write)writes--;
      var elapsed=performance.now()-start;
      if(kind==='api'){
        if(error){if(error.name!=='AbortError')record(false);}
        else{var ok=response.status>=200&&response.status<400;record(ok);if(ok){lastSuccess=performance.now();lastLatency=Math.round(elapsed);}}
      }else if(kind==='plugin'){
        var endpoint=new URL(raw,location.href).pathname;
        if(response&&(response.status===401||response.status===403)){if(restricted.size<100)restricted.add(endpoint);}
        else if(response)restricted.delete(endpoint);
      }else if(error&&error.name!=='AbortError'||response&&!response.ok){if(resources.size<50)resources.add(raw);}else if(response)resources.delete(raw);
      publish();
    }
    var promise;try{promise=original.apply(this,arguments);}catch(error){complete(null,error);throw error;}
    return promise.then(function(response){
      complete(response,null);
      // Fetch resolves at headers. Track the body's real consumer too: a hung
      // JSON download must not look healthy just because headers arrived.
      if(kind==='api' && response.ok) ['json','text','blob','arrayBuffer','formData'].forEach(function(name){
        var read=response[name];if(typeof read!=='function')return;
        try{Object.defineProperty(response,name,{configurable:true,value:function(){
          var bodyId=++sequence,bodyStart=performance.now(),bodyTimer;
          pending.add(bodyId);if(write)writes++;
          bodyTimer=setTimeout(function(){if(pending.has(bodyId)){slow.add(bodyId);publish();}},8000);
          function end(error){clearTimeout(bodyTimer);pending.delete(bodyId);slow.delete(bodyId);if(write)writes--;
            if(!error){lastSuccess=performance.now();lastLatency=Math.round(performance.now()-start);record(true);}
            else if(error.name!=='AbortError')record(false);publish();}
          var result;try{result=read.apply(this,arguments);}catch(error){end(error);throw error;}
          return result.then(function(value){end(null);return value;},function(error){end(error);throw error;});
        }});}catch(_){}
      });
      return response;
    },function(error){complete(null,error);throw error;});
  };}
  function resourceNode(node){return node&&(node.tagName==='SCRIPT'||node.tagName==='LINK'&&/^(stylesheet|modulepreload)$/.test(node.rel));}
  function track(node){if(resourceNode(node)){var url=node.src||node.href;if(url&&category(url)==='resource'&&loading.size<256&&!loading.has(url))loading.set(url,performance.now());}}
  if(typeof MutationObserver!=='undefined')new MutationObserver(function(records){records.forEach(function(r){r.addedNodes.forEach(function(node){track(node);if(node.tagName==='HTML'||node.tagName==='HEAD')node.querySelectorAll('script[src],link[href]').forEach(track);});});}).observe(document,{childList:true,subtree:true});
  document.addEventListener('error',function(e){var node=e.target;if(resourceNode(node)){var url=node.src||node.href;loading.delete(url);if(resources.size<50)resources.add(url);publish();}},true);
  document.addEventListener('load',function(e){var node=e.target;if(resourceNode(node)){var url=node.src||node.href;loading.delete(url);resources.delete(url);publish();}},true);
  window.__DSH_BUSINESS_HEALTH__={snapshot:snapshot};
  setInterval(function(){if(!document.hidden)publish();},5000);publish();
})();
