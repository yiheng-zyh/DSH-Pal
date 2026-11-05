(function () {
  if (window.top !== window || window.__DSH_FILE_SEND__) return;
  var authority = '__DSH_FILE_AUTHORITY__';
  var exports = new Map();
  window.__DSH_FILE_DROP__ = function(token) { exports.delete(token); };
  window.__DSH_FILE_SEND__ = async function(token) {
    var blob = exports.get(token), success = false;
    if (!blob) { DshFiles.endBlob(token, false); return; }
    try {
      for (var offset = 0; offset < blob.size; offset += 49152) {
        var bytes = new Uint8Array(await blob.slice(offset, offset + 49152).arrayBuffer());
        if (!DshFiles.writeChunk(token, btoa(String.fromCharCode.apply(null, bytes)))) throw Error('write failed');
      }
      success = true;
    } catch (_) {} finally { DshFiles.endBlob(token, success); exports.delete(token); }
  };
  function prepare(anchor) {
    if (!anchor || !anchor.hasAttribute('download') || !/^(blob:|data:)/.test(anchor.href)) return false;
    // Retain the Blob before the page immediately revokes its object URL.
    fetch(anchor.href).then(function(r) { return r.blob(); }).then(function(blob) {
      var token = crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(36).slice(2);
      exports.set(token, blob);
      if (!DshFiles.offerBlob(authority, token, anchor.download || 'dsh-export.zip', blob.type || 'application/octet-stream', blob.size)) exports.delete(token);
    }).catch(function() { console.error('DSH file export could not be prepared'); });
    return true;
  }
  var nativeClick = HTMLAnchorElement.prototype.click;
  // Detached anchors never bubble through document. Session exports use this path.
  HTMLAnchorElement.prototype.click = function() {
    if (prepare(this)) return;
    return nativeClick.apply(this, arguments);
  };
  document.addEventListener('click', function(event) {
    var anchor = event.target instanceof Element && event.target.closest('a[href]');
    if (prepare(anchor)) event.preventDefault();
  }, true);
})();
