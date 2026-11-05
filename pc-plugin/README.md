# Tailscale Serve 配套插件

与现有 `@linxin666/dsh-remote-web-ui` 一起使用。它仅跟踪 DSH 当前的回环端口，维护对应的 Tailscale HTTPS Serve 转发；不包含第二套登录服务，不读聊天、不改变 DSH 绑定、不启用出口节点或 Funnel。

先安装并登录电脑官方 Tailscale，启用 Serve/HTTPS，用 `tailscale serve --bg http://127.0.0.1:<实际端口>` 配好第一条转发。现有远控插件按 [连接帮助](../docs/connections.md) 配置和配对。

与 Cloudflare / Relay 共存时，保留远控插件的 `autoTunnel`、`relay` 开启，并将 Serve 主机名加入 `trustedHosts`。本插件只维护 Serve，不能以配置 Tailscale 为由关闭已有公网服务。两种入口首次分别配对，手机按地址保留凭据；日常切换不退出 Tailscale 账号。默认公布的二维码可能仍为公网地址，具体 Serve 配对方法与验证条件见连接帮助。

在对应 DSH profile 的补丁层增加以下独立条目（项目放在 ASCII 路径，示例为 `<项目目录>`）：

```yaml
- insert:
    - id: dsh-mobile-tailscale-serve
      name: '<项目目录>/pc-plugin/index.mjs'
      config:
        origin: 'https://<设备>.<网络>.ts.net'
        previousPort: <刚才已验证的DSH端口>
```

修改前备份，只加这一条，不能改 `webserver` 配置。支持配置文件热加载的 DSH 会自动应用；否则等现有任务保存后再手动重载，不能为安装擅自中断任务。

每 15 秒检查一次，CLI 调用最多 8 秒，不并发执行。DSH 端口变化时更新自己管理的根转发。发现其他主机、其他应用的路径、TCP 转发或已启用的 Funnel 时拒绝覆盖，并记录原因。账号退出或命令失败时保留原配置并稍后重试。关闭插件不会重置整台设备的 Serve，也不删除其他业务的配置。

日志只记录阶段，不记录域名、账号、授权链接或凭据。`node tools/check-tailscale-serve.mjs` 检查路由归属和更新边界；真正的手机登录、握手与业务验证仍需真机完成。


归属信息持久保存在项目自己的 `.local/tailscale-serve-owner.json`，只包含版本、HTTPS 入口的 SHA-256 摘要和已管理端口，不含账号或密钥。下次进程启动先读取有效归属记录；无效记录按初次配置保守处理。只创建或原子替换这个插件自己的状态文件，不修改其他目录或清理数据。项目迁移后需要复核插件模块路径与归属记录，不能直接接管其他 Serve 根路由。

---

# 登录即连：自动配对插件（`autopair.mjs`）

## 它解决什么

手机登录 Tailscale 之后，本来应该点一下「连接」就进去，而不是再对着电脑屏幕扫码。但上游插件
`@linxin666/dsh-remote-web-ui` 的配对令牌只能从**回环**签发：

```
127.0.0.1:<端口>/api/pair/issue        → 200
https://<设备>.ts.net/api/pair/issue   → 403 {"ok":false,"code":"forbidden"}
```

经 Serve 转发的请求带 `x-forwarded-for`，被判为「非本机」，所以手机自己签不出令牌。
本插件在电脑侧补上这一环：**校验来源确实是自己 tailnet 里的设备后，从回环代签一个令牌交回手机**。

```
手机 ──Serve(注入身份头)──▶ /dsh-mobile/autopair
     │  校验 x-forwarded-for ∈ tailscaled 设备表（且同账号）
     ▼  通过 → 插件从 127.0.0.1 代发 POST /api/pair/issue（天然满足上游的回环栅栏）
     ▼  令牌回给手机 → 手机打开 /pair-accept?pair=… → 拿到 device cookie
```

令牌仍由上游插件按它自己的策略和有效期签发，**没有绕过任何限制**；`accept` / `revoke` / `stop`
等有副作用的端点一概不碰。

## 安装

在对应 DSH profile 的补丁层（`cordis.patch.yml`）里，和上面的 Serve 条目并列再加一条：

```yaml
- insert:
    - id: dsh-mobile-autopair
      name: '<项目目录>/pc-plugin/autopair.mjs'
      config:
        autopairEnabled: true
        tailscaleBin: 'C:\Program Files\Tailscale\tailscale.exe'
```

改完**必须重启 DSH**（只有配置字段热更新生效，新插件条目要重启才会加载）。

### 怎么判断装成功了

改完并重启后，在电脑上执行（把端口换成 DSH 当前的）：

```powershell
# 换成不存在的路径做对照，两者状态码必须不同
curl.exe -s -o NUL -w "autopair=%{http_code}`n" -X POST -H "Content-Type: application/json" -d "{}" http://127.0.0.1:<端口>/dsh-mobile/autopair
curl.exe -s -o NUL -w "control =%{http_code}`n" -X POST -H "Content-Type: application/json" -d "{}" http://127.0.0.1:<端口>/dsh-mobile/nonexistent
```

- `autopair=403` 且 `control=405` → **插件已加载**（403 是本插件对「没有转发头」的正常拒绝）
- 两个都是 405 → 插件没加载。检查：条目有没有写错、DSH 是否重启过、路径是否存在

再验证经 Serve 能拿到令牌（需要电脑自己也在 tailnet 内）：

```powershell
curl.exe -s -X POST -H "Content-Type: application/json" -d "{}" https://<设备>.<网络>.ts.net/dsh-mobile/autopair
```

返回 `{"ok":true,"token":"..."}` 即通。

## 开关与回滚

| 层 | 怎么关 | 效果 |
|---|---|---|
| App 侧 | 设置 → 连接管理 → 内置通道 → Tailscale →「登录后自动配对」 | 该手机回到扫码流程 |
| 电脑端 | 把 `autopairEnabled` 改成 `false` | 所有设备回到扫码流程，插件仍在但不放行 |
| 彻底移除 | 删掉上面那段 `dsh-mobile-autopair` 并重启 DSH | 端点消失，行为与装之前完全一致 |

## 安全边界（如实说明）

信任依据是：来源 IP 出现在 `tailscale status` 的 Self/Peer 表里，且账号与电脑一致。
那张表由 tailscaled 经 WireGuard 密钥验证维护，反向代理伪造不了；`x-forwarded-for` 由 Serve
自己写入，而 Serve 只在 tailnet 内可达。

**局限**：Tailscale 1.104.2 的 `status --json` 不提供登录名（`LoginName` 为空，只有 `UserID`），
所以无法用 Serve 注入的 `tailscale-user-login` 做交叉验证。这意味着 —— **同一 tailnet 里、
同一账号的设备都会被自动信任**。自用没问题；若把 tailnet 共享给别人，请用 `allowLogins`
白名单（填登录名数组）或关掉本插件。

## 测试

```powershell
cd <项目目录>\pc-plugin
node --test autopair.test.mjs        # 19 例：自家放行 / 陌生 IP 拒绝 / 异账号拒绝 / 失败安全
node check-apply.mjs                 # apply() 不抛错、路由注册成功
node verify-integration.mjs <端口>   # 真跑 handler → 真调回环 issue → 真拿令牌
```

