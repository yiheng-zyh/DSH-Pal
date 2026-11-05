# dsh-remote-web-ui（第三方插件的本地副本）

## 这是什么

`@linxin666/dsh-remote-web-ui@0.4.14` 的**完整本地副本**，放在本仓库内分发。

- 上游仓库：<https://github.com/zhu1090093659/dsh-web>
- npm 包：<https://www.npmjs.com/package/@linxin666/dsh-remote-web-ui>
- 版本：**0.4.14**
- 许可证：**Apache-2.0**（允许再分发，见本目录 `LICENSE`）

---

## 为什么把别人的代码放进本仓库

因为**上游已经放弃了这个插件**，而本 App 完全依赖它。

### 上游的弃用声明（原文）

`@linxin666/dsh-remote-web-ui` 包内 README 顶部：

> **Deprecation notice**: `@linxin666/dsh-remote-web-ui` is deprecated in favor of
> `@linxin666/dsh-remote-bridge`. It has been retired from the `@linxin666/dsh-web-all`
> aggregate bundle and will be removed in the next release. Standalone installation
> remains functional during this transitional period. **Browser-based remote control
> has been replaced by the native phone app, and this package no longer provides
> remote access capabilities.**

**关键**：作者明确说「浏览器远程控制已被替代」——而那正是本 App 依赖的部分。

### 这意味什么

| 风险 | 后果 |
|---|---|
| 作者从 npm 下架 0.4.14 | 新用户装不上，App 变砖 |
| 作者发新版删掉浏览器面 | 用户装 `@latest` 就白屏 |
| npm 服务不可用 | 装不上 |

**这三条都不可控，全在上游手里。**

### 所以我们怎么做

1. **把 0.4.14 完整副本放进本仓库**（就是这个目录）
2. 用户的 profile 指向本地副本，而不是 npm
3. 这样**上游怎么变都不影响本 App**

---

## 本副本与 npm 官方版的差异

本副本基于 npm 官方的 `dsh-remote-web-ui-0.4.14.tgz`（sha512 与 registry 记录一致），
**额外打了一个补丁**：

### IPv6 黑洞补丁

**问题**：校园网等环境下，本机有 IPv6 地址与默认路由，但 IPv6 出网被关停 →
连接黑洞（丢包不回 RST）。插件用 Node 原生 `fetch`（undici）访问
`https://dsh-market.com/api/relay/register` 时，Node ≥20 默认开启 Happy Eyeballs
（`net.setDefaultAutoSelectFamily(true)`），把 AAAA 也当候选 → 7/10 失败。

**实测**（2026-10-28，同一 IP `104.21.47.118`）：
- 默认 `autoSelectFamily=ON` → 7/10 成功，失败清一色 `UND_ERR_CONNECT_TIMEOUT @10600ms`
- 关掉 `autoSelectFamily`（IPv4 优先）→ **10/10 成功**，中位 729ms

**补丁做什么**：
- 每 60 秒读一次当前 SSID
- 只在命中「已知 IPv6 黑洞」名单时，把**本进程**的出网改成 IPv4 优先
- 换到别的网络自动恢复默认
- **只改本进程的 Node 行为，不碰系统 IPv6、不碰网卡、不碰任何系统设置**

**补丁标记**：`// [dsh-fix-ipv6-blackhole] begin` / `end`
（在 `lib/index.js` 里搜这个标记可以定位）

**幂等**：已打过就静默退出
**失败安全**：锚点找不到就放弃，绝不猜着改
**可还原**：标记块可整体剥除

**补丁脚本**（用于重新应用）：本仓库的 `D:\ds\DSH自动修复\patch-remote-web-ui-ipv6.mjs`
（不在本仓库内，属于用户本机工具）

---

## 用户需要做什么

### 方案 A：用本仓库的副本（推荐）

```bash
# 在 DSH profile 目录里，把依赖指向本目录
# 例如 C:\Users\<你>\.dsh\profiles\desktop\package.json
```

把：

```json
"@linxin666/dsh-remote-web-ui": "0.4.14"
```

改成：

```json
"@linxin666/dsh-remote-web-ui": "file:<本仓库绝对路径>/third_party/dsh-remote-web-ui"
```

然后在该 profile 目录执行：

```bash
pnpm install
```

重启 DSH，验证 `http://127.0.0.1:<端口>/api/pair/status` 返回 200。

**详细步骤见**：`docs/PLUGIN-VENDOR-GUIDE.md`

### 方案 B：从 npm 装（如果你确认 npm 上还有）

```bash
dsh plugin --profile desktop add @linxin666/dsh-remote-web-ui@0.4.14
```

**必须固定 0.4.14，不要用 `@latest`。**

---

## 许可证合规

Apache-2.0 允许再分发，但要求：

| 要求 | 本仓库怎么做的 |
|---|---|
| 保留 LICENSE | ✅ 本目录有完整 `LICENSE` |
| 保留版权声明 | ✅ 保留在 LICENSE 与原文件中 |
| 声明修改 | ✅ 本文件第「本副本与 npm 官方版的差异」节说明 |
| 不得暗示原作者背书 | ✅ 本文件明确说明这是本地副本，非官方发布 |

**本 App 与原作者无隶属关系。**
