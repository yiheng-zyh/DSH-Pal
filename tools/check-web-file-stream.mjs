import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../app/src/main/assets/web-files.js', import.meta.url), 'utf8');
function page(accept = true) {
  const chunks = [], finished = [];
  const context = {
    Map, Uint8Array, Blob, crypto: {randomUUID: () => 'fixture-token-123456'},
    HTMLAnchorElement: class {click() {}},
    document: {addEventListener() {}},
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
    DshFiles: {
      offerBlob: () => accept,
      writeChunk(token, value) { chunks.push(Buffer.from(value, 'base64')); return true; },
      endBlob(token, success) { finished.push(success); },
    },
  };
  context.window = context; context.top = context;
  vm.runInNewContext(source, context);
  return {context, chunks, finished};
}
const expected = Buffer.alloc(600001);
for (let index = 0; index < expected.length; index++) expected[index] = index % 251;
const fixture = page();
const reads = [];
fixture.context.__DSH_OFFER_REMOTE_FILE__({
  size: expected.length,
  read: async (offset, length) => {reads.push([offset, length]); return new Uint8Array(expected.subarray(offset, offset + length));},
}, 'fixture.bin');
await fixture.context.__DSH_FILE_SEND__('fixture-token-123456');
assert.deepEqual(Buffer.concat(fixture.chunks), expected);
assert.equal(reads.length, 3);
assert.ok(fixture.chunks.every(chunk => chunk.length <= 49152));
assert.deepEqual(fixture.finished, [true]);
const blob = page();
blob.context.__DSH_OFFER_REMOTE_FILE__(new Blob([expected]), 'blob.bin');
await blob.context.__DSH_FILE_SEND__('fixture-token-123456');
assert.deepEqual(Buffer.concat(blob.chunks), expected);
assert.deepEqual(blob.finished, [true]);

const empty = page();
empty.context.__DSH_OFFER_REMOTE_FILE__({size: 0, read() {throw Error('Empty file must not be read');}}, 'empty.bin');
await empty.context.__DSH_FILE_SEND__('fixture-token-123456');
assert.deepEqual(empty.finished, [true]);

const failed = page();
failed.context.__DSH_OFFER_REMOTE_FILE__({size: 100, read() {throw Error('changed');}}, 'changed.bin');
await failed.context.__DSH_FILE_SEND__('fixture-token-123456');
assert.deepEqual(failed.finished, [false]);
assert.throws(() => page(false).context.__DSH_OFFER_REMOTE_FILE__({size: 1}, 'busy.bin'));
console.log('PASS binary streaming, bounded chunks, empty files, changed files and rejected offers');

const modern = page();
let offeredContext, remembered = true;
modern.context.DshFiles.offerFile = (authority, token, name, mime, size, metadata) => { offeredContext = JSON.parse(metadata); return true; };
modern.context.DshFiles.downloadOpenAfter = () => remembered;
modern.context.DshFiles.setDownloadOpenAfter = (authority, value) => { remembered = value; };
assert.equal(modern.context.__DSH_FILE_OPTIONS__.getOpenAfter(), true);
modern.context.__DSH_FILE_OPTIONS__.setOpenAfter(false);
assert.equal(modern.context.__DSH_FILE_OPTIONS__.getOpenAfter(), false);
modern.context.__DSH_OFFER_REMOTE_FILE__({size: 0, context: {session: 'session-one', conversation: 'Current chat'}}, 'context.txt');
assert.deepEqual(offeredContext, {session: 'session-one', conversation: 'Current chat'});
await modern.context.__DSH_FILE_SEND__('fixture-token-123456');
assert.deepEqual(modern.finished, [true]);
console.log('PASS remembered open-after option and conversation metadata');
