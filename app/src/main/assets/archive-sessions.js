(function(){
 // 会话列表读取器：给「选择要同步的对话」窗口用。
 // 只取**标题 + 时间 + 标识 + 层级**，不加载正文。
 // 与 overlay-chat.js 完全独立：那个服务于悬浮助手，这里只做列表枚举。
 if(window.__DSH_ARCHIVE_SESSIONS__)return;

 // 侧栏时间后缀：刚刚 / N分 / N小时 / N天 / 昨天 / MM-DD / HH:mm
 var TIME_TAIL=/(刚刚|\d+\s*(?:秒|分钟|分|小时|时|天|周|个月|月)前?|\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2})?|\d{1,2}月\d{1,2}日(?:\s+\d{1,2}:\d{2})?|今天|昨天|前天|\d{1,2}:\d{2})\s*$/;

 function splitTitle(raw){
  var lines=String(raw||'').split('\n').map(function(s){return s.trim()}).filter(Boolean);
  var title=lines[0]||'';
  var when='';
  var m=title.match(TIME_TAIL);
  if(m){ when=m[1]; title=title.slice(0,title.length-m[0].length).trim(); }
  else if(lines.length>1 && TIME_TAIL.test(lines[1])){ var m2=lines[1].match(TIME_TAIL); when=m2[1]; }
  if(!title)title=(lines[1]||'未命名会话');
  return {title:title,when:when};
 }

 // 判定依据来自**真机 dump 的实际结构**（2026-11-03）：
 //   data-row-key 形如 "workspace:<uuid>"  → 工作区（可展开的分组）
 //   data-row-key 形如 "session:<uuid>"    → 会话
 // 早先靠"没有时间后缀/带 aria-expanded"猜，把多个标题拼成了一个畸形组头。
 // 现在直接按前缀判定，不再猜。
 function rowKind(el){
  var key=el.getAttribute('data-row-key')||'';
  if(key.indexOf('workspace:')===0)return 'workspace';
  if(key.indexOf('session:')===0)return 'session';
  return key?'session':'unknown';
 }

 window.__DSH_ARCHIVE_SESSIONS__=function(){
  var out=[],seen={},currentGroup='';
  var sb=document.querySelector('[data-dsh-mobile-sidebar]');
  if(!sb)return {ok:true,count:0,sessions:[],origin:location.href};

  var nodes=[].slice.call(sb.querySelectorAll('[role="treeitem"]'));
  nodes.sort(function(a,b){
   return (a.compareDocumentPosition(b)&Node.DOCUMENT_POSITION_FOLLOWING)?-1:1;
  });

  for(var i=0;i<nodes.length;i++){
   var el=nodes[i];
   var key=el.getAttribute('data-row-key')||'';
   if(!key)continue;
   var raw=(el.innerText||el.textContent||'').trim();
   var t=splitTitle(raw);
   if(rowKind(el)==='workspace'){
    // 工作区头：名字常为空（图标+展开箭头），取不到就留空，由原生侧归"未分组"。
    var g=t.title.replace(/\s*\d+\s*$/,'').trim();
    currentGroup=g?g.slice(0,60):'';
    continue;
   }
   if(seen[key])continue;
   seen[key]=1;
   out.push({
    key:key,
    title:String(t.title).slice(0,80),
    when:String(t.when).slice(0,24),
    group:String(currentGroup||'').slice(0,60),
    level:0,
    index:i
   });
  }
  return {ok:true,count:out.length,sessions:out,origin:location.origin+location.pathname};
 };
})();
