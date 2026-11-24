import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
// Exercise the shipped outside-tap listener. Portals live outside the sidebar,
// so descendants of an open menu must reach their action instead of the mask.
const source=fs.readFileSync(new URL('../app/src/main/java/com/dsh/mobile/bridge/WebMotionScript.kt',import.meta.url),'utf8').split('"""')[1];
const section=source.slice(source.indexOf('// Tapping the empty area beside an open drawer'));
const listener=section.slice(section.indexOf("document.addEventListener('click'"),section.indexOf('}, true);')+10);
assert.ok(listener.includes('closeWithMotion'));
function harness({open=true,native=true,active=true,mobile=true}={}) {
  class Element {
    constructor(attrs={},parent=null){this.attrs=attrs;this.parent=parent;}
    closest(selector){
      const selectors=selector.split(',');
      for(let node=this;node;node=node.parent)for(const part of selectors){
        const match=/^\[([^=\]]+)(?:="([^"]+)")?\]$/.exec(part.trim());
        if(match&&Object.hasOwn(node.attrs,match[1])&&(match[2]===undefined||node.attrs[match[1]]===match[2]))return node;
      }
      return null;
    }
  }
  let capture,closes=0,stops=0,prevented=0;
  const context={Element,bypass:false,nativeDrawer:native,active,mobile:{matches:mobile},frame:{},side:{},isOpen:()=>open,sidebarToggle:()=>({}),closeWithMotion:()=>closes++,settleClosed:()=>closes++,document:{addEventListener:(type,fn)=>capture=fn}};
  vm.runInNewContext(listener,context);
  function tap(node){const event={target:node,preventDefault(){prevented++},stopImmediatePropagation(){stops++}};capture(event);return event;}
  return {Element,tap,get result(){return {closes,stops,prevented}}};
}
for(const role of ['menu','listbox','dialog','alertdialog']){
  const h=harness(),surface=new h.Element({role}),button=new h.Element({role:'menuitem'},surface),label=new h.Element({},button);
  h.tap(label);assert.deepEqual(h.result,{closes:0,stops:0,prevented:0},`${role} portal label must reach its owner`);
}
{
  const h=harness(),side=new h.Element({'data-dsh-mobile-sidebar':''});h.tap(new h.Element({},side));assert.equal(h.result.closes,0);
}
{
  const h=harness();h.tap(new h.Element({'data-sidebar-mask':''}));assert.deepEqual(h.result,{closes:1,stops:1,prevented:1},'real mask closes once and prevents click through');
}
for(const options of [{open:false},{native:false},{active:false},{mobile:false}]){
  const h=harness(options);h.tap(new h.Element());assert.equal(h.result.closes,0);
}
console.log('PASS 10 native drawer routes: portal actions, sidebar, actual mask and inactive states');
