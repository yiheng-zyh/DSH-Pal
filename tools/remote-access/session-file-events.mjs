import {jsonResponseTransform} from './file-metadata.mjs';

// The upstream Session page remains the authority for authentication, scope and
// pagination. Keep only file evidence before transferring the page to the phone.
export function sessionFileEvents(document) {
  const value = document?.result?.ok === true ? document.result.value : null;
  if (!Array.isArray(value?.records)) return document;
  const sequences = value.records.map(record => record.event?.seq).filter(Number.isSafeInteger);
  if (sequences.length) value.palBeforeSeq = Math.min(...sequences);
  value.records = value.records.flatMap(record => {
    const event = record.event, data = event?.data;
    if (!data) return [];
    let picked;
    if (event.type === 'deliverables/presented') picked = data;
    if (event.type === 'tool/call' && ['write', 'edit', 'str_replace_editor'].includes(data.name)) {
      picked = {callId: data.callId, name: data.name, arguments: data.arguments};
    }
    if (event.type === 'tool/result' && data.message?.source) {
      picked = {message: {source: data.message.source, isError: data.message.isError}, meta: {path: data.meta?.path}};
    }
    return picked ? [{type: 'event', event: {type: event.type, seq: event.seq, time: event.time, data: picked}}] : [];
  });
  return document;
}

export function sessionFileEventsTransform() {
  return jsonResponseTransform(sessionFileEvents, 8 * 1024 * 1024);
}
