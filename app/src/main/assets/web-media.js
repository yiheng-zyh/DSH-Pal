(function () {
  if(window.top!==window||window.__DSH_WEB_CAPABILITIES__)return;
  window.__DSH_WEB_CAPABILITIES__=true;
  var recordings=new Set(),uploads=0;
  window.__DSH_NATIVE_ACTIVITY__={busy:function(){return uploads>0||Array.from(recordings).some(function(r){return r.state!=='inactive';});}};
  function report(feature,phase,detail){try{DshNative.onBridgeMessage(JSON.stringify({kind:'web-capability',payload:{feature:feature,phase:phase,detail:String(detail||'')}}));}catch(_){}}
  var devices=navigator.mediaDevices;
  if(devices&&devices.getUserMedia&&!devices.getUserMedia.__dshMobileWrapped){
    var original=devices.getUserMedia;
    function wrapped(constraints){
      var audio=constraints&&!!constraints.audio;
      if(audio)report('microphone','requested','');
      var promise;
      try{promise=original.apply(this,arguments);}catch(error){if(audio)report('microphone','error',error.name);throw error;}
      if(!audio)return promise;
      return promise.then(function(stream){report('microphone','opened',stream.getAudioTracks().length);return stream;},function(error){report('microphone','error',error&&error.name||'UnknownError');throw error;});
    }
    wrapped.__dshMobileWrapped=true;devices.getUserMedia=wrapped;
  }
  // Observe recording stages, without reading chunks or changing recorder options.
  if(window.MediaRecorder){
    ['start','stop'].forEach(function(method){
      var original=MediaRecorder.prototype[method];if(!original||original.__dshMobileWrapped)return;
      function wrapped(){
        var audio=this.stream&&this.stream.getAudioTracks().length>0;
        try{var value=original.apply(this,arguments);if(audio){if(method==='start')recordings.add(this);else recordings.delete(this);report('recording',method==='start'?'started':'stopped','');}return value;}
        catch(error){if(audio)report('recording','error',error.name);throw error;}
      }
      wrapped.__dshMobileWrapped=true;MediaRecorder.prototype[method]=wrapped;
    });
  }
  document.addEventListener('change',function(event){
    var input=event.target;if(!(input instanceof HTMLInputElement)||input.type!=='file')return;
    var files=Array.from(input.files||[]);
    report('file-upload','selected','count='+files.length+' bytes='+files.reduce(function(total,file){return total+file.size;},0));
  },true);
  // Keep the official upload transport, body, cancellation and retry policy intact.
  var fetch=window.fetch;
  if(fetch&&!fetch.__dshCapabilityWrapped){
    function observed(input,init){
      var upload=false;
      try{var url=new URL(typeof input==='string'?input:input.url||input.href,location.href);upload=url.origin===location.origin&&/^\/(?:remote\/)?api\/session\/uploadFileBinary$/.test(url.pathname);}catch(_){}
      if(upload){uploads++;report('file-upload','requested','');}
      var pending=fetch.apply(this,arguments);if(!upload)return pending;
      return pending.then(function(response){uploads--;report('file-upload',response.ok?'completed':'httpError','HTTP'+response.status);return response;},function(error){uploads--;report('file-upload',error&&error.name==='AbortError'?'cancelled':'error',error&&error.name);throw error;});
    }
    observed.__dshCapabilityWrapped=true;window.fetch=observed;
  }
})();
