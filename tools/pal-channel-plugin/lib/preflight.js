/**
 * 切到国内通道前的**预检**。
 *
 * 为什么必须预检（这一条是整个功能里最容易被省掉、也最不能省的一步）：
 * 切通道只是「改两个字段，让二维码指向另一个地址」。如果那个地址实际连不上，
 * 用户得到的不是"切换失败"，而是**扫不出来、且回不去**的二维码 —— 比不切更糟。
 * 所以：先在服务端自己验一遍目标入口，验不过就拒绝切换。
 *
 * 为什么必须显式 `accept-encoding: identity`：
 * 入口是经 Sakura / Cloudflare 中转回来的。中转对压缩流的处理并不可靠，
 * Node 的 fetch 默认会带 `gzip, deflate, br`，解压时抛 `Z_DATA_ERROR`
 * （真机 2026-11-04 实测：curl 拿到 200，fetch 却 reported terminated）。
 * 不要压缩就绕开了中转对 body 的破坏，而且这更接近 App 的真实请求形态。
 *
 * @module dsh-pal-channel/preflight
 */

/** 预检超时（毫秒）。经海外中转，给足时间但仍要能失败。 */
const PREFLIGHT_TIMEOUT_MS = 15000;

/**
 * 探测一个入口是否真的活着、且返回的是配对协议。
 * @param publicOrigin - 形如 https://host:port 的入口（已由 config.mjs 校验过）。
 * @param options.fetchImpl - 测试注入用，默认全局 fetch。
 * @returns {ok, detail, probe}
 */
export async function preflightDomesticOrigin(publicOrigin, { fetchImpl = fetch } = {}) {
  const probe = `${String(publicOrigin).replace(/\/+$/, '')}/api/pair/status`;
  let response;
  try {
    response = await fetchImpl(probe, {
      signal: AbortSignal.timeout(PREFLIGHT_TIMEOUT_MS),
      // 不跟随重定向：重定向到别处说明这个入口本身不是在提供配对协议。
      redirect: 'error',
      headers: { 'accept-encoding': 'identity' },
    });
  } catch (error) {
    return { ok: false, detail: describeError(error), probe };
  }
  if (!response.ok) return { ok: false, detail: `HTTP ${response.status}`, probe };
  let body;
  try {
    body = await response.json();
  } catch (error) {
    return { ok: false, detail: `返回体不是 JSON（${describeError(error)}）`, probe };
  }
  // 协议形状校验：只认「ok + paired 布尔 + phase 字符串」。
  // 不能只看 HTTP 200 —— 中转的错误页、认证页也可能是 200。
  const ok = body?.ok === true && typeof body.paired === 'boolean' && typeof body.phase === 'string';
  return { ok, detail: ok ? 'ok' : '入口未返回配对协议', probe };
}

function describeError(error) {
  const cause = error?.cause;
  return String(cause?.code ?? cause?.message ?? error?.message ?? 'preflight failed');
}
