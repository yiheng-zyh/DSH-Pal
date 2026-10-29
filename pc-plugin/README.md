# Tailscale Serve 配套插件

与现有 `@linxin666/dsh-remote-web-ui` 一起使用。它仅跟踪 DSH 当前的回环端口，维护对应的 Tailscale HTTPS Serve 转发；不包含第二套登录服务，不读聊天、不改变 DSH 绑定、不启用出口节点或 Funnel。

先安装并登录电脑官方 Tailscale，启用 Serve/HTTPS，用 `tailscale serve --bg http://127.0.0.1:<实际端口>` 配好第一条转发。现有远控插件按 [连接帮助](../docs/connections.md) 配置和配对。

与 Cloudflare / Relay 共存时，保留远控插件的 `autoTunnel`、`relay` 开启，并将 Serve 主机名加入 `trustedHosts`。本插件只维护 Serve，不能以配置 Tailscale 为由关闭已有公网服务。两种入口首次分别配对，手机按地址保留凭据；日常切换不退出 Tailscale 账号。默认公布的二维码可能仍为公网地址，具体 Serve 配对方法与验证条件见连接帮助。

在对应 DSH profile 的补丁层增加以下独立条目（项目放在 ASCII 路径，示例为 `D:/dsh-mobile`）：

```yaml
- insert:
    - id: dsh-mobile-tailscale-serve
      name: 'D:/dsh-mobile/pc-plugin/index.mjs'
      config:
        origin: 'https://<设备>.<网络>.ts.net'
        previousPort: <刚才已验证的DSH端口>
```

修改前备份，只加这一条，不能改 `webserver` 配置。支持配置文件热加载的 DSH 会自动应用；否则等现有任务保存后再手动重载，不能为安装擅自中断任务。

每 15 秒检查一次，CLI 调用最多 8 秒，不并发执行。DSH 端口变化时更新自己管理的根转发。发现其他主机、其他应用的路径、TCP 转发或已启用的 Funnel 时拒绝覆盖，并记录原因。账号退出或命令失败时保留原配置并稍后重试。关闭插件不会重置整台设备的 Serve，也不删除其他业务的配置。

日志只记录阶段，不记录域名、账号、授权链接或凭据。`node tools/check-tailscale-serve.mjs` 检查路由归属和更新边界；真正的手机登录、握手与业务验证仍需真机完成。


归属信息持久保存在项目自己的 `.local/tailscale-serve-owner.json`，只包含版本、HTTPS 入口的 SHA-256 摘要和已管理端口，不含账号或密钥。下次进程启动先读取有效归属记录；无效记录按初次配置保守处理。只创建或原子替换这个插件自己的状态文件，不修改其他目录或清理数据。项目迁移后需要复核插件模块路径与归属记录，不能直接接管其他 Serve 根路由。
