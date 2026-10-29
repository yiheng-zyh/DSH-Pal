/* Exercise the shipping theme in an isolated document, including default rollback. */
async function testWebAppearance(source,palettes) {
  const host=document.createElement('iframe');host.style.cssText='position:fixed;left:-10000px;width:375px;height:700px';const loaded=new Promise(r=>host.onload=r);
  host.srcdoc='<html><body style="--we-ui-send:#4f8cff"><main data-dsh-mobile-frame><section data-dsh-mobile-center><article data-phase="hero"><div data-composer-seat><div class="mock_card" style="background:#202126"><div contenteditable="true" data-composer-input data-phase="plain"></div><button class="mock_add" style="background:#353638">Add</button><button class="mock_primary" aria-label="发送消息" style="background:#4f8cff">Send</button><button class="mock_primary" aria-label="停止任务" style="background:#c93232">Stop</button></div></div><div data-phase="plugin" style="background:#724ba1">Plugin</div></article></section></main></body></html>';
  document.body.append(host);await loaded;const w=host.contentWindow,d=w.document;let selected='dsh';const results=[];const css=n=>w.getComputedStyle(n),rgb=hex=>'rgb('+[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16)).join(', ')+')';
  function check(name,value){if(!value)throw Error(name);results.push(name)}
  try {
    w.DshFiles={localAppearance:()=>selected};w.eval(source.replace('if(window.top!==window)return;',''));
    for(const [id,palette] of Object.entries(palettes)){
      selected=id;w.__DSH_APPEARANCE__.apply();
      check(id+' coherent card/editor',css(d.querySelector('.mock_card')).backgroundColor===rgb(palette.surface)&&css(d.querySelector('[data-composer-input]')).backgroundColor==='rgba(0, 0, 0, 0)');
      check(id+' send follows accent and stop preserves warning',css(d.querySelector('[aria-label="发送消息"]')).backgroundColor===rgb(palette.accent)&&css(d.querySelector('[aria-label="停止任务"]')).backgroundColor==='rgb(201, 50, 50)');
      check(id+' independent plugin phase is untouched',css(d.querySelector('[data-phase="plugin"]')).backgroundColor==='rgb(114, 75, 161)');
    }
    selected='dsh';w.__DSH_APPEARANCE__.apply();
    check('DSH restores original surfaces and send token',!d.documentElement.hasAttribute('data-dsh-local-theme')&&!d.getElementById('dsh-local-appearance')&&css(d.querySelector('.mock_card')).backgroundColor==='rgb(32, 33, 38)'&&css(d.body).getPropertyValue('--we-ui-send').trim()==='#4f8cff');
    return {passed:results.length,checks:results};
  } finally {host.remove()}
}
