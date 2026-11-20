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
    var rows = document.querySelectorAll('[role="treeitem"][aria-selected="true"][data-row-key]');
    if (rows.length !== 1) return null;
    var key = rows[0].getAttribute('data-row-key');
    return key.startsWith('session:') ? key.slice(8) : null;
  }
  function button(label, action) {
    var item = document.createElement('button');
    item.type = 'button'; item.textContent = label;
    item.style.cssText = 'font:inherit;color:inherit;background:transparent;border:1px solid #8886;border-radius:10px;padding:12px;text-align:left;overflow-wrap:anywhere;min-height:44px;flex-shrink:0;cursor:pointer';
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
    if (dialog) dialog.remove();
    dialog = null;
  }
  function open(file) {
    if (dialog) return;
    if (!file) {
      var previews = Array.from(document.querySelectorAll('[data-textpreview-url]')).filter(function(node) {
        var rect = node.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility === 'visible';
      });
      if (previews.length === 1) file = previewFile(previews[0]);
    }
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
    backdrop.textContent = '[data-pal-file-dialog]::backdrop{background:rgba(0,0,0,.65)!important}';
    host.append(backdrop);
    var heading = document.createElement('h2');
    heading.textContent = '下载文件到手机'; heading.style.cssText = 'font-size:18px;margin:0 0 8px';
    var note = document.createElement('p');
    note.textContent = '选择文件后，在系统窗口选择手机保存位置。';
    note.style.cssText = 'margin:0 0 12px;font-size:13px';
    note.style.color = palette.muted;
    var status = document.createElement('p');
    status.setAttribute('role', 'status');
    status.style.cssText = 'margin:8px 0;overflow-wrap:anywhere;font-size:13px';
    var list = document.createElement('div');
    list.style.cssText = 'display:flex;flex-direction:column;gap:8px;max-height:48dvh;overflow:auto;overscroll-behavior:contain;touch-action:pan-y';
    var controls = document.createElement('div');
    controls.style.cssText = 'display:flex;gap:8px;margin-top:14px';
    var currentPath = '', generation = 0, busy = false;
    function message(value) { status.textContent = value; }
    function fail(error) { message(error.message || '操作失败，请重试'); }
    function fileItem(path, label, value) {
      var item = button(label, function() { save(path); });
      item.setAttribute('aria-label', label);
      var title = document.createElement('span');
      title.textContent = label;
      title.style.display = 'block';
      var detail = document.createElement('span');
      detail.setAttribute('data-pal-file-meta', '');
      detail.textContent = value.modifiedAt ? fileDetails(value) : '正在读取修改时间…';
      detail.style.cssText = 'display:block;margin-top:6px;font-size:12px;line-height:1.6';
      detail.style.color = palette.muted;
      item.replaceChildren(title, detail);
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
      busy = true;
      try {
        message('正在检查文件…');
        var stat = (await rpc(session, 'stat', path)).value;
        if (!Number.isSafeInteger(stat.bytes) || stat.bytes < 0 || stat.bytes > 536870912) {
          throw Error('当前支持保存不超过 512 MB 的文件');
        }
        var filePath = stat.absolutePath;
        var originalOrigin = location.origin;
        window.__DSH_OFFER_REMOTE_FILE__({
          size: stat.bytes,
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
    async function browse(path) {
      var token = ++generation;
      if (host.fileObserver) host.fileObserver.disconnect();
      message('正在读取文件列表…');
      list.replaceChildren();
      try {
        var value = (await rpc(session, 'list', path)).value;
        if (dialog !== host || token !== generation) return;
        currentPath = value.path === '.' ? '' : value.path;
        if (currentPath) list.append(button('返回上一级', function() {
          browse(currentPath.split('/').slice(0, -1).join('/'));
        }));
        var files = [];
        value.entries.forEach(function(entry) {
          var child = (currentPath ? currentPath + '/' : '') + entry.name;
          if (entry.type === 'directory') list.append(button('文件夹 · ' + entry.name, function() { browse(child); }));
          if (entry.type === 'file') {
            var row = fileItem(child, '下载 · ' + entry.name, entry);
            list.append(row.item);
            if (!entry.modifiedAt) files.push(row);
          }
        });
        loadFileDetails(files, token);
        message((currentPath || '工作区') + (value.truncated ? ' · 列表较长，仅显示部分文件' : value.entries.length ? '' : ' · 空文件夹'));
      } catch (error) { if (dialog === host && token === generation) fail(error); }
    }
    var pathEntry = document.createElement('details');
    var summary = document.createElement('summary');
    summary.textContent = '按电脑文件路径下载';
    summary.style.cssText = 'padding:10px 0;cursor:pointer';
    var input = document.createElement('input');
    input.type = 'text'; input.placeholder = '粘贴电脑上的完整文件路径';
    input.setAttribute('aria-label', '电脑文件路径');
    input.style.cssText = 'box-sizing:border-box;width:100%;padding:10px;margin:0 0 8px;border:1px solid #8888;border-radius:8px;background:transparent;color:inherit;font:inherit';
    pathEntry.append(summary, input, button('下载此路径的文件', function() {
      var path = input.value.trim();
      if (path && session) save(path);
    }));
    controls.append(button('刷新', function() { if (session) browse(currentPath); }), button('关闭', close));
    host.append(heading, note, status, list, pathEntry, controls);
    dialog = host;
    document.body.append(host);
    host.addEventListener('cancel', function(event) { event.preventDefault(); close(); });
    host.showModal();
    if (session && file && file.path) {
      message(file.path);
      var row = fileItem(file.path, '下载到手机', {});
      list.append(row.item);
      loadFileDetails([row], generation);
    } else if (session) browse('');
    else message('请先在主界面打开一条对话，再下载该会话工作区内的文件。');
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
