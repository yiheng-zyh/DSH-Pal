;(function() {
  if (window.top !== window || window.__DSH_REMOTE_FILES__) return;
  var dialog = null, sequence = 0;
  async function rpc(session, method, path, options) {
    if (method === 'list' && !path) path = '.';
    var args = {workspaceFileScopeId: session, path: path};
    if (options) args.options = options;
    var response = await fetch('/api/workspaceFiles/' + method + (method === 'stat' ? '?pal_file_metadata=1' : ''), {
      method: 'POST', credentials: 'same-origin',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({type: 'client-request', rpcId: 'pal-file-' + (++sequence),
        method: 'workspaceFiles/' + method, payload: {args: args}}),
      signal: AbortSignal.timeout(45000)
    });
    if (!response.ok) throw Error('文件请求失败（HTTP ' + response.status + '）');
    var metadata, data;
    if ((response.headers.get('content-type') || '').includes('multipart/form-data')) {
      var form = await response.formData();
      metadata = JSON.parse(await text(form.get('metadata')));
      var attachment = (metadata.attachments || []).find(function(item) {
        return item.codec === 'bytes' && JSON.stringify(item.path) === '["data"]';
      });
      if (attachment) {
        var part = form.get(attachment.part);
        if (part && typeof part.arrayBuffer === 'function') data = new Uint8Array(await part.arrayBuffer());
      }
    } else metadata = await response.json();
    var result = metadata && metadata.result;
    if (!result || !result.ok) {
      var code = result && result.error && result.error.code || '';
      if (/not-found/.test(code)) throw Error('文件已移动或不存在，请刷新列表');
      if (/not-regular|symlink|outside/.test(code)) throw Error('当前文件类型或路径不支持读取');
      if (/unavailable|not-found-method|unknown/.test(code)) throw Error('当前 DSH 未提供文件读取接口');
      throw Error('无法读取文件，请检查连接和电脑端文件权限');
    }
    return {value: result.value, data: data};
  }
  async function text(value) {
    return typeof value === 'string' ? value : value && typeof value.text === 'function' ? value.text() : '';
  }
  function selectedSession() {
    var current = clientService('uiSession');
    var key = current && current.adapter.current.getSnapshot().key;
    if (typeof key === 'string' && key) return key;
    var rows = document.querySelectorAll('[role="treeitem"][aria-selected="true"][data-row-key]');
    if (rows.length !== 1) return null;
    var key = rows[0].getAttribute('data-row-key');
    return key.startsWith('session:') ? key.slice(8) : null;
  }
  function clientService(name) {
    try {
      var modules = window.__dshSidebarModuleSystem__;
      var entry = modules && modules.entries.managed.get('@deepseek-ai/dsh-client-ui-chat');
      return entry && entry.ctx.get(name);
    } catch (_) { return null; }
  }
  function button(label, action) {
    var item = document.createElement('button');
    item.type = 'button'; item.textContent = label;
    item.style.cssText = 'font:inherit;color:inherit;background:transparent;border:1px solid #8886;border-radius:22px;padding:8px 16px;text-align:left;overflow-wrap:anywhere;min-height:44px;flex-shrink:0;cursor:pointer';
    item.onclick = action;
    return item;
  }
  function fileDetails(value) {
    var date = typeof value.modifiedAt === 'string' ? new Date(value.modifiedAt) : null;
    var time = date && Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
    }) : null;
    var bytes = value.bytes === undefined ? value.size : value.bytes;
    var size = Number.isSafeInteger(bytes) && bytes >= 0 ?
      (bytes < 1024 ? bytes + ' B' : bytes < 1048576 ? (bytes / 1024).toFixed(1) + ' KB' : (bytes / 1048576).toFixed(1) + ' MB') : '';
    return (time ? '修改于 ' + time : '电脑端未提供修改时间') + (size ? ' · ' + size : '');
  }
  function close() {
    if (dialog && dialog.fileObserver) dialog.fileObserver.disconnect();
    if (dialog && dialog.fileRequest) dialog.fileRequest.abort();
    if (dialog && dialog.sessionWatch) dialog.sessionWatch.disconnect();
    if (dialog && dialog.stopSessionWatch) dialog.stopSessionWatch();
    if (dialog) dialog.remove();
    dialog = null;
  }
  function open(file) {
    if (dialog) return;
    var session = file && file.session || selectedSession();
    var host = document.createElement('dialog');
    host.setAttribute('aria-label', '下载文件到手机');
    host.setAttribute('data-pal-file-dialog', '');
    var palette = {surface: '#24272e', foreground: '#f4f6fa', muted: '#c1c6d0'};
    try {
      var nativePalette = JSON.parse(DshFiles.localAppearancePalette());
      if ([nativePalette.surface, nativePalette.foreground, nativePalette.muted].every(function(color) { return /^#[a-f0-9]{6}$/i.test(color); })) palette = nativePalette;
    } catch (_) {}
    host.style.cssText = 'box-sizing:border-box;width:calc(100% - 32px);max-width:520px;max-height:80dvh;border:1px solid #8886;border-radius:20px;padding:20px;font-family:inherit;font-size:15px;line-height:1.5;box-shadow:0 16px 64px #0008';
    host.style.setProperty('background', palette.surface, 'important');
    host.style.setProperty('background-image', 'none', 'important');
    host.style.setProperty('color', palette.foreground, 'important');
    host.style.setProperty('opacity', '1', 'important');
    var backdrop = document.createElement('style');
    backdrop.textContent = '[data-pal-file-dialog]::backdrop{background:rgba(0,0,0,.65)!important}' +
      '[data-pal-file-dialog][open]{display:flex!important;flex-direction:column!important;max-height:calc(100dvh - 32px)!important;overflow:hidden!important}' +
      '[data-pal-file-dialog] [data-pal-file-list]{min-height:48px;flex:1 1 auto}' +
      '[data-pal-file-dialog] button:active{background:#8882!important}' +
      '@media(orientation:landscape) and (max-height:500px){[data-pal-file-dialog][open]{width:calc(100% - 40px)!important;max-width:760px!important;padding:12px 16px!important;gap:0!important}' +
      '[data-pal-file-dialog] h2{font-size:16px!important;margin-bottom:2px!important}' +
      '[data-pal-file-dialog] [data-pal-file-option]{min-height:32px!important;margin:0!important}' +
      '[data-pal-file-dialog] [role=status]{margin:2px 0!important}}';
    host.append(backdrop);
    var heading = document.createElement('h2');
    heading.tabIndex=-1;
    heading.style.outline='none';
    heading.textContent = file ? '下载文件到手机' : '当前对话的文件'; heading.style.cssText = 'font-size:18px;margin:0 0 8px';
    var note = document.createElement('p');
    note.textContent = '点文件名在侧栏预览，点「下载」保存到手机 Download 文件夹。';
    note.style.cssText = 'margin:0 0 12px;font-size:13px';
    note.style.color = palette.muted;
    var openOption = document.createElement('label');
    openOption.setAttribute('data-pal-file-option','');
    openOption.style.cssText = 'display:flex;align-items:center;gap:8px;min-height:44px;margin-right:auto;font-size:13px;cursor:pointer;white-space:nowrap';
    openOption.title = '记住此选择，下次下载继续使用';
    var openCheck = document.createElement('input'); openCheck.type = 'checkbox';
    openCheck.checked = window.__DSH_FILE_OPTIONS__ ? window.__DSH_FILE_OPTIONS__.getOpenAfter() : true;
    openCheck.style.cssText = 'width:20px;height:20px;flex-shrink:0';
    openCheck.addEventListener('change', function() {
      if (window.__DSH_FILE_OPTIONS__) window.__DSH_FILE_OPTIONS__.setOpenAfter(openCheck.checked);
    });
    openOption.append(openCheck, document.createTextNode('下载后打开'));
    var status = document.createElement('p');
    status.setAttribute('role', 'status');
    status.style.cssText = 'margin:8px 0;overflow-wrap:anywhere;font-size:13px';
    var list = document.createElement('div');
    list.setAttribute('data-pal-file-list','');
    list.style.cssText = 'display:flex;flex-direction:column;gap:8px;max-height:48dvh;overflow:auto;overscroll-behavior:contain;touch-action:pan-y';
    var controls = document.createElement('div');
    controls.style.cssText = 'display:flex;align-items:center;justify-content:flex-end;gap:8px;margin-top:12px;flex-shrink:0';
    var generation = 0, busy = false;
    function message(value) { status.textContent = value; }
    function fail(error) {
      message(error.name === 'TimeoutError' ? '读取文件超时，请检查连接后点「刷新」重试' : error.message || '操作失败，请重试');
    }
    function fileItem(path, label, value) {
      var item = document.createElement('div');
      item.setAttribute('data-pal-session-file', '');
      item.style.cssText = 'flex-shrink:0;border:1px solid #8886;border-radius:12px;padding:12px;overflow-wrap:anywhere';
      var name = path.replace(/\\/g, '/').split('/').pop();
      var title = button(name, function() {
        try {
          if (selectedSession() !== session) throw Error('对话已切换，请重新打开文件列表');
          var sidebar = clientService('sidebarRight');
          if (!sidebar || typeof sidebar.openResource !== 'function') throw Error('当前 DSH 未提供侧栏预览，请更新电脑端后重试');
          var normalized = path.replace(/\\/g, '/').replace(/^(?:\.\/)+/, '');
          var address = 'dsh-resource://file/session/' + encodeURIComponent(session) + '/' + normalized.split('/').map(encodeURIComponent).join('/');
          sidebar.openResource(address);
          close();
          if (window.__DSH_PHONE_RIGHT__) window.__DSH_PHONE_RIGHT__.open();
        } catch (error) { fail(error); }
      });
      title.setAttribute('aria-label', '在侧栏预览 ' + name);
      title.style.cssText += ';display:block;width:100%;border:0;padding:0;font-size:15px;line-height:1.5;min-height:0;font-weight:600';
      item.append(title);
      var description = document.createElement('div');
      description.textContent = value.description || path.replace(/\\/g, '/').split('/').slice(0, -1).join('/');
      description.style.cssText = 'font-size:12px;line-height:1.6;margin-top:4px';
      description.style.color = palette.muted;
      if (description.textContent) item.append(description);
      var detail = document.createElement('span');
      detail.setAttribute('data-pal-file-meta', '');
      detail.textContent = value.modifiedAt ? fileDetails(value) : '正在读取修改时间…';
      detail.style.cssText = 'display:block;font-size:12px;line-height:1.6;flex:1;min-width:0';
      detail.style.color = palette.muted;
      var download = button('下载', function() { save(path); });
      download.setAttribute('aria-label', label);
      download.style.cssText += ';padding:6px 16px;min-height:40px;border-radius:20px;font-size:13px;text-align:center';
      var footer = document.createElement('div');footer.style.cssText='display:flex;gap:12px;align-items:center;margin-top:10px';
      footer.append(detail,download);item.append(footer);
      return {item: item, detail: detail, path: path};
    }
    function loadFileDetails(items, token) {
      var queue = [], active = 0;
      async function next() {
        if (active >= 3 || !queue.length || dialog !== host || token !== generation) return;
        var entry = queue.shift();
        active++;
        try {
          var value = (await rpc(session, 'stat', entry.path)).value;
          if (dialog === host && token === generation) entry.detail.textContent = fileDetails(value);
        } catch (_) {
          if (dialog === host && token === generation) entry.detail.textContent = '修改时间读取失败，可重试刷新';
        } finally { active--; next(); }
      }
      var pending = new Map(items.map(function(entry) { return [entry.item, entry]; }));
      host.fileObserver = new IntersectionObserver(function(records) {
        if (dialog !== host || token !== generation) return;
        records.forEach(function(record) {
          if (!record.isIntersecting) return;
          host.fileObserver.unobserve(record.target);
          queue.push(pending.get(record.target));
          pending.delete(record.target);
          next();
        });
      }, {root: list, rootMargin: '120px'});
      items.forEach(function(entry) { host.fileObserver.observe(entry.item); });
    }
    async function save(path) {
      if (busy) return;
      if (selectedSession() !== session) { message('对话已切换，请重新打开文件列表'); return; }
      busy = true;
      try {
        message('正在检查文件…');
        var stat = (await rpc(session, 'stat', path)).value;
        if (dialog !== host || selectedSession() !== session) return;
        if (!Number.isSafeInteger(stat.bytes) || stat.bytes < 0 || stat.bytes > 536870912) {
          throw Error('当前支持保存不超过 512 MB 的文件');
        }
        var filePath = stat.absolutePath;
        var originalOrigin = location.origin;
        window.__DSH_OFFER_REMOTE_FILE__({
          size: stat.bytes,
          context: {session: session, conversation: (function() {
            try { return clientService('sessions').list.getSnapshot().byId[session].title || ''; } catch (_) { return ''; }
          })()},
          read: async function(offset, length) {
            if (location.origin !== originalOrigin) throw Error('连接已变化');
            var answer = await rpc(session, 'readBytes', filePath, {range: {offset: offset, length: length}});
            var value = answer.value;
            if (value.absolutePath !== filePath || value.version !== stat.version ||
                value.offset !== offset || !answer.data || answer.data.length !== length) {
              throw Error('下载期间文件发生变化或内容不完整，请重试');
            }
            return answer.data;
          }
        }, filePath.replace(/\\/g, '/').split('/').pop());
        close();
      } catch (error) { fail(error); }
      finally { busy = false; }
    }
    async function loadConversation() {
      var token = ++generation;
      if (host.fileObserver) host.fileObserver.disconnect();
      if (host.fileRequest) host.fileRequest.abort();
      host.fileRequest = new AbortController();
      message('正在读取当前对话的文件记录…');
      list.replaceChildren();
      try {
        var files = await window.__DSH_SESSION_FILES__.list(session, host.fileRequest.signal, function(items) {
          if (dialog === host && token === generation) message('正在读取历史记录，已找到 ' + items.length + ' 个文件…');
        });
        if (dialog !== host || token !== generation) return;
        var rows = files.map(function(entry) {
          var row = fileItem(entry.path, '下载 · ' + entry.path.replace(/\\/g, '/').split('/').pop(), entry);
          list.append(row.item);
          return row;
        });
        loadFileDetails(rows, token);
        message(files.length ? '当前对话共 ' + files.length + ' 个文件' : '这条对话还没有记录到生成或交付的文件。');
      } catch (error) { if (dialog === host && token === generation) fail(error); }
    }
    controls.append(openOption, button('刷新', function() {
      if (!session) return;
      if (file && file.path) {
        loadFileDetails([row], generation);
      } else loadConversation();
    }), button('关闭', close));
    host.append(heading, note, status, list, controls);
    dialog = host;
    document.body.append(host);
    host.addEventListener('cancel', function(event) { event.preventDefault(); close(); });
    host.showModal();
    heading.focus({preventScroll:true});
    if (session && file && file.path) {
      message(file.path);
      var row = fileItem(file.path, '下载到手机', {});
      list.append(row.item);
      loadFileDetails([row], generation);
    } else if (session) loadConversation();
    else message('请先在主界面打开一条对话，再查看它生成的文件。');
    host.sessionWatch = new MutationObserver(function() {
      if (session && selectedSession() !== session) close();
    });
    host.sessionWatch.observe(document, {subtree: true, attributes: true, attributeFilter: ['aria-selected']});
    var currentSource = clientService('uiSession');
    if (currentSource) host.stopSessionWatch = currentSource.adapter.current.subscribe(function() {
      if (session && selectedSession() !== session) close();
    });
  }
  function previewFile(node) {
    var raw = node.getAttribute('data-textpreview-url') || '';
    var prefix = 'dsh-resource://file/session/';
    if (!raw.startsWith(prefix)) return null;
    try {
      var parts = raw.split(/[?#]/)[0].slice(prefix.length).split('/');
      return {session: decodeURIComponent(parts.shift()), path: parts.map(decodeURIComponent).join('/')};
    } catch (_) { return null; }
  }
  function enhance(root) {
    if (!(root instanceof Element) || root.closest('[data-pal-download]')) return;
    var nodes = Array.from(root.querySelectorAll('[data-presented-file],[data-textpreview-url]'));
    if (root.matches('[data-presented-file],[data-textpreview-url]')) nodes.push(root);
    nodes.forEach(function(node) {
      var preview = node.hasAttribute('data-textpreview-url');
      var seat = preview ? node.querySelector('[data-textpreview-path]')?.parentElement :
        node.querySelector('[class$="_actions"]');
      if (!seat || seat.querySelector('[data-pal-download]')) return;
      var control = button('下载', function() {
        var file;
        if (preview) file = previewFile(node);
        else {
          var path = node.querySelector('button[title]')?.getAttribute('title');
          var session = selectedSession();
          if (path && session) file = {session: session, path: path};
        }
        open(file);
      });
      control.setAttribute('data-pal-download', '');
      control.setAttribute('aria-label', '下载文件到手机');
      control.style.cssText += ';position:relative;z-index:3;pointer-events:auto;flex-shrink:0;min-height:36px;padding:6px 10px;font-size:12px';
      seat.append(control);
    });
  }
  new MutationObserver(function(records) {
    records.forEach(function(record) {
      record.addedNodes.forEach(function(node) {
        if (node instanceof Element) {
          enhance(node);
          var owner = node.closest('[data-presented-file],[data-textpreview-url]');
          if (owner) enhance(owner);
        }
      });
    });
  }).observe(document, {childList: true, subtree: true});
  if (document.documentElement) enhance(document.documentElement);
  window.__DSH_REMOTE_FILES__ = {open: open};
})();
