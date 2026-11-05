/**
 * 模拟 DSH/cordis 加载 autopair，看 apply() 是否抛错。
 * 只请求导出、不触碰真实 webServer，用来暴露「加载期就炸」的问题。
 */
import {apply, inject, name} from './autopair.mjs';

console.log('name =', name);
console.log('inject =', JSON.stringify(inject));

// 最小可用的假 ctx：webServer.register 记录注册了什么
const registered = [];
const ctx = {
  logger: () => ({info: m => console.log('  [log]', m), warn: m => console.log('  [warn]', m)}),
  webServer: {
    port: 51976,
    register: route => {
      registered.push(route);
      return () => {};
    },
  },
  effect: fn => {
    const dispose = fn();
    return typeof dispose === 'function' ? dispose : () => {};
  },
  on: () => () => {},
};

try {
  apply(ctx, {autopairEnabled: true});
  console.log('\napply() OK');
  console.log('registered routes:', registered.map(r => `${r.kind} ${r.path}`).join(', ') || '(none)');
  console.log('handler type:', typeof registered[0]?.handler);
  if (registered.length === 0) {
    console.log('\nFAIL: 没有注册任何路由');
    process.exit(1);
  }
  process.exit(0);
} catch (error) {
  console.log('\napply() THREW:', error && error.stack ? error.stack : error);
  process.exit(1);
}
