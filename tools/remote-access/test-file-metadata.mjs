import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {Readable} from 'node:stream';
import {addFileModifiedTime, fileMetadataTransform} from './file-metadata.mjs';

test('modified time requires an authorized result with matching local file identity', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-file-times-'));
  try {
    const file = path.join(directory, 'sample.txt');
    await fs.writeFile(file, 'file metadata fixture');
    await fs.utimes(file, new Date(), new Date('2025-04-03T09:26:00Z'));
    const info = await fs.stat(file, {bigint: true});
    const version = `${info.dev}:${info.ino}:${info.size}:${info.mtimeNs}:${info.ctimeNs}`;
    const success = {result: {ok: true, value: {absolutePath: file, version}}};
    await addFileModifiedTime(success);
    assert.equal(success.result.value.modifiedAt, '2025-04-03T09:26:00.000Z');
    const stale = {result: {ok: true, value: {absolutePath: file, version: 'different-backend-or-version'}}};
    assert.equal((await addFileModifiedTime(stale)).result.value.modifiedAt, undefined);
    const denied = {result: {ok: false, value: {absolutePath: file, version}}};
    assert.equal((await addFileModifiedTime(denied)).result.value.modifiedAt, undefined);
    const absent = {result: {ok: true, value: {absolutePath: path.join(directory, 'absent'), version}}};
    assert.equal((await addFileModifiedTime(absent)).result.value.modifiedAt, undefined);
  } finally { await fs.rm(directory, {recursive: true, force: true}); }
});

test('metadata transform preserves malformed and oversized responses', async () => {
  for (const raw of ['not JSON', 'x'.repeat(70000)]) {
    const chunks = [];
    for await (const chunk of Readable.from([Buffer.from(raw.slice(0, 100)), Buffer.from(raw.slice(100))]).pipe(fileMetadataTransform())) chunks.push(chunk);
    assert.equal(Buffer.concat(chunks).toString(), raw);
  }
});
