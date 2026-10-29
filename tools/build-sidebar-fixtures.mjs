// Build an expression for a real mobile WebView inspector; no ADB/device mutation.
// Usage: node tools/build-sidebar-fixtures.mjs > .local/sidebar-fixtures.js
import fs from 'node:fs';
const read=path=>fs.readFileSync(new URL('../'+path,import.meta.url),'utf8');
const script=path=>read(path).split('"""')[1];
const palettes=Object.fromEntries([...read('app/src/main/java/com/dsh/mobile/core/LocalAppearance.kt').matchAll(/\("(\w+)", "[^"]+", "(#[a-f0-9]+)", "(#[a-f0-9]+)", "(#[a-f0-9]+)"\)/g)].filter(m=>m[1]!=='dsh').map(m=>[m[1],{background:m[2],surface:m[3],accent:m[4]}]));
if(Object.keys(palettes).length!==4)throw Error('Review fixtures for changed preset catalog');
const nav=script('app/src/main/java/com/dsh/mobile/bridge/WebSidebarNavigation.kt');
const appearance=script('app/src/main/java/com/dsh/mobile/ui/web/WebAppearance.kt').replace('$palettes',JSON.stringify(palettes));
const motion=script('app/src/main/java/com/dsh/mobile/bridge/WebDrawerMotion.kt');
process.stdout.write(`(async()=>({race:await (${read('tools/drawer-navigation-fixture.js')})(${JSON.stringify(nav)},${JSON.stringify(motion)}),navigation:await (${read('tools/sidebar-navigation-fixture.js')})(${JSON.stringify(nav)}),appearance:await (${read('tools/web-appearance-fixture.js')})(${JSON.stringify(appearance)},${JSON.stringify(palettes)})}))()`);
