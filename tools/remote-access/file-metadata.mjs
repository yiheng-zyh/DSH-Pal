import fs from 'node:fs/promises';
import {Transform} from 'node:stream';

// Only enrich a successful, authenticated DSH stat whose identity matches a
// regular file on this host. Other filesystems keep their original response.
export async function addFileModifiedTime(document) {
  const value = document?.result?.ok === true ? document.result.value : null;
  if (!value || typeof value.absolutePath !== 'string' || typeof value.version !== 'string') return document;
  try {
    const info = await fs.stat(value.absolutePath, {bigint: true});
    const version = `${info.dev}:${info.ino}:${info.size}:${info.mtimeNs}:${info.ctimeNs}`;
    if (info.isFile() && value.version === version) {
      value.modifiedAt = new Date(Number(info.mtimeNs / 1000000n)).toISOString();
    }
  } catch {}
  return document;
}

export function fileMetadataTransform() {
  return jsonResponseTransform(addFileModifiedTime);
}

export function jsonResponseTransform(map, limit = 65536) {
  let chunks = [], length = 0, passthrough = false;
  return new Transform({
    transform(chunk, encoding, done) {
      length += chunk.length;
      if (!passthrough && length > limit) {
        passthrough = true;
        for (const part of chunks) this.push(part);
        chunks = [];
      }
      if (passthrough) this.push(chunk);
      else chunks.push(chunk);
      done();
    },
    flush(done) {
      if (passthrough) { done(); return; }
      const raw = Buffer.concat(chunks);
      let document;
      try { document = JSON.parse(raw.toString('utf8')); }
      catch { this.push(raw); done(); return; }
      Promise.resolve().then(() => map(document)).then(value => {
        this.push(JSON.stringify(value));
        done();
      }, () => { this.push(raw); done(); });
    },
  });
}
