import YAML,{isMap,YAMLMap} from 'yaml';
import {remotePluginConfig} from './trust.mjs';

/**
 * 公网入口通道。
 *
 * 两条通道由**同一个插件**提供，靠插件的 `autoTunnel` 开关二选一：
 *  - DOMESTIC（国内 frp）：我们自建隧道，autoTunnel=false，publicBaseUrl 指向 frp 域名
 *  - CLOUDFLARE        ：插件自带的 Quick 隧道，autoTunnel=true，publicBaseUrl 由插件自己填
 *
 * 之所以能"一键切换"，是因为真正要改的只有这两个字段 —— 不需要下载、不需要建隧道、
 * 不需要再要一次凭据。Cloudflare 的 cloudflared 由插件自己准备。
 */
export const Channel=Object.freeze({DOMESTIC:'domestic',CLOUDFLARE:'cloudflare'});

export function normalizeChannel(value){
  if(value===Channel.DOMESTIC||value===Channel.CLOUDFLARE)return value;
  throw Error(`channel must be ${Channel.DOMESTIC} or ${Channel.CLOUDFLARE}`);
}

/**
 * 规划一次通道切换。
 *
 * @param source 插件配置原文（cordis.patch.yml 或 settings.yaml）
 * @param options.settings 是否是旧版 settings.yaml 布局
 * @param options.channel 目标通道
 * @param options.domesticOrigin 切到国内通道时要发布的主入口（必须已通过预检）
 * @param options.cloudflareOrigin 切到 Cloudflare 时**当前已观测到的** CF 入口；
 *        未知时传空串，表示"由插件自己填"，我们不能凭空编一个地址。
 * @returns {changed,fields,text}
 */
export function planChannelSwitch(source,{settings=false,channel,domesticOrigin,cloudflareOrigin=''}={}){
  const target=normalizeChannel(channel);
  const doc=YAML.parseDocument(source,{keepSourceTokens:true});
  if(doc.errors.length)throw Error('Plugin configuration is not valid YAML');
  let config;
  if(settings){
    if(!isMap(doc.contents))throw Error('Settings must be a YAML mapping');
    config=doc.get('remote-web-ui');
    if(config===undefined){config=new YAMLMap();doc.set('remote-web-ui',config);}
    if(!isMap(config))throw Error('Remote plugin settings must be a YAML mapping');
  }else config=remotePluginConfig(doc);

  // 目标状态。只写与通道语义直接相关的字段，其余（trustedHosts/offlineAfterMs/
  // maxDevices 等）一律不碰 —— 那属于用户与插件的既有配置，切通道不该改它们。
  const desired=target===Channel.DOMESTIC
    ? {
        // 国内通道：关掉插件自带隧道，发布我们自己的 HTTPS 入口。
        autoTunnel:false,
        relay:false,
        publicBaseUrl:domesticOrigin,
      }
    : {
        // Cloudflare 通道：交给插件自己起 Quick 隧道。
        //
        // 关键：插件规则是「publicBaseUrl **填了就用它**，优先于 autoTunnel」
        // （见插件 locales：'穿透隧道的公网地址，填了二维码就用公网链接'）。
        // 所以切到 CF 时必须把 publicBaseUrl **清空**，否则插件会继续用旧地址，
        // 二维码永远指向旧入口 —— 2026-11-04 实测踩到。
        // 清空后由插件填入它自己拿到的 trycloudflare 地址。
        autoTunnel:true,
        relay:false,
        publicBaseUrl:cloudflareOrigin||'',
      };

  const changed=[];
  for(const [key,value] of Object.entries(desired)){
    if(config.get(key)===value)continue;
    config.set(key,value);changed.push(key);
  }
  return {changed:changed.length>0,fields:changed,channel:target,text:changed.length?doc.toString({lineWidth:0}):source};
}

/** 读出当前配置用的是哪条通道（用于 --status 与切换前判断）。 */
export function currentChannel(source,{settings=false}={}){
  const doc=YAML.parseDocument(source,{keepSourceTokens:true});
  if(doc.errors.length)throw Error('Plugin configuration is not valid YAML');
  let config;
  if(settings){
    if(!isMap(doc.contents))return null;
    config=doc.get('remote-web-ui');
  }else config=remotePluginConfig(doc);
  if(!isMap(config))return null;
  const auto=config.get('autoTunnel');
  return {
    channel:auto===true?Channel.CLOUDFLARE:Channel.DOMESTIC,
    autoTunnel:auto===true,
    publicBaseUrl:String(config.get('publicBaseUrl')??''),
    relay:config.get('relay')===true,
  };
}
