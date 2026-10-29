import fs from 'node:fs';
import vm from 'node:vm';
let count=0;
for(const file of ['WebDrawerMotion.kt','WebLayoutScript.kt']) {
 const raw=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/'+file,import.meta.url),'utf8');
 for(const [,script] of raw.matchAll(/"""([\s\S]*?)"""/g)){new vm.Script(script,{filename:file});count++;}
}
if(count!==3)throw Error('Expected drawer and two layout scripts');
console.log('PASS all three injected layout/drawer JavaScript fragments parse');
