;(function(){
  if(window.top!==window||window.__DSH_QUICK_COMPOSER__)return;
  var busy=false,seen=new Set();
  function visible(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!e.disabled&&!e.readOnly;}
  function context(){
    var side=document.querySelector('[data-dsh-mobile-sidebar]');
    var selected=side&&side.querySelectorAll('[role="treeitem"][aria-selected="true"][data-row-key]');
    if(!selected||selected.length!==1)return null;
    return {origin:location.origin,path:location.pathname,selection:selected[0].getAttribute('data-row-key'),title:document.title.replace(/\s*(?:—|\||-)\s*DeepSeek Harness\s*$/i,'').slice(0,120)};
  }
  function same(expected){var c=context();return c&&expected&&c.origin===expected.origin&&c.path===expected.path&&c.selection===expected.selection;}
  async function stage(text){
    if(typeof text!=='string'||!text.trim()||text.length>8000)return 'invalid';
    var center=document.querySelector('[data-dsh-mobile-center]');
    var boxes=center&&Array.from(center.querySelectorAll('textarea,[role="textbox"][contenteditable="true"]')).filter(visible);
    if(!boxes||boxes.length!==1)return 'no-composer';
    var box=boxes[0];if((box.tagName==='TEXTAREA'?box.value:box.textContent).trim())return 'draft-occupied';
    var initialPath=location.pathname,initialContext=context();
    box.focus();
    await new Promise(function(resolve){setTimeout(resolve,80);});
    if(!box.isConnected||location.pathname!==initialPath||(initialContext&&!same(initialContext)))return 'context-changed';
    if((box.tagName==='TEXTAREA'?box.value:box.textContent).trim())return 'draft-occupied';
    if(box.tagName==='TEXTAREA'){
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(box,text);box.dispatchEvent(new Event('input',{bubbles:true}));
    }else{
      var selection=window.getSelection(),range=document.createRange();range.selectNodeContents(box);range.collapse(false);selection.removeAllRanges();selection.addRange(range);
      await new Promise(function(resolve){setTimeout(resolve,80);});
      if(!box.isConnected||location.pathname!==initialPath||(initialContext&&!same(initialContext)))return 'context-changed';
      if(box.textContent.trim())return 'draft-occupied';
      if(!document.execCommand('insertText',false,text))return 'unsupported-composer';
    }
    for(var attempt=0;attempt<12;attempt++){
      await new Promise(function(resolve){setTimeout(resolve,50);});
      if(!box.isConnected||location.pathname!==initialPath||(initialContext&&!same(initialContext)))return 'context-changed';
      if((box.tagName==='TEXTAREA'?box.value:box.textContent).trim()===text.trim())return 'staged';
    }
    return 'input-not-retained';
  }
  async function submit(text,expected,id){
    if(busy||seen.has(id))return 'busy';
    if(typeof text!=='string'||!text.trim()||text.length>8000)return 'invalid';
    if(!same(expected))return 'context-changed';
    var center=document.querySelector('[data-dsh-mobile-center]');
    var boxes=center&&Array.from(center.querySelectorAll('textarea,[role="textbox"][contenteditable="true"]')).filter(visible);
    if(!boxes||boxes.length!==1)return 'no-composer';
    var box=boxes[0],value=function(){return box.tagName==='TEXTAREA'?box.value:box.textContent;};
    if(value().trim())return 'draft-occupied';
    var owner=box.parentElement,send=null;
    for(var depth=0;owner&&depth<5;depth++,owner=owner.parentElement){
      var buttons=Array.from(owner.querySelectorAll('button')).filter(function(b){return /^(发送|发送消息|Send|Send message)$/i.test((b.getAttribute('aria-label')||b.textContent||'').trim())&&b.getBoundingClientRect().width>0;});
      if(buttons.length===1){send=buttons[0];break;}
      if(owner===center)break;
    }
    if(!send)return 'no-send';
    busy=true;seen.add(id);
    try{
      box.focus();
      if(box.tagName==='TEXTAREA'){
        Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(box,text);
        box.dispatchEvent(new Event('input',{bubbles:true}));
      }else{
        // Use the browser editing path so rich-text editors keep their own state.
        var selection=window.getSelection(),range=document.createRange();range.selectNodeContents(box);range.collapse(false);selection.removeAllRanges();selection.addRange(range);
        if(!document.execCommand('insertText',false,text))return 'unsupported-composer';
      }
      for(var attempt=0;attempt<12;attempt++){
        // Lexical/React can commit the input after this editing command returns.
        await new Promise(function(resolve){setTimeout(resolve,50);});
        if(!same(expected)||!box.isConnected)return 'context-changed';
        var actual=value().trim();
        if(actual && actual!==text.trim())return 'staged';
        if(!send.isConnected)return 'staged';
        if(actual===text.trim()&&!send.disabled&&send.getAttribute('aria-disabled')!=='true'){
          send.click();return 'dispatched';
        }
      }
      return 'staged';
    }catch(_){return 'error';}finally{busy=false;}
  }
  window.__DSH_QUICK_COMPOSER__={context:context,submit:submit,stage:stage};
})();
