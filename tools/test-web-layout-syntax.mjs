import fs from 'node:fs';
import vm from 'node:vm';
let count=0;
for(const file of ['WebDrawerMotion.kt','WebLayoutScript.kt','WebMotionScript.kt','WebSidebarNavigation.kt','WebInteractionPolicy.kt']) {
 const raw=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/'+file,import.meta.url),'utf8');
 for(const [,script] of raw.matchAll(/"""([\s\S]*?)"""/g)){new vm.Script(script,{filename:file});count++;}
}
if(count!==6)throw Error('Expected six layout/drawer JavaScript fragments');
for(const file of ['page-motion.js','plugin-compat.js','phone-right-motion.js','quick-composer.js','browser-probe.js','business-health.js']) {
 new vm.Script(fs.readFileSync(new URL('../app/src/main/assets/'+file,import.meta.url),'utf8'),{filename:file});
}
// A broken quoted selector used to discard the entire mobile stylesheet in
// WebView. Catch malformed strings/comments/brackets without a new dependency.
const css=fs.readFileSync(new URL('../app/src/main/assets/mobile-layout.css',import.meta.url),'utf8');
if(css.charCodeAt(0)===0xfeff)throw Error('Inline CSS must not begin with a BOM');
let quote='',comment=false;const stack=[];
for(let i=0;i<css.length;i++) {
 const c=css[i],next=css[i+1];
 if(comment){if(c==='*'&&next==='/'){comment=false;i++;}continue;}
 if(quote){if(c==='\\'){i++;continue;}if(c==='\n'||c==='\r')throw Error('Unterminated CSS string at '+i);if(c===quote)quote='';continue;}
 if(c==='/'&&next==='*'){comment=true;i++;continue;}
 if(c==='"'||c==="'"){quote=c;continue;}
 if('{[('.includes(c))stack.push(c);
 if('}])'.includes(c)&&stack.pop()!==({'}':'{',']':'[',')':'('}[c]))throw Error('Unbalanced CSS at '+i);
}
if(quote||comment||stack.length)throw Error('Unterminated CSS token');
console.log('PASS injected JavaScript, page motion, compatibility and mobile CSS token structure');
