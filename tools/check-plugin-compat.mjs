import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'..');
const asset=name=>fs.readFileSync(path.join(root,'app/src/main/assets',name),'utf8');
const kotlin=name=>fs.readFileSync(path.join(root,'app/src/main/java/com/dsh/mobile/bridge',name),'utf8');
const extract=(source,marker)=>{const start=source.indexOf(marker)+marker.length;return source.slice(start,source.indexOf('""".trimIndent()',start)).trim();};
const mediaSource=asset('web-media.js');
const fragment=name=>{const match=kotlin(name).match(/"""([\s\S]*?)"""/);if(!match)throw Error('Missing script in '+name);return match[1];};
const sources=[fragment('WebInteractionPolicy.kt'),fragment('WebSidebarNavigation.kt'),fragment('WebDrawerMotion.kt'),fragment('WebMotionScript.kt'),asset('plugin-compat.js'),extract(kotlin('WebLayoutScript.kt'),'private val controller = """')+'('+JSON.stringify(asset('mobile-layout.css'))+');',asset('phone-right-motion.js'),asset('page-motion.js')].map(s=>s.replace(/window\.top\s*!==\s*window/g,'false'));
// The harness runs the shipped scripts in isolated synthetic same-origin frames.
// Only the top-frame guard is disabled; real chat DOM and remote settings stay untouched.
async function check(sources,mediaSource){
  const results=[],runtimeErrors=[];let fixture;
  const pause=ms=>new Promise(r=>setTimeout(r,ms));
  async function mount(html,reduced=false,options={}){
    if(fixture)fixture.remove();
    fixture=document.createElement('iframe');fixture.id='dsh-compat-verification';
    fixture.style.cssText='position:fixed;inset:0;width:100%;height:100%;border:0;z-index:2147483600;background:white';
    fixture.srcdoc='<meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;height:100%;font:16px sans-serif}*{box-sizing:border-box}.test_frame{display:grid;grid-template-columns:180px minmax(0,1fr) 200px;height:100%}.test_centerCol{grid-column:2;min-width:0;background:#e9edf4}.test_sidebarCol{background:#ddd}.test_rightbarCol{background:#eee}.test_panel{height:100%}button{min-height:36px} [hidden]{display:none!important}</style>'+html;
    const loaded=new Promise(resolve=>fixture.onload=resolve);document.body.appendChild(fixture);await loaded;
    const w=fixture.contentWindow;w.__compatLogs=[];w.__compatErrors=[];
    w.addEventListener('error',e=>{w.__compatErrors.push(e.message);runtimeErrors.push(e.message);});w.addEventListener('unhandledrejection',e=>runtimeErrors.push(String(e.reason&&e.reason.message||e.reason)));
    w.DshNative={onBridgeMessage:data=>{const v=JSON.parse(data);w.__compatLogs.push(v.payload);}};
    w.DshFiles={enhancedInteractions:()=>options.enabled!==false};
    if(reduced){const native=w.matchMedia.bind(w);w.matchMedia=q=>q.includes("prefers-reduced-motion")?{matches:true,addEventListener(){},removeEventListener(){}}:native(q);}
    if(options.legacy)w.CSS.supports=()=>false;
    if(options.noInert)delete w.HTMLElement.prototype.inert;
    if(options.noAnimation)w.Element.prototype.animate=undefined;
    if(options.throwAnimation)w.Element.prototype.animate=function(){throw Error('unsupported animation');};
    for(const source of sources)w.eval(source);
    await pause(90);return w;
  }
  function assert(name,condition,detail){results.push({name,passed:!!condition,detail});}
  const center='<main class="test_centerCol"><article>Official conversation</article></main>';
  try{
    let w=await mount('<div class="test_frame" data-dsh-frame data-sidebar-collapsed data-rightbar-collapsed>'+center+'</div>');
    assert('no optional plugins: full-width center',Math.abs(w.document.querySelector('main').getBoundingClientRect().width-w.innerWidth)<2,w.__DSH_MOBILE_COMPAT__.summary());
    const wrapper=w.document.createElement('div');w.document.querySelector('main').append(wrapper);await pause(30);wrapper.innerHTML='<header class="late_header"><div class="late_crumbs">Late official title</div></header>';await pause(80);
    assert('late nested official header recognised',w.__DSH_MOBILE_COMPAT__.summary().header);
    assert('no optional plugins: no mask or right-open state',!w.document.querySelector('.dsh-mobile-sidebar-scrim')&&!w.document.querySelector('[data-dsh-mobile-right-open]'));
    let f=w.document.querySelector('[data-dsh-frame]');const right=w.document.createElement('aside');right.className='test_rightbarCol';right.innerHTML='<div class="test_panel"><p>Plugin mounted later</p><section class="plugin_panel" id="nested">Nested panel</section></div>';f.append(right);await pause(80);
    assert('late right plugin recognised',right.hasAttribute('data-dsh-mobile-right'));
    assert('nested plugin panel retains own position',w.getComputedStyle(w.document.getElementById('nested')).position==='static');
    right.remove();await pause(80);assert('right plugin removed without stale open state',!w.__DSH_MOBILE_COMPAT__.summary().right&&!f.hasAttribute('data-dsh-mobile-right-open'));
    const left=w.document.createElement('aside');left.className='test_sidebarCol';left.innerHTML='<p>Optional conversations</p>';f.prepend(left);await pause(80);
    assert('unknown left controls keep original interaction without a mask',w.__DSH_MOBILE_COMPAT__.summary().left&&w.document.querySelectorAll('.dsh-mobile-sidebar-scrim').length===0);
    left.remove();await pause(80);assert('left plugin removed cleans mask',!w.document.querySelector('.dsh-mobile-sidebar-scrim'));
    f.remove();w.document.body.insertAdjacentHTML('beforeend','<div class="test_frame" data-dsh-frame>'+center+'</div>');await pause(80);assert('shell remount rebinds safely',w.__DSH_MOBILE_COMPAT__.summary().shell&&w.document.querySelector('main').hasAttribute('data-dsh-mobile-center'));
    w=await mount('<div class="test_frame" data-dsh-frame>'+center.replace('<article>','<header class="test_header"><div class="test_crumbs">Title</div><div class="test_headerActions"><button>Official action</button></div></header><article>')+'</div>');
    let header=w.document.querySelector('header');assert('header without tabs: actions use second row',w.getComputedStyle(header.querySelector('.test_headerActions')).gridRowStart==='2',{row:w.getComputedStyle(header.querySelector('.test_headerActions')).gridRowStart,marked:header.hasAttribute('data-dsh-mobile-header'),css:w.document.getElementById('dsh-mobile-layout').sheet.cssRules.length,media:w.matchMedia('(max-width:900px) and (pointer:coarse)').matches,html:header.outerHTML,first:w.document.getElementById('dsh-mobile-layout').sheet.cssRules[0].cssText.slice(0,900),firstCount:w.document.getElementById('dsh-mobile-layout').sheet.cssRules[0].cssRules.length,rule:[...w.document.getElementById('dsh-mobile-layout').sheet.cssRules[0].cssRules].filter(r=>r.selectorText&&r.selectorText.includes('_headerActions')).map(r=>r.cssText)});
    assert('header without left sidebar: no logo gap',parseFloat(w.getComputedStyle(header.querySelector('.test_crumbs')).paddingLeft)===0);
    w=await mount('<div class="test_frame" data-dsh-frame>'+center.replace('<article>','<section><div role="tablist"><button role="tab" id="a" aria-selected="true" aria-controls="pa">Plugin A</button><button role="tab" id="b" aria-selected="false" aria-controls="pb">Plugin B</button></div><div id="pa" role="tabpanel">A</div><div id="pb" role="tabpanel" hidden>B</div></section><article>')+'</div>');
    const b=w.document.getElementById('b');b.onclick=()=>{w.document.getElementById('pa').hidden=true;w.document.getElementById('pb').hidden=false;b.setAttribute('aria-selected','true');};b.click();await pause(180);
    assert('unfamiliar plugin tabs: handler and semantic panel preserved',!w.document.getElementById('pb').hidden);
    assert('unfamiliar plugin tabs: no duplicate content animation',w.document.getElementById('pb').getAnimations().length===0);
    await pause(260);assert('tab animation cleans up',w.document.getElementById('pb').getAnimations().length===0);
    const panel=w.document.getElementById('pb');panel.style.transition='opacity 1s linear';b.setAttribute('aria-selected','false');const previous=w.__compatLogs.length;b.click();await pause(90);
    assert('plugin-owned motion is not layered with app motion',!w.__compatLogs.slice(previous).some(s=>String(s).includes('semantic target')));
    panel.style.transition='none';b.setAttribute('aria-selected','false');const own=panel.animate([{opacity:.9},{opacity:1}],{duration:500});const motionBefore=w.__compatLogs.length;b.click();await pause(80);
    assert('plugin Web Animations ownership respected',!w.__compatLogs.slice(motionBefore).some(s=>String(s).includes('semantic target')));own.cancel();
    const menu=w.document.createElement('div');menu.setAttribute('role','menu');menu.style.cssText='position:fixed;width:800px;left:0;top:0;height:100px';menu.innerHTML='<div role="menu" id="sub" style="position:absolute;left:10px;top:30px;width:80px;height:40px">Submenu</div>';w.document.body.append(menu);await pause(80);
    assert('oversize root menu fits phone',menu.getBoundingClientRect().width<=w.innerWidth&&menu.hasAttribute('data-dsh-mobile-menu'));
    assert('nested submenu keeps its placement',!w.document.getElementById('sub').hasAttribute('data-dsh-mobile-menu')&&w.getComputedStyle(w.document.getElementById('sub')).position==='absolute');
    const keep=w.document.createElement('div');keep.setAttribute('data-dsh-mobile-keep-layout','');keep.innerHTML='<div role="menu" style="position:fixed;width:800px;height:50px">Plugin owned</div>';w.document.body.append(keep);await pause(80);
    assert('explicit plugin opt-out respected',!keep.querySelector('[data-dsh-mobile-menu]'));
    const dialog=w.document.createElement('div');dialog.setAttribute('role','dialog');dialog.style.cssText='position:fixed;width:800px;left:0;top:0;height:300px';dialog.innerHTML='<div style="display:flex;width:780px"><div style="width:300px">Settings nav</div><div style="width:480px">Settings content</div></div>';w.document.body.append(dialog);await pause(80);
    assert('oversize dialog constrained without becoming bottom menu',dialog.getBoundingClientRect().width<=w.innerWidth&&!dialog.hasAttribute('data-dsh-mobile-menu'));
    assert('dialog columns stack only when overflowing',w.getComputedStyle(dialog.firstElementChild).flexDirection==='column');
    assert('plugin-owned tabs do not receive an extra conversation indicator',!w.document.querySelector('.dsh-tab-indicator'));
    const tabMarkup='<div class="test_frame" data-dsh-frame><main class="test_centerCol"><div role="tablist"><button role="tab" aria-selected="false" aria-controls="rp">Motion preference</button></div><div id="rp" role="tabpanel">Panel</div></main></div>';
    const rightMarkup='<div class="test_frame" data-dsh-frame data-rightbar-collapsed>'+center+'<aside class="test_rightbarCol"><div data-sidebar-right-open="false"><section data-sidebar-right-panel><button data-sidebar-right-toggle aria-label="Close right sidebar">Close</button><div style="height:1300px">Real panel content</div></section></div></aside></div><button id="outside" aria-label="Close right sidebar">Unrelated plugin</button>';
    w=await mount(rightMarkup);
    let root=w.document.querySelector('[data-dsh-frame]'),outer=w.document.querySelector('aside'),declared=outer.querySelector('[data-sidebar-right-open]');
    declared.setAttribute('data-sidebar-right-open','true');await pause(110);
    assert('semantic right contract animates one complete surface',outer.getAnimations().length===1&&Math.abs(outer.getBoundingClientRect().width-w.innerWidth)<2);
    await pause(450);assert('right opening animation completes',outer.getAnimations().length===0);
    let outsideCalls=0;w.document.getElementById('outside').onclick=()=>outsideCalls++;w.document.getElementById('outside').click();
    assert('same-label button outside managed right panel untouched',outsideCalls===1);
    let nativeCloses=0;const closing=outer.querySelector('button');closing.onclick=()=>{nativeCloses++;declared.setAttribute('data-sidebar-right-open','false');};
    closing.click();await pause(500);
    assert('right local close retains content and releases touch',root.hasAttribute('data-dsh-right-closed')&&declared.getAttribute('data-sidebar-right-open')==='true'&&outer.inert&&w.getComputedStyle(outer).display==='none');
    assert('right local close does not replay upstream action',nativeCloses===0);
    w.__DSH_PHONE_RIGHT__.open();await pause(550);assert('right local reopen restores interaction',!root.hasAttribute('data-dsh-right-closed')&&!outer.inert&&root.hasAttribute('data-dsh-mobile-right-open'));
    closing.click();await pause(500);w.__DSH_INTERACTION__.setEnabled(false);await pause(100);
    assert('compatibility switch aligns native closed state and removes owned entry',nativeCloses===1&&declared.getAttribute('data-sidebar-right-open')==='false'&&!root.hasAttribute('data-dsh-right-closed')&&!outer.inert&&!w.document.querySelector('.dsh-mobile-right-entry'));
    w.__DSH_INTERACTION__.setEnabled(true);await pause(100);declared.setAttribute('data-sidebar-right-open','true');await pause(550);
    outer.querySelector('[data-sidebar-right-panel]').setAttribute('data-dsh-mobile-keep-layout','');await pause(120);
    assert('dynamic panel opt-out removes enhancement and inherited inert state',!outer.hasAttribute('data-dsh-mobile-right')&&!w.document.querySelector('.dsh-mobile-right-entry')&&!outer.inert);
    w=await mount('<div class="test_frame" data-dsh-frame>'+center+'<aside class="test_rightbarCol">Unknown plugin visibility contract</aside></div>');
    assert('unknown right visibility contract not forcibly overlaid',!w.document.querySelector('aside').hasAttribute('data-dsh-mobile-right')&&w.getComputedStyle(w.document.querySelector('aside')).visibility==='visible');
    w=await mount(tabMarkup,true);w.document.querySelector('button').click();await pause(90);
    assert('reduced motion: functional tabs without app animation',w.document.getElementById('rp').getAnimations().length===0&&!w.__compatLogs.some(s=>String(s).includes('semantic target')));
    w=await mount(tabMarkup);for(let i=0;i<20;i++)w.document.querySelector('button').click();await pause(430);
    assert('rapid tab switches leave no stuck animation',w.document.getElementById('rp').getAnimations().length===0);
    w=await mount('<div id="foreign"><section class="foreign_header">Unrecognised plugin</section></div>');
    assert('unknown shell keeps its own layout',!w.__DSH_MOBILE_COMPAT__.summary().shell&&!w.document.querySelector('[data-dsh-mobile-center]'));
    const leftMarkup='<div class="test_frame" data-dsh-frame data-sidebar-collapsed><aside id="nav" data-dsh-slot="sidebar"><div class="test_logoRow"><button data-sidebar-toggle aria-controls="nav">Navigation</button></div><p>Retained navigation content</p></aside>'+center+'</div>';
    w=await mount(leftMarkup);let lf=w.document.querySelector('[data-dsh-frame]'),ls=w.document.querySelector('aside'),lt=ls.querySelector('button');
    let leftCalls=0;lt.onclick=()=>{leftCalls++;lf.toggleAttribute('data-sidebar-collapsed');};
    w.__DSH_MOBILE_MOTION__.openLeft();await pause(100);
    assert('stable left controls work without Chinese labels or hashed classes',w.__DSH_MOBILE_MOTION__.isOpen()&&leftCalls===1);
    w.__DSH_MOBILE_MOTION__.closeLeft(true);
    assert('left local hidden content is isolated',lf.hasAttribute('data-dsh-left-closed')&&ls.inert);
    w.__DSH_INTERACTION__.setEnabled(false);await pause(120);
    assert('left compatibility exit restores native state, focus and shade',lf.hasAttribute('data-sidebar-collapsed')&&!ls.inert&&!lf.hasAttribute('data-dsh-left-closed')&&!lf.hasAttribute('data-dsh-left-managed')&&!w.document.querySelector('.dsh-mobile-sidebar-scrim'));
    w.__DSH_INTERACTION__.setEnabled(true);await pause(100);ls.setAttribute('data-dsh-mobile-keep-layout','');await pause(120);
    assert('dynamic left opt-out restores original region',!ls.hasAttribute('data-dsh-mobile-sidebar')&&!ls.inert&&!w.document.querySelector('.dsh-mobile-sidebar-scrim'));
    w=await mount(leftMarkup,false,{enabled:false});
    assert('disabled preference at startup preserves native sidebar',!w.__DSH_INTERACTION__.enabled()&&!w.document.querySelector('[data-dsh-left-managed]')&&!w.document.querySelector('.dsh-mobile-sidebar-scrim'));
    w=await mount(leftMarkup);ls=w.document.querySelector('aside');ls.setAttribute('data-dsh-mobile-no-retain','');await pause(120);
    assert('resource-heavy plugin can opt out of sidebar retention',!w.document.querySelector('[data-dsh-left-managed]')&&!w.document.querySelector('.dsh-mobile-sidebar-scrim')&&!ls.inert);
    w=await mount(leftMarkup);lf=w.document.querySelector('[data-dsh-frame]');lf.setAttribute('data-dsh-mobile-keep-layout','');await pause(120);
    assert('dynamic whole-shell opt-out removes owned controller',!lf.hasAttribute('data-dsh-mobile-frame')&&!w.document.querySelector('.dsh-mobile-sidebar-scrim'));
    w=await mount(leftMarkup+ '<button id="plain">Original action</button>',false,{legacy:true});let original=0;w.document.getElementById('plain').onclick=()=>original++;w.document.getElementById('plain').click();
    assert('missing layout capabilities leave original page and actions intact',original===1&&!w.__DSH_MOBILE_COMPAT__&&!w.document.querySelector('[data-dsh-left-managed]'));
    w=await mount(rightMarkup,false,{noInert:true});let oldCalls=0;let oldPanel=w.document.querySelector('[data-sidebar-right-open]');oldPanel.setAttribute('data-sidebar-right-open','true');await pause(80);w.document.querySelector('[data-sidebar-right-toggle]').onclick=()=>oldCalls++;w.document.querySelector('[data-sidebar-right-toggle]').click();
    assert('missing inert keeps upstream close action and no local controller entry',oldCalls===1&&!w.document.querySelector('.dsh-mobile-right-entry')&&!w.document.querySelector('[data-dsh-right-closed]'));
    for(const option of [{noAnimation:true},{throwAnimation:true}]){
      w=await mount(rightMarkup,false,option);let rp=w.document.querySelector('[data-sidebar-right-open]');rp.setAttribute('data-sidebar-right-open','true');await pause(100);w.document.querySelector('[data-sidebar-right-toggle]').click();await pause(50);
      assert('unavailable animation closes functionally: '+Object.keys(option)[0],w.document.querySelector('[data-dsh-right-closed]')&&w.getComputedStyle(w.document.querySelector('aside')).display==='none');
      w.__DSH_PHONE_RIGHT__.open();await pause(100);assert('unavailable animation reopens functionally: '+Object.keys(option)[0],!w.document.querySelector('[data-dsh-right-closed]'));
    }
    w=await mount('<div class="test_frame" data-dsh-frame data-dsh-mobile-keep-layout>'+center+'<aside class="test_rightbarCol" style="position:fixed">Owned layout</aside></div>');
    assert('whole-shell opt-out does not receive layout markers',!w.__DSH_MOBILE_COMPAT__.summary().shell&&!w.document.querySelector('[data-dsh-mobile-frame]'));
    w=await mount(leftMarkup);lf=w.document.querySelector('[data-dsh-frame]');ls=w.document.querySelector('aside');lt=ls.querySelector('button');lt.onclick=()=>lf.toggleAttribute('data-sidebar-collapsed');w.__DSH_MOBILE_MOTION__.openLeft();await pause(100);
    ls.animate=()=>({finished:new Promise(()=>{}),cancel(){}});w.__DSH_MOBILE_MOTION__.closeLeft();await pause(650);
    assert('stalled left animation has a bounded functional close',lf.hasAttribute('data-dsh-left-closed')&&ls.inert);
    w=await mount(rightMarkup);let stalled=w.document.querySelector('[data-sidebar-right-open]');stalled.setAttribute('data-sidebar-right-open','true');await pause(550);w.document.querySelector('aside').animate=()=>({finished:new Promise(()=>{}),cancel(){}});w.__DSH_PHONE_RIGHT__.close();await pause(600);
    assert('stalled right animation has a bounded functional close',!!w.document.querySelector('[data-dsh-right-closed]'));
    // Contract stubs run in the device engine; no audio or upload network is generated.
    const events=[],calls=[];let response={ok:true,status:200};let rejected;
    const stream={getAudioTracks:()=>[{}]};
    const devices={getUserMedia:function(c){calls.push({type:'mic',constraints:c});return rejected?Promise.reject(rejected):Promise.resolve(stream);}};
    function Recorder(){this.stream=stream;}Recorder.prototype.start=function(...a){calls.push({type:'start',args:a});};Recorder.prototype.stop=function(){calls.push({type:'stop'});};
    const sandbox={MediaRecorder:Recorder,fetch:function(...a){calls.push({type:'fetch',args:a});return rejected?Promise.reject(rejected):Promise.resolve(response);}};sandbox.top=sandbox;
    new w.Function('window','navigator','location','MediaRecorder','DshNative',mediaSource)(sandbox,{mediaDevices:devices},{href:'https://fixture.invalid/',origin:'https://fixture.invalid'},Recorder,{onBridgeMessage:data=>events.push(JSON.parse(data).payload)});
    const constraints={audio:{echoCancellation:true}};assert('microphone constraints and stream preserved',await devices.getUserMedia(constraints)===stream&&calls[0].constraints===constraints);
    const recorder=new Recorder();recorder.start(250);recorder.stop();
    assert('recording methods preserved and stages logged',calls.some(c=>c.type==='start'&&c.args[0]===250)&&events.some(e=>e.feature==='recording'&&e.phase==='stopped'));
    rejected=Object.assign(new Error('fixture denial'),{name:'NotAllowedError'});let caught;try{await devices.getUserMedia({audio:true});}catch(e){caught=e;}
    assert('microphone denial propagates with diagnostic stage',caught===rejected&&events.some(e=>e.feature==='microphone'&&e.detail==='NotAllowedError'));rejected=null;
    const body={fixture:'unchanged'},init={method:'POST',body};const before=calls.filter(c=>c.type==='fetch').length;
    const received=await sandbox.fetch('/remote/api/session/uploadFileBinary',init);
    assert('official upload keeps body and single request',received===response&&calls.filter(c=>c.type==='fetch').length===before+1&&calls.at(-1).args[1]===init);
    response={ok:false,status:401};assert('upload HTTP errors stay available to plugin',await sandbox.fetch('/api/session/uploadFileBinary',init)===response&&events.some(e=>e.feature==='file-upload'&&e.phase==='httpError'&&e.detail==='HTTP401'));
    rejected=Object.assign(new Error('fixture cancellation'),{name:'AbortError'});try{await sandbox.fetch('/api/session/uploadFileBinary',init);}catch(e){caught=e;}
    assert('upload cancellation propagates without automatic retry',caught===rejected&&events.some(e=>e.phase==='cancelled'));
    const earlyRoot=w.document.documentElement;w.document.removeChild(earlyRoot);let earlyError;
    try{delete w.__DSH_MOBILE_MOTION__;w.eval(sources[3]);}catch(error){earlyError=error.message;}finally{w.document.appendChild(earlyRoot);}
    assert('motion script accepts document-start before the html node exists',!earlyError,earlyError);
    assert('no fixture runtime errors',runtimeErrors.length===0,runtimeErrors);
    return {viewport:{width:w.innerWidth,height:w.innerHeight,coarse:w.matchMedia('(pointer:coarse)').matches},results};
  }finally{if(fixture)fixture.remove();}
}
const targets=await(await fetch('http://127.0.0.1:'+ (process.env.DSH_CDP_PORT||9228) +'/json')).json();
const target=targets.find(t=>t.type==='page'&&!t.url.includes('scene-live')&&!t.url.includes('doubleclick'));
if(!target)throw Error('No visible development WebView');
const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{ws.onopen=r;ws.onerror=j;});
const answer=await new Promise((r,j)=>{ws.onmessage=e=>{const data=JSON.parse(e.data);if(data.id===1)r(data);};ws.send(JSON.stringify({id:1,method:'Runtime.evaluate',params:{expression:'('+check.toString()+')('+JSON.stringify(sources)+','+JSON.stringify(mediaSource)+')',awaitPromise:true,returnByValue:true}}));setTimeout(()=>j(Error('Fixture check timed out')),30000).unref();});ws.close();
if(answer.result?.exceptionDetails)throw Error(JSON.stringify(answer.result.exceptionDetails));
const result=answer.result?.result?.value;console.log(JSON.stringify(result,null,2));if(!result||result.results.some(r=>!r.passed))process.exitCode=1;
