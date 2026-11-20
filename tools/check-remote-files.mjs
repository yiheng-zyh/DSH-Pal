import {readFileSync} from 'node:fs';

const source = readFileSync(new URL('../app/src/main/assets/remote-files.js', import.meta.url), 'utf8')
  .replace('window.top !== window', 'false');
const sessionSource = readFileSync(new URL('../app/src/main/assets/session-files.js', import.meta.url), 'utf8');

async function verify(source, sessionSource) {
  const frame = document.createElement('iframe');
  frame.srcdoc = '<div role="treeitem" aria-selected="true" data-row-key="session:fixture-session"></div>';
  document.body.append(frame);
  await new Promise(resolve => frame.onload = resolve);
  const page = frame.contentWindow;
  const checks = [];
  const wait = () => new Promise(resolve => setTimeout(resolve, 80));
  const assert = (name, condition) => { checks.push({name, passed: !!condition}); };
  let offered, version = 'one', calls = [], includeTime = true;
  page.DshFiles = {localAppearancePalette: () => JSON.stringify({surface: '#f2f4f7', foreground: '#20242c', muted: '#515867'})};
  const valueResponse = value => new Response(JSON.stringify({result: {ok: true, value}}),
    {headers: {'content-type': 'application/json'}});
  page.fetch = async (url, options) => {
    const request = JSON.parse(options.body);
    calls.push(request);
    const args = request.payload.args;
    if (request.method === 'session/projections') return valueResponse({asOfSeq: 20});
    if (request.method === 'session/page') return valueResponse({cursor: 0, hasMore: false, records: [
      {event: {type: 'deliverables/presented', seq: 10, time: 1000, data: {files: args.request.address.sessionId === 'different-session' ? [{path: 'only-other.bin'}] : [{path: 'fixture.bin'}, {path: 'second.pdf'}]}}}
    ]});
    if (request.method.endsWith('/stat')) return valueResponse({
      absolutePath: args.path, version, bytes: 3, ...(includeTime ? {modifiedAt: '2025-04-03T09:26:00Z'} : {}),
    });
    const form = new FormData();
    form.set('metadata', JSON.stringify({
      result: {ok: true, value: {absolutePath: args.path, version, offset: args.options.range.offset}},
      attachments: [{path: ['data'], codec: 'bytes', part: 'fixture-bytes'}],
    }));
    form.set('fixture-bytes', new Blob([new Uint8Array([0, 128, 255])]));
    return new Response(form);
  };
  page.__DSH_OFFER_REMOTE_FILE__ = (source, name) => { offered = {source, name}; };
  const dialog = () => page.document.querySelector('dialog');
  const click = label => Array.from(dialog().querySelectorAll('button')).find(item => (item.getAttribute('aria-label') || item.textContent) === label).click();
  try {
    page.document.documentElement.style.setProperty('--dsw-alias-bg-base', 'transparent');
    const pluginStyle = page.document.createElement('style');
    pluginStyle.textContent = 'dialog {background:transparent!important;opacity:.1!important}';
    page.document.head.append(pluginStyle);
    page.eval(source);
    page.eval(sessionSource);
    page.__DSH_REMOTE_FILES__.open();
    await wait();
    assert('file list reads the selected conversation and avoids workspace directory scans',
      calls.some(call => call.method === 'session/page' && call.payload.args.request.address.sessionId === 'fixture-session') && !calls.some(call => call.method === 'workspaceFiles/list'));
    assert('dialog has an opaque surface independent of transparent page colors',
      page.getComputedStyle(dialog()).backgroundColor === 'rgb(242, 244, 247)' && page.getComputedStyle(dialog()).opacity === '1');
    await wait();
    assert('file row shows real modification date and size',
      dialog().querySelector('[data-pal-file-meta]').textContent.includes('2025/04/03') && dialog().querySelector('[data-pal-file-meta]').textContent.includes('3 B'));
    const detail = dialog().querySelector('[data-pal-file-meta]');
    assert('file metadata remains inside its row in a short scroll area',
      detail.getBoundingClientRect().bottom <= detail.parentElement.getBoundingClientRect().bottom - 8);
    click('下载 · fixture.bin');
    await wait();
    assert('file selection offers original filename and size', offered?.name === 'fixture.bin' && offered.source.size === 3);
    const data = await offered.source.read(0, 3);
    assert('multipart binary content survives unchanged', Array.from(data).join(',') === '0,128,255');
    version = 'two';
    let changed = false;
    try { await offered.source.read(0, 3); } catch (_) { changed = true; }
    assert('changed file version is rejected', changed);
    let previewAddress;
    page.__dshSidebarModuleSystem__ = {entries: {managed: new Map([
      ['@deepseek-ai/dsh-client-ui-chat', {ctx: {get: name => name === 'sidebarRight' ? {openResource: address => { previewAddress = address; }} : null}}]
    ])}};
    page.__DSH_REMOTE_FILES__.open(); await wait();
    click('在侧栏预览 second.pdf');
    assert('file title opens the existing DSH sidebar resource navigator', previewAddress === 'dsh-resource://file/session/fixture-session/second.pdf' && !dialog());
    page.__DSH_REMOTE_FILES__.open(); await wait();
    const selected = page.document.querySelector('[role=treeitem]');
    selected.setAttribute('data-row-key', 'session:different-session');
    selected.setAttribute('aria-selected', 'false');
    selected.setAttribute('aria-selected', 'true');
    await wait();
    assert('switching conversation dismisses the old file list', !dialog());
    page.__DSH_REMOTE_FILES__.open(); await wait();
    assert('next conversation lists its own files without prior conversation results',
      dialog().textContent.includes('only-other.bin') && !dialog().textContent.includes('fixture.bin'));
    click('关闭');
    const node = page.document.createElement('div');
    node.setAttribute('data-textpreview-url', 'dsh-resource://file/session/other-session/folder/%E6%96%87%E4%BB%B6.pdf?line=5');
    node.innerHTML = '<div><span data-textpreview-path></span></div>';
    page.document.body.append(node);
    await wait();
    page.document.querySelector('[role=treeitem]').setAttribute('data-row-key', 'session:other-session');
    node.querySelector('[data-pal-download]').click();
    assert('preview decodes the original path without view parameters',
      dialog().querySelector('[role=status]').textContent === 'folder/文件.pdf');
    click('下载到手机');
    await wait();
    assert('preview keeps its own session scope', calls.at(-1).payload.args.workspaceFileScopeId === 'other-session');
    node.remove();
    includeTime = false;
    page.DshFiles.localAppearancePalette = () => JSON.stringify({surface: '#24272e', foreground: '#f4f6fa', muted: '#c1c6d0'});
    page.__DSH_REMOTE_FILES__.open();
    await wait(); await wait();
    assert('older backend missing time is labeled explicitly', dialog().querySelector('[data-pal-file-meta]').textContent.includes('未提供修改时间'));
    assert('dark surface also stays opaque under wallpaper overrides', page.getComputedStyle(dialog()).backgroundColor === 'rgb(36, 39, 46)' && page.getComputedStyle(dialog()).opacity === '1');
    click('关闭');
    page.document.querySelector('[role=treeitem]').remove();
    page.__DSH_REMOTE_FILES__.open();
    assert('missing session is explained in the dialog', dialog().querySelector('[role=status]').textContent.includes('请先'));
    return checks;
  } finally { frame.remove(); }
}

const targets = await (await fetch('http://127.0.0.1:9228/json')).json();
const target = targets.find(item => item.type === 'page');
const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(resolve => socket.onopen = resolve);
const result = await new Promise(resolve => {
  socket.onmessage = event => {
    const reply = JSON.parse(event.data);
    if (reply.id === 1) resolve(reply.result);
  };
  socket.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {
    expression: '(' + verify.toString() + ')(' + JSON.stringify(source) + ',' + JSON.stringify(sessionSource) + ')',
    awaitPromise: true, returnByValue: true,
  }}));
});
socket.close();
if (result.exceptionDetails) throw Error(result.exceptionDetails.text);
const checks = result.result.value;
console.log(JSON.stringify(checks, null, 2));
if (checks.some(check => !check.passed)) process.exitCode = 1;
