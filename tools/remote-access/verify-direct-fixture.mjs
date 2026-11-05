/**
 * 真机端到端验证夹具：起一个**独立测试网关**（不碰生产网关 18772），
 * 绑 IPv6 + 局域网地址，转发到真实 DSH 回环端口。
 *
 * 为什么用独立实例而不是改生产配置：生产网关由计划任务托管、正被 frpc 隧道使用，
 * 重启会中断用户当前连接。这里用 18998 端口证明「手机经 IPv6 直连能访问真实 DSH」，
 * 不动任何生产状态。验证完进程退出即释放端口。
 */
import {createGateway} from './gateway.mjs';
import {findDshPort} from './discovery.mjs';
import {advertisableAddresses, planDirectEntries} from './direct.mjs';

const PORT = Number(process.argv[2] || 18998);
// 必须显式传入自己的 publicOrigin：不要在这里写死任何个人域名（会被公开）。
// 用法：node verify-direct-fixture.mjs [端口] https://<你自己的公网入口>:<端口>
const PUBLIC_ORIGIN = process.argv[3];
if (!PUBLIC_ORIGIN) {
  console.error('缺少 publicOrigin 参数。用法: node verify-direct-fixture.mjs [端口] https://<你的公网入口>');
  process.exit(2);
}

const targetPort = await findDshPort({});
if (targetPort === null) { console.error('DSH port not discovered; aborting'); process.exit(2); }

const gateway = createGateway({
  publicOrigin: PUBLIC_ORIGIN,
  getTargetPort: () => targetPort,
  getDirectEntries: () => gateway.liveDirectEntries().filter(e => e.listening),
});
await gateway.listen(PORT);

const addresses = advertisableAddresses();
const plan = planDirectEntries({
  enableIPv6: true, enableLAN: true, port: PORT,
  ipv6: addresses.ipv6, ipv4: addresses.ipv4,
});
const states = await gateway.syncDirect(plan.entries);

console.log(JSON.stringify({ dshTargetPort: targetPort, testGatewayPort: PORT, direct: states }, null, 2));

const shutdown = () => { gateway.close().finally(() => process.exit(0)); };
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
// 保活，等待手机侧测试；由调用方 kill。
setInterval(() => {}, 1 << 30);
