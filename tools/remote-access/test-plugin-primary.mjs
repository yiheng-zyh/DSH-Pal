import test from 'node:test';
import assert from 'node:assert/strict';
import YAML from 'yaml';
import {planPluginPrimary} from './plugin-primary.mjs';
test('primary transport replaces both tunnel priorities while preserving pairings and other plugin settings',()=>{
  const source='- id: remote-web-ui\n  config:\n    autoTunnel: true\n    relay: true\n    publicBaseUrl: https://old.example\n    tunnelToken: named-sentinel\n    maxDevices: 16\n    cookieName: original\n    trustedHosts: [retained.example]\n- name: other\n  config: {setting: keep}\n';
  const result=planPluginPrimary(source,'https://new.example:12451');const d=YAML.parse(result.text);
  assert.deepEqual(d[1],YAML.parse(source)[1]);
  assert.equal(d[0].config.autoTunnel,false);assert.equal(d[0].config.relay,false);
  assert.equal(d[0].config.publicBaseUrl,'https://new.example:12451');assert.equal(d[0].config.tunnelToken,'');
  assert.equal(d[0].config.maxDevices,16);assert.equal(d[0].config.cookieName,'original');
  assert.deepEqual(d[0].config.trustedHosts,['retained.example']);
  assert.equal(planPluginPrimary(result.text,'https://new.example:12451').changed,false);
});
test('volatile settings get the same origin without overwriting unrelated values',()=>{
  const source='# keep\nremote-web-ui:\n  autoTunnel: true\n  maxDevices: 16\nother: {secret: retained}\n';
  const result=planPluginPrimary(source,'https://new.example:12451',{settings:true});const d=YAML.parse(result.text);
  assert.equal(d['remote-web-ui'].maxDevices,16);assert.deepEqual(d.other,{secret:'retained'});
  assert.match(result.text,/# keep/);assert.equal(d['remote-web-ui'].autoTunnel,false);
  assert.equal(planPluginPrimary(result.text,'https://new.example:12451',{settings:true}).changed,false);
});
