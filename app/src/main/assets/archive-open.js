(function(){
 // 按 data-row-key 打开指定会话。给「同步勾选的对话」用：
 // 要抓某个会话的内容，必须先在网页上把它打开（App 只能看到当前渲染的会话）。
 if(window.__DSH_ARCHIVE_OPEN__)return;

 function sidebar(){return document.querySelector('[data-dsh-mobile-sidebar]');}

 function findRow(key){
  var sb=sidebar();
  if(!sb)return null;
  var rows=sb.querySelectorAll('[role="treeitem"][data-row-key]');
  for(var i=0;i<rows.length;i++){
   if(rows[i].getAttribute('data-row-key')===key)return rows[i];
  }
  return null;
 }

 window.__DSH_ARCHIVE_CURRENT__=function(){
  var sb=sidebar();
  if(!sb)return '';
  var rows=sb.querySelectorAll('[role="treeitem"][data-row-key][aria-selected="true"]');
  for(var i=0;i<rows.length;i++){
   var k=rows[i].getAttribute('data-row-key')||'';
   if(k.indexOf('session:')===0)return k;
  }
  return '';
 };

 /**
  * 打开一个会话。
  *
  * React 只认真实事件序列，单纯 dispatchEvent('click') 往往被忽略
  * （真机验证过：点了两次 ctx 完全没变）。这里按 pointerdown→mousedown→
  * pointerup→mouseup→click 的顺序完整模拟，并把事件的 isTrusted 之外
  * 需要的字段补全，React 的合成事件系统才会走它的 onClick。
  */
 /**
  * 取某个会话行在视口里的可点击中心坐标（供原生触摸用）。
  *
  * 原生侧必须用 dispatchTouchEvent 才能真正触发 React 的 onClick ——
  * 真机验证：JS 里 dispatchEvent 各种事件模拟都不生效，点 3 次抓到的还是同一会话。
  * @returns {x,y} 或 null（行不存在/不可见）
  */
 window.__DSH_ARCHIVE_RECT__=function(key){
  var row=findRow(key);
  if(!row)return null;
  var r=row.getBoundingClientRect();
  if(r.width<=0||r.height<=0)return null;
  // 点在行的左侧文字区（避开右侧的行操作按钮）
  return {x:Math.round(r.left+Math.min(r.width/2,60)),y:Math.round(r.top+r.height/2)};
 };

 window.__DSH_ARCHIVE_OPEN__=function(key){
  var row=findRow(key);
  if(!row)return 'missing';
  if(row.getAttribute('aria-selected')==='true')return 'already';
  // 优先点可交互的内层元素（真正的 onClick 常挂在它上面）
  var target=row.querySelector('[class*="_rowMain"],[class*="_rowContent"],[class*="_sessionRow"],button,[role="button"]')||row;
  try{
   var rect=target.getBoundingClientRect();
   var x=rect.left+Math.min(rect.width/2,40), y=rect.top+rect.height/2;
   var base={bubbles:true,cancelable:true,composed:true,view:window,
     clientX:x,clientY:y,screenX:x,screenY:y,button:0,buttons:1};
   function fire(type,Ctor){
    try{ target.dispatchEvent(new Ctor(type,base)); }catch(e){}
   }
   if(window.PointerEvent)fire('pointerdown',PointerEvent);
   fire('mousedown',MouseEvent);
   if(window.PointerEvent)fire('pointerup',PointerEvent);
   fire('mouseup',MouseEvent);
   fire('click',MouseEvent);
   return 'opened';
  }catch(e){return 'error';}
 };
})();
