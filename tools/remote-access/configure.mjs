import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {dataDirectory} from './windows.mjs';
import {installClient} from './install-client.mjs';
import {validateConfig} from './config.mjs';
import {applyTrustedHost,planTrustedHost} from './trust.mjs';

const directory=dataDirectory();
const provisioned=JSON.parse(await fs.readFile(path.join(directory,'sakura-provisioned.json'),'utf8'));
if(!/^[a-z0-9.-]+\.nyat\.app$/.test(provisioned.domain)||!/^\d+$/.test(provisioned.remote))throw Error('Invalid provisioned domain or port');
const dshConfigPath=path.resolve(process.argv[2]||path.join(process.env.USERPROFILE,'.dsh','profiles','desktop','cordis.patch.yml'));
const publicOrigin=`https://${provisioned.domain}:${provisioned.remote}`;
// Validate the exact plugin before downloading, editing, or replacing configuration.
planTrustedHost(await fs.readFile(dshConfigPath,'utf8'),publicOrigin);
const file=path.join(directory,'config.json');let existing;
try{existing=validateConfig(JSON.parse(await fs.readFile(file,'utf8')));}catch(e){if(e.code!=='ENOENT')throw e;}
if(existing&&(existing.provider!=='sakura'||existing.tunnelId!==provisioned.tunnelId||existing.publicOrigin!==publicOrigin))
  throw Error('Existing configuration belongs to another endpoint; inspect it before replacing');
const client=existing?{path:existing.frpcPath,sha256:existing.frpcSha256}:await installClient();
if(createHash('sha256').update(await fs.readFile(client.path)).digest('hex')!==client.sha256)throw Error('Configured client hash mismatch');
const config={version:1,provider:'sakura',publicOrigin,gatewayPort:18772,tunnelId:provisioned.tunnelId,
  frpcPath:client.path,frpcSha256:client.sha256,dshConfigPath};
validateConfig(config);
const trusted=await applyTrustedHost(dshConfigPath,publicOrigin);
await fs.writeFile(file,JSON.stringify(config,null,2));
console.log(JSON.stringify({configured:true,trustedHostChanged:trusted.changed,publicOrigin}));
