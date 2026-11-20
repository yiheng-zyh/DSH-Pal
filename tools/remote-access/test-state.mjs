import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {writeGatewayState} from './state.mjs';

test('status replacement failure is recoverable on the next update', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-gateway-state-'));
  const destination = path.join(directory, 'state.json');
  try {
    await fs.mkdir(destination);
    const failed = await writeGatewayState(directory, {requests: 1});
    assert.equal(failed.ok, false);
    assert.match(failed.code, /^[A-Z0-9_]+$/);
    await fs.rmdir(destination);
    assert.deepEqual(await writeGatewayState(directory, {requests: 2}), {ok: true});
    assert.deepEqual(JSON.parse(await fs.readFile(destination, 'utf8')), {requests: 2});
    assert.deepEqual(await writeGatewayState(directory, {requests: 3}), {ok: true});
    assert.deepEqual(JSON.parse(await fs.readFile(destination, 'utf8')), {requests: 3});
  } finally {
    await fs.rm(directory, {recursive: true, force: true});
  }
});

test('status creation failure returns a safe error code', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pal-gateway-state-'));
  try {
    assert.deepEqual(await writeGatewayState(path.join(directory, 'missing'), {}), {
      ok: false, code: 'ENOENT',
    });
  } finally {
    await fs.rm(directory, {recursive: true, force: true});
  }
});
