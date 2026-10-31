import test from 'node:test';
import assert from 'node:assert/strict';
import YAML from 'yaml';
import {planTrustedHost} from './trust.mjs';
test('trusted hosts preserve user entries and unrelated configuration; repeat is exact no-op',()=>{
  const source='# preserved\n- id: remote-web-ui\n  name: "@linxin666/dsh-remote-web-ui"\n  config:\n    trustedHosts:\n      - original.example # user entry\n    password: private-sentinel\n- name: unrelated\n  config:\n    nested: [1, true]\n';
  const plan=planTrustedHost(source,'https://remote.example:12451');
  const before=YAML.parse(source),after=YAML.parse(plan.text);
  assert.deepEqual(after[1],before[1]);assert.equal(after[0].config.password,before[0].config.password);
  assert.deepEqual(after[0].config.trustedHosts,['original.example','remote.example','remote.example:12451']);
  assert.match(plan.text,/# preserved/);assert.match(plan.text,/# user entry/);
  assert.deepEqual(planTrustedHost(plan.text,'https://remote.example:12451'),{changed:false,text:plan.text});
  const moved=YAML.parse(planTrustedHost(plan.text,'https://next.example:12452').text);
  assert.deepEqual(moved[0].config.trustedHosts,['original.example','next.example','next.example:12452']);
});
test('ambiguous or indirect plugin configurations fail closed',()=>{
  assert.throws(()=>planTrustedHost('- name: unrelated\n','https://remote.example'));
  assert.throws(()=>planTrustedHost('- id: remote-web-ui\n  config: {}\n- id: remote-web-ui\n  config: {}\n','https://remote.example'));
  assert.throws(()=>planTrustedHost('- id: remote-web-ui\n  config:\n    trustedHosts: "*"\n','https://remote.example'));
});
