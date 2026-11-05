(function(){
  if(window.top!==window||window.__DSH_EGO_STREAM_BUDGET__||!window.EventSource||!window.EventTarget)return;
  window.__DSH_EGO_STREAM_BUDGET__=true;
  var Native=window.EventSource;
  class BudgetSource extends EventTarget {
    constructor(url,options){
      super();this._url=String(url);this._options=options;this._source=null;this._closed=false;this._frames=false;this._types=new Set(['open','error','message']);
      this.onopen=null;this.onerror=null;this.onmessage=null;
      this._timer=setTimeout(()=>this._start(),0);
    }
    get url(){return new URL(this._url,location.href).href;}
    get withCredentials(){return !!(this._options&&this._options.withCredentials);}
    get readyState(){return this._closed?Native.CLOSED:this._source?this._source.readyState:Native.CONNECTING;}
    close(){this._closed=true;clearTimeout(this._timer);if(this._source)this._source.close();this._source=null;}
    addEventListener(type,listener,options){
      super.addEventListener(type,listener,options);
      if(!listener)return;
      if(type==='frame'&&!this._frames){this._frames=true;if(this._source){this._source.close();this._source=null;this._start();}}
      if(!this._types.has(type)){this._types.add(type);if(this._source)this._bind(type,this._source);}
    }
    _bind(type,source){source.addEventListener(type,e=>{
      if(this._closed||source!==this._source)return;
      var copy=type==='open'||type==='error'?new Event(type):new MessageEvent(type,{data:e.data,lastEventId:e.lastEventId,origin:e.origin});
      this.dispatchEvent(copy);var handler=this['on'+type];if(typeof handler==='function')handler.call(this,copy);
    });}
    _start(){
      if(this._closed||this._source)return;
      var url=new URL(this._url,location.href);if(!this._frames)url.searchParams.set('dsh_events_only','1');
      this._source=new Native(url.href,this._options);for(const type of this._types)this._bind(type,this._source);
    }
  }
  for(const name of ['CONNECTING','OPEN','CLOSED'])Object.defineProperty(BudgetSource,name,{value:Native[name]});
  var Wrapped=function(url,options){
    var parsed;try{parsed=new URL(String(url),location.href);}catch(_){return new Native(url,options);}
    if(parsed.origin===location.origin&&/^\/(?:remote\/)?api\/ego\/stream$/.test(parsed.pathname))return new BudgetSource(url,options);
    return options===undefined?new Native(url):new Native(url,options);
  };
  Wrapped.prototype=Native.prototype;for(const name of ['CONNECTING','OPEN','CLOSED'])Wrapped[name]=Native[name];
  window.EventSource=Wrapped;
})();
