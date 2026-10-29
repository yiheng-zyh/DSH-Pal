/* Run this function in a disposable browser iframe, with the shipping compat/CSS
   passed as strings. No plugin is uninstalled and the real DSH DOM is untouched. */
async function testPluginLayout(compat, css) {
  const iframe=document.createElement('iframe');
  iframe.style.cssText='position:fixed;left:-10000px;top:0;width:375px;height:700px';
  const loaded=new Promise(r=>iframe.onload=r);
  iframe.srcdoc='<html><head></head><body><main data-dsh-frame style="display:flex;height:600px"><section data-dsh-slot="center"></section></main></body></html>';
  document.body.appendChild(iframe);await loaded;
  const w=iframe.contentWindow,d=w.document,results=[];
  const wait=()=>new Promise(r=>setTimeout(r,100));
  function check(name,condition){if(!condition)throw Error('Plugin compatibility: '+name);results.push(name);}
  try {
    const style=d.createElement('style');style.textContent=css;d.head.appendChild(style);
    w.__fixtureMessages=[];
    w.eval(compat.replace('window.top !== window || ','').replace('DshNative.onBridgeMessage','window.__fixtureMessages.push'));
    await wait();
    let frame=d.querySelector('main');
    check('no sidebar, right dock or header required',frame.hasAttribute('data-dsh-mobile-frame')&&!frame.hasAttribute('data-dsh-mobile-has-left')&&!frame.hasAttribute('data-dsh-mobile-has-right'));
    const side=d.createElement('aside');side.dataset.dshSlot='sidebar';side.style.cssText='height:600px;width:260px';
    side.innerHTML='<div class="mock_root" style="display:flex;flex-direction:column;height:100%"><div class="mock_logoRow"><button aria-label="打开侧边栏"><svg viewBox="0 0 24 24"><path d="M2 12h20" /></svg></button></div><div class="mock_regionArea"><div role="tree" style="height:180px">'+('<div style="height:30px">Conversation</div>'.repeat(20))+'</div></div><div class="mock_footArea">Plugin footer</div></div>';
    frame.prepend(side);await wait();
    frame.setAttribute('data-sidebar-collapsed','');await wait();
    const entry=frame.querySelector('.dsh-mobile-sidebar-entry');let clicked=0;
    side.querySelector('button').addEventListener('click',()=>clicked++);
    check('collapsed drawer retains a visible accessible navigation entry',!!entry&&entry.getAttribute('aria-label')==='打开侧边栏'&&w.getComputedStyle(entry).display==='flex');
    entry.click();await wait();check('entry delegates once to the real plugin control',clicked===1);
    const escaped=d.createElement('button');escaped.setAttribute('aria-label','添加工作区');escaped.style.visibility='visible';side.append(escaped);await wait();
    check('plugin child visibility cannot escape a collapsed sidebar',w.getComputedStyle(escaped).visibility==='hidden');
    frame.removeAttribute('data-sidebar-collapsed');await wait();check('workspace action becomes visible inside an expanded drawer',w.getComputedStyle(escaped).visibility==='visible');
    const portal=d.createElement('span');portal.className='mock_dockFallback';portal.innerHTML='<button data-dsh-bottom-toggle="true">Bottom panel</button>';d.body.append(portal);await wait();
    check('external bottom entry stays below an open drawer',w.getComputedStyle(portal).visibility==='hidden');
    frame.setAttribute('data-sidebar-collapsed','');await wait();check('bottom entry returns after drawer closes',w.getComputedStyle(portal).visibility==='visible');
    portal.remove();escaped.remove();

    check('workspace capability enables bounded nested scrolling',!!side.querySelector('[data-dsh-mobile-workspace]')&&!!side.querySelector('[data-dsh-mobile-sidebar-scroll]'));
    check('conversation tree remains independently scrollable',getComputedStyle(side.querySelector('[role=tree]')).overflowY==='auto');
    side.querySelector('.mock_regionArea').remove();await wait();
    check('removing workspace plugin removes reserved area',!side.querySelector('[data-dsh-mobile-sidebar-scroll]'));
    side.remove();await wait();check('removing sidebar removes its navigation entry',!frame.querySelector('.dsh-mobile-sidebar-entry'));
    const unknown=d.createElement('aside');unknown.dataset.dshSlot='rightbar';unknown.className='unknown-plugin';unknown.textContent='Unknown plugin';frame.appendChild(unknown);await wait();
    check('unknown plugin keeps ownership and is not forced into dock overlay',unknown.className==='unknown-plugin'&&!unknown.hasAttribute('data-dsh-mobile-right-contract')&&!unknown.hasAttribute('data-dsh-mobile-right'));
    frame.setAttribute('data-dsh-mobile-keep-layout','');await wait();
    check('plugin layout opt-out is respected',!frame.hasAttribute('data-dsh-mobile-frame'));
    return {passed:results.length,checks:results};
  } finally {iframe.remove();}
}
