import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {webcrypto} from 'node:crypto';
import {sessionFileEvents} from './remote-access/session-file-events.mjs';

const source = fs.readFileSync(new URL('../app/src/main/assets/session-files.js', import.meta.url), 'utf8');
function load(fetch) {
  const scope = {window: {}, crypto: webcrypto, fetch, AbortSignal};
  vm.runInNewContext(source, scope);
  return scope.window.__DSH_SESSION_FILES__;
}
function event(type, seq, data) { return {type: 'event', event: {type, seq, time: seq * 1000, data}}; }
const records = [
  event('tool/call', 1, {callId: 'read', name: 'read', arguments: '{"file_path":"input.txt"}'}),
  event('tool/result', 2, {message: {source: {callId: 'read'}}}),
  event('tool/call', 3, {callId: 'write', name: 'write', arguments: '{"file_path":"draft.txt","content":"private content"}'}),
  event('tool/result', 4, {message: {source: {callId: 'write'}}, meta: {path: 'C:\\work\\report.txt'}}),
  event('tool/call', 5, {callId: 'failed', name: 'write', arguments: '{"file_path":"failed.txt","content":"x"}'}),
  event('tool/result', 6, {message: {source: {callId: 'failed'}, isError: true}}),
  event('deliverables/presented', 7, {files: [{path: 'C:/work/report.txt', description: 'Delivered report'}, {path: 'table.xlsx'}]}),
  event('assistant/message', 8, {text: 'A random file path is not proof of creation'}),
];

test('file evidence includes successful writes and deliveries, deduplicates paths and excludes reads/failures', () => {
  const api = load();
  const evidence = records.map(record => api.fileEvent(record.event)).filter(Boolean);
  const files = api.collect(evidence.reverse());
  assert.equal(files.length, 2);
  assert.equal(files.find(file => file.description)?.description, 'Delivered report');
  assert.deepEqual(Array.from(files, file => file.path), ['C:/work/report.txt', 'table.xlsx']);
});

test('history pages stay pinned to one selected conversation and resolve calls split across pages', async () => {
  const calls = [];
  const api = load(async (url, options) => {
    const request = JSON.parse(options.body), args = request.payload.args.request;
    calls.push({url, args});
    let value;
    if (request.method === 'session/projections') value = {asOfSeq: 20};
    else value = args.beforeSeq === undefined ? {records: records.slice(3), cursor: 20, hasMore: true} : {records: records.slice(0, 3), cursor: 20, hasMore: false};
    return Response.json({result: {ok: true, value}});
  });
  const files = await api.list('selected-old-conversation', new AbortController().signal);
  assert.equal(files.length, 2);
  for (const call of calls.filter(call => call.url.includes('/page'))) {
    assert.equal(call.args.address.sessionId, 'selected-old-conversation');
    assert.equal(call.args.throughSeq, 20);
  }
  assert.equal(calls[2].args.beforeSeq, 4);
  assert.ok(calls.every(call => !call.url.includes('workspaceFiles/list')));
});

test('cancellation stops before another history page and does not return an incomplete successful list', async () => {
  const controller = new AbortController();
  let pages = 0;
  const api = load(async (url) => {
    if (url.includes('projections')) return Response.json({result: {ok: true, value: {asOfSeq: 20}}});
    pages++;
    return Response.json({result: {ok: true, value: {records, cursor: 1, hasMore: true}}});
  });
  await assert.rejects(api.list('selected', controller.signal, () => controller.abort()));
  assert.equal(pages, 1);
});

test('a filtered page without file events still advances to older file records', async () => {
  let pages = 0;
  const api = load(async (url, options) => {
    if (url.includes('projections')) return Response.json({result: {ok: true, value: {asOfSeq: 20}}});
    const args = JSON.parse(options.body).payload.args.request;
    pages++;
    const value = args.beforeSeq === undefined ? {records: [], cursor: 20, palBeforeSeq: 9, hasMore: true} :
      {records, cursor: 20, palBeforeSeq: 1, hasMore: false};
    return Response.json({result: {ok: true, value}});
  });
  assert.equal((await api.list('history', new AbortController().signal)).length, 2);
  assert.equal(pages, 2);
});

test('gateway filtering preserves pagination and file evidence while dropping conversation and tool output', () => {
  const original = {result: {ok: true, value: {cursor: 1, hasMore: true, records}}};
  const api = load();
  const before = api.collect(records.map(record => api.fileEvent(record.event)).filter(Boolean));
  const filtered = sessionFileEvents(structuredClone(original));
  const after = api.collect(filtered.result.value.records.map(record => api.fileEvent(record.event)).filter(Boolean));
  assert.equal(JSON.stringify(after), JSON.stringify(before));
  assert.equal(filtered.result.value.cursor, 1);
  assert.equal(filtered.result.value.hasMore, true);
  assert.equal(filtered.result.value.palBeforeSeq, 1);
  assert.ok(!filtered.result.value.records.some(record => record.event.type === 'assistant/message'));
  const denied = {result: {ok: false, error: {code: 'denied'}}};
  assert.deepEqual(sessionFileEvents(structuredClone(denied)), denied);
});
