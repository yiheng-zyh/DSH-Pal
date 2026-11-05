import YAML,{isMap,YAMLMap} from 'yaml';
import {origin} from './config.mjs';
import {remotePluginConfig} from './trust.mjs';

export function planPluginPrimary(source,publicOrigin,{settings=false}={}){
  const desired={publicBaseUrl:origin(publicOrigin),autoTunnel:false,relay:false,pairingSource:'auto',tunnelToken:''};
  const doc=YAML.parseDocument(source,{keepSourceTokens:true});
  if(doc.errors.length)throw Error('Plugin configuration is not valid YAML');
  let config;
  if(settings){
    if(!isMap(doc.contents))throw Error('Settings must be a YAML mapping');
    config=doc.get('remote-web-ui');
    if(config===undefined){config=new YAMLMap();doc.set('remote-web-ui',config);}
    if(!isMap(config))throw Error('Remote plugin settings must be a YAML mapping');
  }else config=remotePluginConfig(doc);
  const changed=[];
  for(const [key,value]of Object.entries(desired)){
    if(config.get(key)===value)continue;
    config.set(key,value);changed.push(key);
  }
  return {changed:changed.length>0,fields:changed,text:changed.length?doc.toString({lineWidth:0}):source};
}
