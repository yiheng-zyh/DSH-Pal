import fs from 'node:fs/promises';
import path from 'node:path';
import {randomUUID} from 'node:crypto';
import {dataDirectory,backupEncrypted} from './windows.mjs';
import {validateConfig} from './config.mjs';
import {planPluginPrimary} from './plugin-primary.mjs';

const apply=process.argv.includes('--apply');
if(process.argv.slice(2).some(arg=>!['--apply','--dry-run'].includes(arg)))throw Error('Usage: node integrate-plugin.mjs [--dry-run|--apply]');
const config=validateConfig(JSON.parse(await fs.readFile(path.join(dataDirectory(),'config.json'),'utf8')));
if(!config.dshConfigPath)throw Error('Configure the DSH profile path first');
// A valid certificate and an actual public pairing surface must exist before
// retiring the previous provider. HTTP 200 at an unrelated page is not enough.
const response=await fetch(config.publicOrigin+'/api/pair/status',{signal:AbortSignal.timeout(15000),redirect:'error'});
if(!response.ok)throw Error(`Domestic pairing preflight failed: HTTP ${response.status}`);
const status=await response.json();
if(status.ok!==true||typeof status.paired!=='boolean'||typeof status.phase!=='string')throw Error('Domestic endpoint did not return the pairing protocol');
// 插件配置可能分散在不同版本的位置：
//   - 旧版：<profile>/settings.yaml（与 cordis.patch.yml 同目录）
//   - 新版 DSH 0.2.1+：只有 cordis.patch.yml，settings.yaml 可能整个不存在
// 早期实现无条件读 settings.yaml，遇到没有该文件的 profile 直接 ENOENT 失败，
// 而这一步是国内通道的最后一步 —— 前面的隧道、证书、配对全做完了却卡在这里。
// 现在按“存在才处理”来：缺哪个就跳过哪个，至少要把 cordis.patch.yml 改对。
const profileDir = path.dirname(config.dshConfigPath);
const settingsPath = path.join(profileDir, 'settings.yaml');
const candidates = [{file: config.dshConfigPath, settings: false}];
try {
  await fs.access(settingsPath);
  // 放在前面：插件主配置优先，profile patch 作为兜底。顺序影响 fields 的输出但不影响正确性。
  candidates.unshift({file: settingsPath, settings: true});
} catch {
  // 没有 settings.yaml 是合法情况（新版 DSH），不是错误。
}

const files = [];
for (const candidate of candidates) {
  let bytes;
  try {
    bytes = await fs.readFile(candidate.file);
  } catch (error) {
    // 只有显式存在过的文件才允许报错；这里再兜一层，避免竞态导致整个流程失败。
    if (error.code === 'ENOENT') continue;
    throw error;
  }
  const plan = planPluginPrimary(bytes.toString('utf8'), config.publicOrigin, {settings: candidate.settings});
  files.push({...candidate, bytes, plan});
}

if (files.length === 0) throw new Error('No writable plugin configuration found; check dshConfigPath');

console.log(JSON.stringify({
  apply,
  files: files.map((e) => ({kind: e.settings ? 'plugin settings' : 'profile patch', file: e.file, changed: e.plan.changed, fields: e.plan.fields})),
  skipped: candidates.length === files.length ? [] : ['settings.yaml 不存在，已跳过（新版 DSH 正常现象）'],
  effects: ['publish domestic HTTPS as official pairing origin', 'stop plugin-owned Cloudflare and Relay transports', 'retain device pairings and other plugins'],
}));
if(apply){
  for(const entry of files)if(entry.plan.changed)await backupEncrypted(entry.bytes);
  for(const entry of files)if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error('Configuration changed concurrently; nothing written');
  let written=0;
  for(const entry of files){
    if(!entry.plan.changed)continue;
    if(!(await fs.readFile(entry.file)).equals(entry.bytes))throw Error(`Concurrent configuration change; ${written} file(s) already updated; inspect before retrying`);
    const temporary=entry.file+'.dshpal-'+randomUUID()+'.tmp';
    try{await fs.writeFile(temporary,entry.plan.text,{flag:'wx',mode:0o600});await fs.rename(temporary,entry.file);written++;}
    finally{await fs.unlink(temporary).catch(e=>{if(e.code!=='ENOENT')throw e;});}
  }
  console.log(JSON.stringify({updatedFiles:written,encryptedBackups:true,requiresRuntimeVerification:true}));
}
