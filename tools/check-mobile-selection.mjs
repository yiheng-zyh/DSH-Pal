import fs from 'node:fs';

const source = fs.readFileSync(new URL('../app/src/main/assets/mobile-selection.js', import.meta.url), 'utf8')
  .replace('window.top !== window', 'false');
async function verify(source) {
  const frame = document.createElement('iframe');
  frame.srcdoc = '<main data-dsh-mobile-center><div data-text-interaction-source id="first"><p>第一段 <strong>加粗文字</strong> 与 code。</p><p>第二段内容。</p></div><div data-text-interaction-source id="other">另一条消息</div><div data-text-interaction-source data-text-interaction-exclude id="excluded">控件文字</div><div contenteditable="true" id="editor">原有草稿</div><div data-text-interaction-surface>网页引用菜单</div></main>';
  document.body.append(frame);
  await new Promise(resolve => frame.onload = resolve);
  const page = frame.contentWindow, doc = page.document, checks = [];
  let admitted = true, insertable = true, quoted;
  const inputActions = {insertQuote: text => { if (!insertable) return false; quoted = text; return true; }};
  const context = {get: name => name === 'uiSession' ? {adapter: {current: {getSnapshot: () => ({key: 'fixture', props: {inputActions}})}}} :
    {interactions: {dispatch: () => admitted ? 'quote' : null}}};
  page.__dshSidebarModuleSystem__ = {entries: {managed: new Map([['@deepseek-ai/dsh-client-ui-chat', {ctx: context}]])}};
  const assert = (name, passed) => checks.push({name, passed: !!passed});
  function select(start, end = start) {
    const range = doc.createRange();
    range.setStart(start, 0);
    range.setEnd(end, end.childNodes.length);
    const selection = page.getSelection();
    selection.removeAllRanges(); selection.addRange(range);
  }
  try {
    page.eval(source);
    const api = page.__DSH_MOBILE_SELECTION__;
    select(doc.getElementById('first'));
    const expected = page.getSelection().toString().trim();
    assert('valid multi-paragraph selection is admitted by the DSH quote registry', api.available());
    api.active(true);
    assert('native mode hides the overlapping web surface', page.getComputedStyle(doc.querySelector('[data-text-interaction-surface]')).visibility === 'hidden');
    assert('quote uses the original composer action with the current selected text', api.quote() && quoted === expected);
    assert('successful quote clears only selection and preserves the draft', page.getSelection().isCollapsed && doc.getElementById('editor').textContent === '原有草稿');
    api.active(false);
    assert('leaving native mode restores the web surface', page.getComputedStyle(doc.querySelector('[data-text-interaction-surface]')).visibility !== 'hidden');
    select(doc.getElementById('editor'));
    assert('input-box selection keeps the standard edit menu', !api.available());
    select(doc.getElementById('first'), doc.getElementById('other'));
    assert('selection spanning separate messages is rejected', !api.available());
    select(doc.getElementById('excluded'));
    assert('excluded controls cannot become quotes', !api.available());
    select(doc.getElementById('first')); admitted = false;
    assert('disabled quote plugin or read-only admission stays rejected', !api.available() && !api.quote());
    admitted = true; insertable = false;
    assert('failed composer insertion retains the selected text', !api.quote() && !page.getSelection().isCollapsed);
    return checks;
  } finally { frame.remove(); }
}
const targets = await (await fetch('http://127.0.0.1:9228/json')).json();
const socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
await new Promise(resolve => socket.onopen = resolve);
const answer = await new Promise(resolve => {
  socket.onmessage = event => { const reply = JSON.parse(event.data); if (reply.id === 1) resolve(reply.result); };
  socket.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {
    expression: '(' + verify.toString() + ')(' + JSON.stringify(source) + ')', awaitPromise: true, returnByValue: true,
  }}));
});
socket.close();
if (answer.exceptionDetails) throw Error(answer.exceptionDetails.text);
const checks = answer.result.value;
console.log(JSON.stringify(checks, null, 2));
if (checks.some(check => !check.passed)) process.exitCode = 1;
