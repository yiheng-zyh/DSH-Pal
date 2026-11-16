import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

const source = readFileSync(new URL('../app/src/main/assets/web-plugin-policy.js', import.meta.url), 'utf8');
const graph = {
  rev: 'fixture',
  entries: [
    {id: '@deepseek-ai/chat', external: ['@example/required/client']},
    {id: '@example/required', external: ['shared-dependency']},
    {id: 'shared-dependency'},
    {id: 'optional-decoration'},
  ],
  batches: [
    {entries: ['@deepseek-ai/chat', '@example/required', 'shared-dependency'], url: '/original-core-bundle'},
    {entries: ['optional-decoration'], url: '/original-decoration-bundle'},
  ],
};

function context(paused) {
  const page = {DshFiles: {pausedWebPlugins: () => JSON.stringify(paused)}};
  page.window = page;
  page.top = page;
  runInNewContext(source, page);
  return page;
}

const normal = context([]);
normal.__DSH_BOOT__ = graph;
assert.equal(normal.__DSH_BOOT__, graph);

const compatible = context(['optional-decoration', '@example/required', 'shared-dependency']);
compatible.__DSH_BOOT__ = graph;
assert.equal(compatible.__DSH_BOOT__.entries.length, 3);
assert.equal(compatible.__DSH_BOOT__.batches.length, 1);
assert.equal(compatible.__DSH_BOOT__.batches[0].url, '/original-core-bundle');
assert.equal(graph.entries.length, 4);
const report = compatible.__DSH_PAL_PLUGIN_POLICY__.report();
assert.deepEqual(Array.from(report.paused), ['optional-decoration']);
assert.deepEqual(Array.from(report.required), ['@example/required', 'shared-dependency']);

const unknown = {manifest: {}};
compatible.__DSH_BOOT__ = unknown;
assert.equal(compatible.__DSH_BOOT__, unknown);
const wallpaper = 'dsh-plugin-wallpaper-engine';
const wallpaperGraph = {
  entries: [wallpaper, wallpaper + '-glass', wallpaper + '-fonts', '@deepseek-ai/chat'].map(id => ({id})),
  batches: [{entries: [wallpaper, wallpaper + '-glass', wallpaper + '-fonts', '@deepseek-ai/chat']}],
};
for (const [selection, expected] of [
  [[wallpaper], [wallpaper, wallpaper + '-glass', wallpaper + '-fonts']],
  [[wallpaper + '-fonts'], [wallpaper + '-fonts']],
]) {
  const page = context(selection);
  page.__DSH_BOOT__ = wallpaperGraph;
  assert.deepEqual(Array.from(page.__DSH_PAL_PLUGIN_POLICY__.report().paused), expected);
  assert.ok(page.__DSH_BOOT__.entries.some(row => row.id === '@deepseek-ai/chat'));
  assert.ok(page.__DSH_BOOT__.batches[0].entries.every(id => !expected.includes(id)));
  const entries = {sync: graph => graph};
  page.__ModuleLoader__ = {create: () => ({entries})};
  const refreshed = page.__ModuleLoader__.create().entries.sync(wallpaperGraph);
  assert.ok(refreshed.entries.every(row => !expected.includes(row.id)));
}
console.log('PASS plugin isolation, dependency retention, cache URLs and wallpaper family');
