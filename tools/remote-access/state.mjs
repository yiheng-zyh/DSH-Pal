import fs from 'node:fs/promises';
import path from 'node:path';

// Status is diagnostic data. A failed write must leave the live tunnel running.
export async function writeGatewayState(directory, value) {
  const temporary = path.join(directory, `state-${process.pid}.tmp`);
  try {
    await fs.writeFile(temporary, JSON.stringify(value, null, 2));
    await fs.rename(temporary, path.join(directory, 'state.json'));
    return {ok: true};
  } catch (error) {
    const code = /^[A-Z0-9_]+$/.test(error?.code ?? '') ? error.code : 'UNKNOWN';
    return {ok: false, code};
  }
}
