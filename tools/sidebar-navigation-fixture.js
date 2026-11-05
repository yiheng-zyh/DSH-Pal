/* Runs in a disposable real-browser iframe. No remote account or plugin is changed. */
async function testSidebarNavigation(source) {
  const host=document.createElement('iframe');host.style.cssText='position:fixed;left:-10000px;width:375px;height:700px';
  const loaded=new Promise(r=>host.onload=r);host.srcdoc='<html><body><main data-sidebar-collapsed><aside><div class="mock_logoRow"><button aria-label="收起侧边栏">Close</button></div><nav class="mock_panelList"><button id="context">Context</button><button id="more" aria-haspopup="menu" aria-expanded="false">More</button><button id="switch" role="switch" aria-pressed="false">Toggle</button></nav><div class="mock_settingsArea"><span data-slot="settings.launcher"><button aria-label="账号菜单" aria-haspopup="menu" aria-expanded="false">Account</button></span></div></aside><section></section></main></body></html>';document.body.append(host);await loaded;
  const w=host.contentWindow,d=w.document,f=d.querySelector('main'),side=d.querySelector('aside'),toggle=side.querySelector('.mock_logoRow button'),account=side.querySelector('[aria-label="账号菜单"]');
  const results=[],wait=ms=>new Promise(r=>setTimeout(r,ms));let toggles=0,faceCalls=0;
  function check(name,value){if(!value)throw Error(name);results.push(name)}
  try {
    const shape={frame:f,left:side,center:d.querySelector('section')};w.__DSH_MOBILE_COMPAT__={snapshot:()=>shape};
    toggle.onclick=()=>{toggles++;f.toggleAttribute('data-sidebar-collapsed')};
    w.__dshRemoteAdapt={toggleSidebar:()=>{faceCalls++;toggle.click()}};
    account.onclick=()=>account.setAttribute('aria-expanded',account.getAttribute('aria-expanded')==='true'?'false':'true');
    d.getElementById('context').onclick=()=>shape.center.dataset.page='context';
    d.getElementById('more').onclick=()=>d.getElementById('more').setAttribute('aria-expanded','true');
    // Reproduce the upstream adapter's two collapse paths, after our window guard.
    d.addEventListener('click',event=>{if(event.target.closest('.mock_settingsArea')&&!f.hasAttribute('data-sidebar-collapsed')){const before=toggles;toggle.click();setTimeout(()=>{if(before===toggles)w.__dshRemoteAdapt.toggleSidebar()},240)}},true);
    w.eval(source.replace('window.top!==window||',''));
    f.removeAttribute('data-sidebar-collapsed');account.click();await wait(320);
    check('account keeps drawer and menu expanded across verified fallback',!f.hasAttribute('data-sidebar-collapsed')&&account.getAttribute('aria-expanded')==='true'&&toggles===0&&faceCalls===0);
    check('back dismisses account menu before the drawer',w.__DSH_SIDEBAR_NAV__.dismissAccountMenu());await wait(300);
    check('account dismissal does not collapse its anchor',account.getAttribute('aria-expanded')==='false'&&!f.hasAttribute('data-sidebar-collapsed'));
    check('explicit back can close drawer during account cooldown',w.__DSH_SIDEBAR_NAV__.closeDrawer()&&f.hasAttribute('data-sidebar-collapsed'));
    f.removeAttribute('data-sidebar-collapsed');
    d.getElementById('context').click();await wait(50);
    check('page action navigates then closes drawer exactly once',shape.center.dataset.page==='context'&&f.hasAttribute('data-sidebar-collapsed')&&toggles===2);
    f.removeAttribute('data-sidebar-collapsed');d.getElementById('switch').click();await wait(30);
    check('inline switch does not behave like navigation',!f.hasAttribute('data-sidebar-collapsed'));
    d.getElementById('more').click();await wait(30);check('navigation submenu stays open until a choice',!f.hasAttribute('data-sidebar-collapsed'));
    const menu=d.createElement('div');menu.setAttribute('role','menu');menu.innerHTML='<button role="menuitem">Page</button>';d.body.append(menu);menu.firstChild.onclick=()=>shape.center.dataset.page='other';menu.firstChild.click();await wait(30);
    check('portal navigation menu choice closes drawer',f.hasAttribute('data-sidebar-collapsed')&&shape.center.dataset.page==='other');
    f.removeAttribute('data-sidebar-collapsed');const before=toggles;d.getElementById('context').click();shape.left=null;await wait(30);
    check('late navigation callback cannot toggle a removed sidebar',toggles===before);
    d.dispatchEvent(new w.Event('dsh-mobile-capabilities'));
    return {passed:results.length,checks:results};
  } finally {host.remove()}
}
