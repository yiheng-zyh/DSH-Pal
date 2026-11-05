(function(){
 // 从当前页面 DOM 按**消息块**抽取带角色的对话。
 //
 // 为什么需要它：DOM 兜底路径早先只抓整段 innerText，一律记成"无角色"，
 // 界面上就出现大量「历史记录（无角色）」—— 但对话本来只有「我」和「助手」。
 // DSH 的消息块在 DOM 里是可识别的：用户消息靠右/带用户头像，助手消息靠左/带模型标识。
 // 这里用多种线索综合判断，任何一条命中即可，判断不出的才留 Unknown。
 if(window.__DSH_ARCHIVE_DOM__)return;

 function visible(el){
  if(!el)return false;
  var r=el.getBoundingClientRect();
  return r.width>0&&r.height>0;
 }

 function textOf(el){
  var t=(el.innerText||el.textContent||'').trim();
  return t;
 }

 /**
  * 判断一个候选块是用户还是助手。
  * 线索优先级：显式属性 > 类名/头像 > 版式（左/右）> 内容特征。
  * @returns 'user' | 'assistant' | ''
  */
 function roleOf(el,centerRect){
  // ① 显式语义属性（最可靠）
  var attr=(el.getAttribute('data-role')||el.getAttribute('data-author')||
            el.getAttribute('data-message-role')||'').toLowerCase();
  if(attr==='user'||attr==='human')return 'user';
  if(attr==='assistant'||attr==='ai'||attr==='agent'||attr==='model')return 'assistant';

  // ② 类名/aria 里带角色词
  var cls=((el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className)||'')+'';
  var aria=(el.getAttribute('aria-label')||'');
  var probe=(cls+' '+aria).toLowerCase();
  if(/\buser\b|用户消息|我的消息/.test(probe))return 'user';
  if(/\bassistant\b|助手消息|模型消息/.test(probe))return 'assistant';

  // ③ 头像/图标标识（DSH 助手消息旁常有模型名或鲸鱼图标）
  var img=el.querySelector('img[alt],svg[aria-label],[data-model]');
  if(img){
   var alt=((img.getAttribute('alt')||img.getAttribute('aria-label')||'')+'').toLowerCase();
   if(/deepseek|assistant|助手|模型|claude|gpt|harness/.test(alt))return 'assistant';
  }

  // ④ 版式：块的水平中心相对页面中心偏移（用户消息靠右）
  if(centerRect){
   var pageCenter=document.documentElement.clientWidth/2;
   var elCenter=centerRect.left+centerRect.width/2;
   // 只有明显偏一侧（>15% 页宽）才敢下结论
   var bias=(elCenter-pageCenter)/document.documentElement.clientWidth;
   if(bias>0.15)return 'user';
   if(bias<-0.15)return 'assistant';
  }
  return '';
 }

 /**
  * 抽取页面上**当前会话**的消息块。
  * @returns [{role:'user'|'assistant', text:'...'}]  按文档顺序
  */
 window.__DSH_ARCHIVE_DOM__=function(){
  // 主对话区：优先找消息列表容器，退化到整页
  var scopes=[
   document.querySelector('[data-conversation],[data-message-list],[role="log"]'),
   document.querySelector('main'),
   document.body
  ];
  var scope=null;
  for(var i=0;i<scopes.length;i++){ if(scopes[i]&&visible(scopes[i])){ scope=scopes[i]; break; } }
  if(!scope)return {ok:false,reason:'no-scope'};

  // 候选消息块：常见语义容器，再退回"含成段文字的块"
  var nodes=[].slice.call(scope.querySelectorAll(
   '[data-message],[data-message-id],[class*="_message"],[class*="_bubble"],[role="listitem"]'
  )).filter(visible);

  // 过滤掉嵌套（只保留最外层候选，避免同一段文字重复计入）
  nodes=nodes.filter(function(n){
   return !nodes.some(function(other){ return other!==n&&other.contains(n); });
  });

  var out=[];
  for(var j=0;j<nodes.length;j++){
   var el=nodes[j];
   var t=textOf(el);
   if(!t||t.length<1)continue;
   if(t.length>20000)t=t.slice(0,20000);
   var r=el.getBoundingClientRect();
   var role=roleOf(el,r);
   out.push({role:role,text:t});
  }
  return {ok:true,count:out.length,messages:out};
 };
})();
