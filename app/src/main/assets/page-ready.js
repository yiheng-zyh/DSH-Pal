;(function(){
  if(window.__DSH_REMOTE_HOST_GRANT__&&!window.__DSH_PAIR_REPORTED__&&window.DshNative){
    window.__DSH_PAIR_REPORTED__=true;
    DshNative.onBridgeMessage(JSON.stringify({kind:'pair-established'}));
  }
  if(!document.body)return false;
  if(!window.__DSH_REMOTE_HOST_GRANT__&&/^\/pair-(app|accept)$/.test(location.pathname)&&document.title==='Pairing link invalid'){
    if(!window.__DSH_AUTH_REPORTED__&&window.DshNative){
      window.__DSH_AUTH_REPORTED__=true;
      DshNative.onBridgeMessage(JSON.stringify({kind:'auth-lost'}));
    }
    return 'auth-required';
  }
  // Inspect actual editors, without serializing all conversation/plugin text.
  return Array.from(document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"]'))
    .some(function(el){var r=el.getBoundingClientRect();return r.width>0&&r.height>0;});
})();
