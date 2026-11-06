import net from 'node:net';
import path from 'node:path';

export function port(value,name){
  if(!Number.isInteger(value)||value<1024||value>65535)throw Error(`${name} must be an integer in 1024..65535`);
  return value;
}
export function origin(value){
  let url;try{url=new URL(value);}catch{throw Error('publicOrigin must be an HTTPS origin');}
  if(url.protocol!=='https:'||url.username||url.password||url.pathname!=='/'||url.search||url.hash)
    throw Error('publicOrigin must contain only https://host[:port]');
  if(url.hostname==='localhost'||url.hostname.endsWith('.localhost')||net.isIP(url.hostname))
    throw Error('publicOrigin must use the hostname covered by a trusted TLS certificate');
  return url.origin;
}
/**
 * 直连段（IPv6 / 局域网）。缺省或 enabled:false 时完全等同改动前的行为：
 * 网关只绑 127.0.0.1，publicOrigin 仍是唯一的对外入口。
 *
 * 与 publicOrigin 的区别是有意的：publicOrigin 要求域名 + 受信证书，因为它经
 * frpc 隧道对外发布；直连入口是 IP 字面量 + http，因为 IPv6/LAN 字面量拿不到
 * 公共证书，而 App 不信任自签证书（没有 onReceivedSslError 处理），走 https
 * 只会让用户看到证书错误页。两者校验规则不能混用。
 *
 * 这里只存「开哪些种类」的开关，不存具体地址：IPv6 隐私扩展与 DHCP 会让地址
 * 变化，run.mjs 每轮用实测的本机地址重新规划，换网络后自动跟随，无需改配置。
 */
export function direct(value,name='direct'){
  if(value===undefined||value===null)return undefined;
  if(typeof value!=='object'||Array.isArray(value))throw Error(`${name} must be an object`);
  const out={enabled:value.enabled===true};
  if(!out.enabled){out.ipv6=false;out.lan=false;return out;}
  out.ipv6=value.ipv6===true;
  out.lan=value.lan===true;
  if(!out.ipv6&&!out.lan)throw Error(`${name}: enable at least one of ipv6/lan when enabled`);
  return out;
}
/**
 * 用户自己的 HTTPS 入口（可选）。
 *
 * 用途：用户已经有现成的 HTTPS 域名 —— 自己的 nginx / caddy 反代、Cloudflare Named
 * 隧道、或者公司给的反向代理 —— 不想走 Sakura frp，就填这里。
 *
 * 为什么单独一个字段而不是复用 publicOrigin：
 * publicOrigin 是「本工具自己那条隧道发布的地址」，切换 provider / 重配隧道会重写它。
 * 把用户的自定义域名放进同一个字段，一次重配就会被冲掉。分成两个字段后，
 * `customOrigin` 只要非空就优先作为对外发布地址（见 publishedOrigin），
 * 两个来源互不覆盖。
 */
export function customOrigin(value, name = 'customOrigin') {
  if (value === undefined || value === null || value === '') return undefined;
  if (typeof value !== 'string') throw Error(`${name} must be a string`);
  let url;
  try {
    url = new URL(value);
  } catch {
    throw Error(`${name} must be an HTTPS origin like https://your.domain`);
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash)
    throw Error(`${name} must contain only https://host[:port]`);
  if (url.hostname === 'localhost' || url.hostname.endsWith('.localhost') || net.isIP(url.hostname))
    throw Error(`${name} must use a hostname covered by a trusted TLS certificate`);
  return url.origin;
}

/**
 * 当前对外发布的入口：自定义优先，其次是自己那条隧道的 publicOrigin。
 * 网关、trustedHosts、配对二维码必须都用这一个口径，否则会出现
 * 「二维码给的是 A、Host 白名单放的是 B」这种连不上的组合。
 */
export function publishedOrigin(config) {
  return config?.customOrigin || config?.publicOrigin;
}

export function validateConfig(raw){
  if(!raw||raw.version!==1)throw Error('Expected remote-access configuration version 1');
  const value={version:1,publicOrigin:origin(raw.publicOrigin),gatewayPort:port(raw.gatewayPort??18772,'gatewayPort')};
  const custom = customOrigin(raw.customOrigin);
  if (custom !== undefined) value.customOrigin = custom;
  if(raw.dshPort!==undefined)value.dshPort=port(raw.dshPort,'dshPort');
  if(value.dshPort===value.gatewayPort)throw Error('Gateway and DSH ports must differ');
  if(!['sakura','external'].includes(raw.provider))throw Error('provider must be sakura or external');
  value.provider=raw.provider;
  if(raw.dshConfigPath!==undefined){
    if(typeof raw.dshConfigPath!=='string'||!path.isAbsolute(raw.dshConfigPath))throw Error('dshConfigPath must be absolute');
    value.dshConfigPath=raw.dshConfigPath;
  }
  const directConfig=direct(raw.direct);
  if(directConfig!==undefined)value.direct=directConfig;
  if(raw.provider==='sakura'){
    if(typeof raw.frpcPath!=='string'||!raw.frpcPath.trim())throw Error('frpcPath is required');
    if(typeof raw.frpcSha256!=='string'||!/^[a-f0-9]{64}$/i.test(raw.frpcSha256))throw Error('frpcSha256 is required');
    if(!Number.isSafeInteger(raw.tunnelId)||raw.tunnelId<1)throw Error('tunnelId must be a positive integer');
    value.frpcPath=raw.frpcPath;value.frpcSha256=raw.frpcSha256.toLowerCase();value.tunnelId=raw.tunnelId;
  }
  for(const name of ['token','password','authtoken','secret'])if(Object.hasOwn(raw,name))throw Error('Secrets belong in the encrypted credential store, not configuration JSON');
  return value;
}
