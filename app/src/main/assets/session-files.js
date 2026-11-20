;(function() {
  if (window.__DSH_SESSION_FILES__) return;
  var requestSequence = 0;
  function mutationPath(name, raw) {
    var args;
    try { args = JSON.parse(raw); } catch (_) { return null; }
    if (!args || typeof args !== 'object') return null;
    if (name === 'write' && typeof args.content === 'string') return args.file_path;
    if (name === 'edit' && typeof args.old_string === 'string' && args.old_string &&
        typeof args.new_string === 'string' && args.old_string !== args.new_string) return args.file_path;
    if (name === 'str_replace_editor' && (
      args.command === 'create' && typeof args.file_text === 'string' ||
      args.command === 'str_replace' && typeof args.old_str === 'string' && args.old_str ||
      args.command === 'insert' && Number.isInteger(args.insert_line) && typeof args.new_str === 'string'
    )) return args.path;
    return null;
  }
  // Retain file evidence only. Prompt text, reads, shell commands and tool output
  // cannot establish that a file was produced by this conversation.
  function fileEvent(event) {
    var data = event.data || {}, value;
    if (event.type === 'deliverables/presented' && Array.isArray(data.files)) {
      value = {files: data.files.filter(function(file) { return typeof file.path === 'string' && file.path.trim(); })};
    } else if (event.type === 'tool/call') {
      var path = mutationPath(data.name, data.arguments);
      if (typeof path === 'string' && path.trim()) value = {callId: String(data.callId), path: path};
    } else if (event.type === 'tool/result' && data.message && data.message.source) {
      value = {callId: String(data.message.source.callId), failed: data.message.isError === true,
        path: data.meta && data.meta.path};
    }
    return value ? {seq: event.seq, time: event.time, type: event.type, data: value} : null;
  }
  function collect(events) {
    var files = new Map(), calls = new Map();
    function add(file, event) {
      var key = file.path.replace(/\\/g, '/');
      var old = files.get(key);
      files.set(key, Object.assign({}, old, file, {seq: event.seq, deliveredAt: event.time}));
    }
    events.sort(function(a, b) { return a.seq - b.seq; }).forEach(function(event) {
      var data = event.data;
      if (event.type === 'tool/call') calls.set(data.callId, data.path);
      if (event.type === 'deliverables/presented') data.files.forEach(function(file) { add(file, event); });
      if (event.type === 'tool/result' && !data.failed && calls.has(data.callId)) {
        var path = typeof data.path === 'string' && /^(?:[A-Za-z]:[/\\]|[/\\])/.test(data.path) ? data.path : calls.get(data.callId);
        add({path: path}, event);
      }
    });
    return Array.from(files.values()).sort(function(a, b) { return b.seq - a.seq; });
  }
  async function list(session, signal, progress) {
    async function request(method, args) {
      var response = await fetch('/api/session/' + method + (method === 'page' ? '?pal_file_events=1' : ''), {
        method: 'POST', credentials: 'same-origin', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({type: 'client-request', rpcId: 'pal-session-files-' + (++requestSequence),
          method: 'session/' + method, payload: {args: {request: args}}}),
        signal: AbortSignal.any([signal, AbortSignal.timeout(45000)])
      });
      if (!response.ok) throw Error('读取当前对话文件失败（HTTP ' + response.status + '）');
      var document = await response.json();
      if (!document.result || !document.result.ok) throw Error('当前 DSH 无法读取对话文件记录，请检查连接后重试');
      return document.result.value;
    }
    var baseline = await request('projections', {sessionId: session});
    if (!baseline || !Number.isSafeInteger(baseline.asOfSeq)) throw Error('无法确认当前对话，请重新打开对话后重试');
    var events = [], before;
    while (true) {
      signal.throwIfAborted();
      var args = {address: {kind: 'session', sessionId: session}, throughSeq: baseline.asOfSeq, maxMessages: 200};
      if (before !== undefined) args.beforeSeq = before;
      var page = await request('page', args);
      if (!Array.isArray(page.records)) throw Error('对话文件记录格式不可用');
      page.records.forEach(function(record) {
        var event = record.event && fileEvent(record.event);
        if (event) events.push(event);
      });
      if (progress) progress(collect(events));
      if (!page.hasMore) return collect(events);
      var first = page.palBeforeSeq;
      if (first === undefined) first = Math.min.apply(null, page.records.map(function(record) { return record.event && record.event.seq; }).filter(Number.isSafeInteger));
      if (!Number.isSafeInteger(first) || before !== undefined && first >= before) throw Error('历史文件读取未完成，请刷新重试');
      before = first;
    }
  }
  window.__DSH_SESSION_FILES__ = {list: list, fileEvent: fileEvent, collect: collect};
})();
