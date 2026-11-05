/* Verify action identity and the upstream-close/async-capture race together. */
async function testDrawerNavigationRace(navigation,motion) {
  const host=document.createElement('iframe');host.style.cssText='position:fixed;left:-10000px;width:375px;height:700px';const loaded=new Promise(r=>host.onload=r);
  host.srcdoc='<html><body><main data-dsh-mobile-frame><aside data-dsh-mobile-sidebar><div class="mock_logoRow"><button class="mock_brand" aria-label="新建会话">Brand</button><button class="mock_toggle" aria-label="收起侧边栏">Close</button></div><button class="mock_newSession">New</button></aside><section data-dsh-mobile-center></section></main></body></html>';document.body.append(host);await loaded;
  const w=host.contentWindow,d=w.document,f=d.querySelector('main'),side=d.querySelector('aside'),center=d.querySelector('section'),toggle=d.querySelector('.mock_toggle'),brand=d.querySelector('.mock_brand'),start=d.querySelector('.mock_newSession');const results=[],wait=()=>new Promise(r=>setTimeout(r,80));let brandClicks=0,toggles=0;
  const check=(name,value)=>{if(!value)throw Error(name);results.push(name)};
  try {
    w.__DSH_MOBILE_COMPAT__={snapshot:()=>({frame:f,left:side,center})};
    d.startViewTransition=cb=>{const ready=new Promise(resolve=>setTimeout(resolve,30)).then(cb);return{ready,finished:ready.then(()=>new Promise(r=>setTimeout(r,10))),skipTransition(){}}};
    toggle.onclick=()=>{toggles++;f.toggleAttribute('data-sidebar-collapsed')};
    brand.onclick=()=>{brandClicks++;center.dataset.page='hero';f.setAttribute('data-sidebar-collapsed','')};
    start.onclick=()=>{center.dataset.page='hero';f.setAttribute('data-sidebar-collapsed','')};
    w.eval(navigation.replace('window.top!==window||',''));w.eval(motion.replace('window.top!==window||',''));
    d.addEventListener('click',e=>{if(e.target.closest('.mock_newSession,.mock_brand'))toggle.click()},true);
    start.click();await wait();
    check('new session already closed by React cannot be reopened by animation',f.hasAttribute('data-sidebar-collapsed')&&toggles===0);
    f.removeAttribute('data-sidebar-collapsed');start.onclick=()=>center.dataset.page='hero';start.click();await wait();
    check('two automatic close requests coalesce without reversing the slide',f.hasAttribute('data-sidebar-collapsed')&&toggles===1);
    f.removeAttribute('data-sidebar-collapsed');center.dataset.page='context';d.querySelector('.dsh-drawer-scrim').click();await wait();
    check('scrim closes using toggle without navigating home',f.hasAttribute('data-sidebar-collapsed')&&center.dataset.page==='context'&&brandClicks===0);
    f.removeAttribute('data-sidebar-collapsed');brand.click();await wait();
    check('brand action navigates once and settles closed',f.hasAttribute('data-sidebar-collapsed')&&brandClicks===1&&center.dataset.page==='hero');
    return{passed:results.length,checks:results};
  } finally {host.remove()}
}
