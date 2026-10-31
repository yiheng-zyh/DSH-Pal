import {Transform} from 'node:stream';

// Event boundaries are ASCII; keep all other event bytes exactly intact.
// A frame may be multi-megabyte. Drop its data incrementally once its event
// field identifies it, without buffering the JPEG or parsing private payloads.
export function egoEventsOnly(){
  let line=[],size=0,event='message',discard=false,kept=[],droppingLine=false;
  function consume(bytes,stream){
    const text=bytes.toString('utf8').replace(/\r?\n$/,'');
    if(text===''){
      if(!discard)for(const chunk of kept)stream.push(chunk);
      if(!discard)stream.push(bytes);kept=[];event='message';discard=false;return;
    }
    if(text.startsWith('event:')){event=text.slice(6).trim();discard=event==='frame';if(discard)kept=[];}
    if(!discard)kept.push(bytes);
  }
  return new Transform({
    transform(chunk,_,done){
      let start=0;
      for(let i=0;i<chunk.length;i++)if(chunk[i]===10){
        if(droppingLine){droppingLine=false;line=[];size=0;start=i+1;continue;}
        const part=chunk.subarray(start,i+1);let bytes;
        if(line.length){line.push(part);bytes=Buffer.concat(line,size+part.length);}else bytes=part;
        consume(bytes,this);line=[];size=0;start=i+1;
      }
      if(start<chunk.length){const part=chunk.subarray(start);if(discard)droppingLine=true;else{line.push(part);size+=part.length;if(size>8*1024*1024)return done(Error('SSE line exceeds bounded event limit'));}}
      done();
    },
    flush(done){if(!discard){for(const chunk of kept)this.push(chunk);for(const chunk of line)this.push(chunk);}done();}
  });
}
