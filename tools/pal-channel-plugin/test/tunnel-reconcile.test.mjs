/**
 * 隧道联动的行为锁 —— 防止「切换时杀掉另一条隧道」被重新加回来。
 *
 * 背景（2026-11-06 用户实测）：
 *   早先 `reconcileTunnel` 切到 Cloudflare 时会 taskkill 掉 frpc 和网关。
 *   但国内那条有 `DSH-Pal-Remote-Access-Watchdog` 每 5 分钟守护，杀完就被拉回来。
 *   结果"另一条停没停"取决于 watchdog 什么时候跑 —— 用户看到的"两条都在跑"
 *   其实是凑巧，不是可依赖状态。
 *
 * 现在的约定：**切换只改公布地址，本模块不再杀任何隧道进程。**
 * 这个测试就是这条约定的回归保护。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';

const TUNNEL_SOURCE = new URL('../lib/tunnel.js', import.meta.url);

test('reconcileTunnel 里不许出现杀进程的调用（切换不停止另一条隧道）', async () => {
  const source = await fs.readFile(TUNNEL_SOURCE, 'utf8');
  assert.doesNotMatch(source, /taskkill/i, '不许再 taskkill 隧道进程');
  assert.doesNotMatch(source, /killPids/, 'killPids 已删除，不许复活');
  assert.doesNotMatch(source, /stopCloudflared/, 'stopCloudflared 已删除，不许复活');
});

test('切换两个方向都不再返回 stopped，而是 kept / started', async () => {
  const source = await fs.readFile(TUNNEL_SOURCE, 'utf8');
  assert.doesNotMatch(source, /action:\s*'stopped'/, "不许再返回 action:'stopped'");
  assert.match(source, /action:\s*'kept'/, '应返回 kept（保持另一条运行）');
  assert.match(source, /action:\s*'started'/, '国内没起来时应能拉起');
});

test('注释里如实写明：Cloudflare 那条由插件控制，不是我们保证的', async () => {
  const source = await fs.readFile(TUNNEL_SOURCE, 'utf8');
  // 这条是防"文档说谎"：不能把两条隧道说成对称的持久。
  assert.match(source, /autoTunnel=false/, '要写明切到国内时插件自己会关掉 cloudflared');
  assert.match(source, /tunnel\.stop\(\)/, '要写明上游插件的 stop 调用点');
  assert.match(source, /做不到/, '要如实写明两条同时常驻在当前架构下做不到');
});

test('拉起国内隧道仍走既有计划任务，不自己 spawn 控制台进程', async () => {
  const source = await fs.readFile(TUNNEL_SOURCE, 'utf8');
  assert.match(source, /schtasks\.exe/, '拉起必须走计划任务');
  assert.match(source, /DSH-Pal-Remote-Access/);
  // 用户有「不许弹控制台窗口」的硬性要求，spawn powershell 直接起栈会被看到窗口。
  assert.doesNotMatch(source, /spawn\(/, '不许自己 spawn 进程');
});