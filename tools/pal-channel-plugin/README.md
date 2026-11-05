# 公网通道切换插件（`dsh-pal-channel`）

在 DSH 界面里一键切换**公网入口**：国内 frp ⇄ Cloudflare 隧道。只改
`@linxin666/dsh-remote-web-ui` 的配置，**不 import 也不 patch 它的代码**，上游插件更新不会冲掉本功能。

> **它是给谁的**：已经配好至少一条公网通道（国内 frp 或 Cloudflare），
> 想在 DSH 网页里直接点一下换入口、不想每次去改 `cordis.patch.yml` 的人。

---

## 一、它解决什么问题

远控插件的两条公网通道由两个字段决定：

```yaml
autoTunnel=false + publicBaseUrl=https://你的域名   → 走自建国内 frp 隧道
autoTunnel=true  + publicBaseUrl=""                → 走插件自带的 Cloudflare Quick 隧道
```

**插件的规则是「`publicBaseUrl` 填了就用它，优先于 `autoTunnel`」** —— 所以切到 Cloudflare 时
必须把 `publicBaseUrl` 清空，否则二维码永远指向旧入口。这个判断容易漏、漏了就是「切了没反应」，
本插件把它自动化，并且先跑预检再动手。

`channel-plan.mjs` 里的纯函数负责「该改成什么」，本插件不重复实现那套逻辑。

---

## 二、它做哪几件事

| 能力 | 接口 | 说明 |
|---|---|---|
| 读当前走哪条 | `GET /api/pal-channel/status` | 返回当前通道、两条通道各自可用性 |
| 切到另一条 | `POST /api/pal-channel/switch` | 预检 → 备份 → 改配置 → 重启隧道客户端 |
| 记录前端日志 | `POST /api/pal-channel/client-log` | 前端出错时留下证据，不记录凭据 |
| 读连接通道总览 | `GET /api/pal-channel/overview` | 所有通道状态 + 网关健康 + 本机 IPv6 体检 |
| 开关直连入口 | `POST /api/pal-channel/direct` | 写 remote-access 配置，网关 5 秒热生效 |
| 检查插件更新 | `GET /api/pal-channel/update` | 版本对比 + 更新公告（读仓库里的 `CHANGELOG.md`） |
| 应用更新 | `POST /api/pal-channel/update/apply` | 整仓 `git pull --ff-only`；有未提交改动就拒绝 |

### 连接通道总览（2026-11-06 新增）

原先通道状态散在三处，而且没有一处是给用户看的：隧道在插件配置里、直连在
`%LOCALAPPDATA%\DSHPal\remote-access\` 的 config/state 里、组网在手机 App 里。
结果是功能存在，但用户（包括作者）只会得出一个结论——「好像没配」。

总览把这些汇总成一份清单，用户在 **DSH 设置 → 连接通道总览** 里一眼看到：

- 每条通道的状态：当前使用 / 已开启 / 可切换 / 未配置 / 已开启但未监听 / 手机端管理
- **隧道切换也在这个列表里**：可切的那条后面直接是「切到这条」按钮。早先把它做成独立
  设置区块，结果左侧导航出现两个讲同一件事的入口，用户得点两遍才看到全貌 —— 现已合并成一个
- 直连通道附地址与开关；开关写的是 remote-access 配置，网关 5 秒内热生效
- 体检：本机公网 IPv6 数量 + remote-access 网关是否在跑

两条如实原则（不是装饰）：

1. **监听状态必须新鲜**：网关每 5 秒写一次 `state.json`，超过 20 秒没更新就判定它没在跑。
   否则网关被切 Cloudflare 的流程杀掉后，面板会一直谎报「已监听」。
2. **地址判据与网关同源**：直接复用 `direct.mjs` 的 `advertisableAddresses()`，
   Teredo / WSL / Tailscale 等虚拟接口一律不算，保证「面板说有几个地址」与
   「网关绑了几个监听」永远一致。

### 外部可达性：绿色必须是「真能连进来」（2026-11-06 新增）

直连通道的「已监听」只证明**本机绑上了监听**，不证明**外面能连进来**。
校园网、蜂窝网络、运营商 CGN 都会出现「本机能绑、地址也是公网的，但外部入站被阻断」。
早先只看"已监听"就给绿色，用户会以为能用、实际连不上 —— 这是误导，已改成三态：

| 状态 | 颜色 | 含义 |
|---|---|---|
| 已监听 + 外部已验证 | 绿 | 确实收到过**非本机地址**的请求 |
| 已监听 + 外部未验证 | 黄 | 本机绑上了，但还没有外部请求证据（校园网/蜂窝常阻断） |
| 未监听 / 已开但没绑上 | 黄/灰 | 分别对应「没开」和「开了没成功」 |

**外部证据怎么来的**：网关在每条直连监听器上记录「来源地址不是本机任何网卡」的请求
（`gateway.mjs` 的 `isExternalRemote`）。手机只要通过这条入口连过一次（扫码配对或
日常使用），就记下 `lastExternalAt`，面板给绿色。证据超过 7 天没更新会退回"未验证"
—— 网络会变，不能拿半年前的证据当现在。

**为什么不用第三方探测服务**：实测 check-host.net 的 TCP 检测只支持 IPv4 目标
（IPv6 域名返回 `Unknown host`，即使它的 DNS 节点能解析 AAAA），没有可用的公开
IPv6 端口探测 API。而「设备自己连过」这条证据对所有用户都成立，不依赖任何外部服务。

**非公网地址防御**：`fe80::`（链路本地）/`fc00::`/`fd00::`（ULA）这类地址即使被写进
`state.json`，也会在网关和面板各校验一次，**绝不会被当成可点链接**；detail 里会写
「已忽略 N 个非公网地址」。

### 异常时给「怎么办」：每条通道各自一个按钮（2026-11-06 新增）

状态说「不通」还不够，用户需要知道**下一步做什么**。所以每条异常通道的行末都有一个
「怎么办」按钮，点开就是**针对这条通道**的可执行步骤（不是全局面板一个按钮）：

- **IPv6 直连**：手机蜂窝数据自测 → 路由器后台放行该端口 TCP 入站（附小米/华为/OpenWrt
  的菜单名差异）→ 校园网/运营商阻断就换通道
- **Wi-Fi 直连**：确认同一 Wi-Fi → 关掉路由器「AP 隔离」→ Windows 防火墙与网络位置
  （被认成"公用网络"时入站规则不生效）→ 换网络后 IP 变了要重扫码
- **组网通道**：缺什么说什么 —— 没装 Tailscale / 装了没登录 / WireGuard 只有客户端没有服务端
- **隧道通道**：没配就指向 `docs/connections.md`，并说明切之前要先确认至少一条能连

**正常的通道不显示这个按钮** —— 不给用户制造"是不是有问题"的错觉。

### 更新检查与一键更新（2026-11-06 新增）

面板会显示当前版本，并可以检查更新、查看更新公告、一键更新。

**为什么一键更新是整仓 `git pull --ff-only`，而不是下载文件覆盖插件目录**：
本插件与 `tools/remote-access` 共享代码（import 了它的 `config.mjs` / `direct.mjs` /
`windows.mjs`）。只覆盖插件目录会拼出「新版插件 + 旧版工具」，直接加载失败，
而且用户是在"更新成功"之后才发现插件没了。

两道闸保证不吞掉用户的代码：

1. 插件目录或 `tools/remote-access` 有未提交改动 → **拒绝更新**并列出是哪些文件；
2. 不是 git 仓库 / 没有远程地址 → 拒绝并说明只能手动更新。

`--ff-only` 只做快进：本地有提交分叉时直接失败，绝不偷偷合并。
更新完成后**不自动重启 DSH**（在插件自己的请求里重启，响应会断在半路），
界面会提示用户「重启 DSH 后生效」。

**更新提醒是主动的，不靠用户自己去点。** 插件在 `shell.overlay`（覆盖在对话之上的层）
挂了提醒组件：打开 DSH 自动检查一次，之后每 10 分钟一次（服务端把结果缓存 30 分钟，
所以实际外网请求更少）。有新版本就弹出遮罩卡片，列出更新内容，用户可选「立即更新」或
「稍后」。**「稍后」只压住当前这一个版本** —— 下次发新版会重新提醒，不会永久静默。

### 为什么挂在 `ctx.connection.fetch.register`

不用 `ctx.on("api/gate", ...)` 当主挂载点，因为那个事件在官方 runtime 里**没有任何 emitter**
（上游自己的 `lib/types/gate.js` 明写 `NOTHING emits api/gate in the official runtime`）。
挂在一条永不触发的事件上 = 交付一个永远不工作的按钮。

改挂 `@deepseek-ai/dsh-client-connection` 真实的 Fetch 路由注册表后，请求会经过它的
Host/Origin 围栏与浏览器认证。`api/gate` 监听器同时保留，将来那个 seam 真启用时仍然生效。

---

## 三、安装

> ### ⚠️ 先看这个：不装依赖会直接加载失败
>
> 本插件**运行时依赖 `yaml` 包**，它装在 `tools/remote-access/node_modules` 里。
> 而 `node_modules` **被 .gitignore 排除、不进 git** —— 所以 `git clone` 下来的目录**没有它**。
>
> **实测**（2026-11-05，模拟全新 clone 环境）：
>
> ```
> $ node -e "import('./tools/pal-channel-plugin/lib/index.js')"
> LOAD FAILED: Cannot find package 'yaml' imported from .../remote-access/channel-plan.mjs
> ```
>
> 装完依赖后：
>
> ```
> $ cd tools/remote-access && npm ci --ignore-scripts --no-audit --no-fund
> added 30 packages in 1s
> $ node -e "import('./tools/pal-channel-plugin/lib/index.js')"
> LOAD OK, name= pal-channel
> ```
>
> **所以第 0 步不能跳。** 跳过的话 DSH 重启后设置里什么都不会出现，
> 而且日志里只有一个看不懂的 `Cannot find package 'yaml'`。

### 步骤 0：装依赖（**必须做，否则插件加载失败**）

打开 PowerShell，进到项目的 `tools/remote-access` 目录装依赖：

```powershell
cd <项目目录>\tools\remote-access
npm ci --ignore-scripts --no-audit --no-fund
```

**成功标志**：最后一行显示 `added N packages`（N 约为 30）。
**失败处理**：

| 报错 | 原因 | 怎么办 |
|---|---|---|
| `npm : 无法将"npm"项识别为` | 没装 Node.js | 去 <https://nodejs.org/> 装 **LTS** 版，装完**重开** PowerShell 窗口 |
| `npm ERR! ... ENOENT ... package.json` | 当前目录不对 | 确认 `pwd` 显示的是 `<项目目录>\tools\remote-access` |
| `npm ERR! network` / 卡住不动 | 国内网络访问 npm 慢 | 改用国内源：`npm ci --registry=https://registry.npmmirror.com --ignore-scripts --no-audit --no-fund` |
| `npm ci` 报 lock 文件不一致 | 有人改过 `package.json` | 改用 `npm install --ignore-scripts --no-audit --no-fund` |

**装完先自检一次**（这一步能提前发现 90% 的加载问题）：

```powershell
cd <项目目录>
node -e "import('./tools/pal-channel-plugin/lib/index.js').then(m=>console.log('LOAD OK, name=',m.name)).catch(e=>console.log('LOAD FAILED:',e.message))"
```

- 输出 `LOAD OK, name= pal-channel` → 依赖齐了，可以往下走
- 输出 `LOAD FAILED: Cannot find package ...` → 依赖没装好，回上一步

> **为什么这一步容易漏**：插件代码本身是完整的，缺的是它 import 的两个文件所在的
> `tools/remote-access` 目录的依赖。看起来「代码都在啊」，跑起来就报找不到包。

### 步骤 1：确认前置条件

需要**已经配好至少一条公网通道**。检查方法：打开 DSH 的「设置 → 远程访问」面板，
能看到 `publicBaseUrl` 或 `autoTunnel` 配置，且手机上能用那个地址进工作区。

如果两条通道都没配，先按 `docs/connections.md` 配好一条再回来。

### 步骤 2：把插件装进 DSH profile（三步，缺一不可）

> **⚠️ 不是手写 `cordis.patch.yml`。** 这个插件自带补丁声明
> （`package.json` 里的 `dsh.bundle.patch: ./cordis.patch.yml`），
> DSH 会自动读它。**你只要让 DSH 认得这个包就行。**

先找到你的 DSH profile 目录（下面用 `<profile>` 表示）：

| 你平时怎么开 DSH | profile 目录 |
|---|---|
| 双击桌面端图标 | `%USERPROFILE%\.dsh\profiles\desktop` |
| 命令行 `dsh web` | `%USERPROFILE%\.dsh\profiles\web` |

#### 2a. 在 profile 的 package.json 里加依赖

打开 `<profile>\package.json`，在 `"dependencies"` 里加一行
（**路径换成你自己的实际项目路径，用正斜杠**）：

```json
"dsh-pal-channel": "link:D:/<项目目录>/tools/pal-channel-plugin"
```

> 为什么用 `link:` 而不是版本号：这个插件不在 npm 上，`link:` 让 DSH
> 直接指向你本地的目录。改了源码重启就生效，不用重新安装。

#### 2b. 装依赖（生成 node_modules 里的符号链接）

```powershell
cd <profile>
npm install --ignore-scripts --no-audit --no-fund
# 或用 pnpm（如果 DSH 是用 pnpm 管的）
pnpm install
```

**成功标志**：`<profile>\node_modules\dsh-pal-channel` 出现，且是指向
`<项目目录>\tools\pal-channel-plugin` 的**符号链接**。

验证：

```powershell
Get-Item "<profile>\node_modules\dsh-pal-channel" | Select-Object Name,LinkType,Target
# 期望：LinkType=SymbolicLink，Target 指向你的 pal-channel-plugin 目录
```

#### 2c. 在 profile 的 package.json 里把它加进 bundles 清单

同一个 `package.json`，找 `dsh.profile.bundles` 数组，把 `"dsh-pal-channel"` 加进去：

```json
{
  "dsh": {
    "profile": {
      "bundles": [
        "...已有的一堆插件...",
        "dsh-pal-channel"
      ]
    }
  }
}
```

> **这一步最容易漏**：只做 2a/2b 的话，包能装进 node_modules，
> 但 DSH **不会加载它** —— 因为不在 bundles 清单里。
> 表现就是「装完了，设置里什么都没有」。

#### 2d. 重启 DSH

三道都做完后重启。重启方式：关掉 DSH 窗口再打开。

### 步骤 3：（已合并到步骤 2）

<!-- 原「步骤 3：重启 DSH」已并入 2d -->

### 步骤 4：怎么判断成功了

打开 DSH 网页 →「设置」，应该能看到「公网通道」区块，并且显示当前走的是哪条。

**如果看不到这个区块**，按顺序查：

| 现象 | 原因 | 怎么办 |
|---|---|---|
| **重启后设置里什么都没有** | **最常见：依赖没装** | 回步骤 0 装依赖，然后跑那条 `node -e` 自检 |
| 设置里没有「公网通道」区块 | 插件没加载 | 重启 DSH；确认 `name` 写的是**文件路径**不是包名 |
| 区块在但显示「未配置」 | 两条通道都没配 | 先配一条公网通道 |
| 点切换报「预检失败」 | 目标通道地址不可达 | 看报错里的地址，用手机浏览器打开试试 |
| 切换成功但二维码没变 | 上游插件缓存了旧地址 | 在远程访问面板点「刷新二维码」 |

命令行验证（把端口换成 DSH 当前的）：

```powershell
curl.exe -s "http://127.0.0.1:<端口>/api/pal-channel/status"
```

返回 JSON 里有 `current` 和 `channels` 即说明插件在工作。

---

## 四、安全边界（重要，别跳过）

`POST /api/pal-channel/switch` 会**改写 DSH 配置、改变手机二维码指向的公网入口**。
所以它必须只允许**本机桌面**调用 —— 一旦配对的手机能调，就等于「手机能改电脑的对外入口」。

### 难点在哪

上游的 `/remote` 通道会把远程请求**重发到 127.0.0.1** 并附上进程内的浏览器认证凭据。
于是 Host 围栏、浏览器认证、甚至 `peer.principal.kind === 'owner'` 全部照样通过 ——
在内层处理器眼里，远程请求和本机桌面请求**无法靠 socket / Host / 身份区分**。

### 本插件的判据（只用请求头，不依赖 socket）

依赖上游转发时**固定且可依赖**的副作用：

1. 所有 `sec-` 前缀的头被丢弃，只人工补一条 `sec-fetch-site: same-origin`
   ⇒ 转发进来的请求**不可能带 `sec-fetch-mode`**。而 Chromium / Android WebView
   对 `127.0.0.1`、`localhost` 这类 potentially-trustworthy 源上的 fetch **一定**带 Fetch Metadata。
2. `origin`、`cookie`、`x-forwarded-*`、`cf-connecting-ip`、`forwarded`、`x-real-ip` 都在 denylist 里
   ⇒ 转发进来的 POST **不可能带 Origin**；任何真中转会留下转发头。

最终判据：

```
GET  → 带 sec-fetch-mode，且无转发头
POST → 以上 + sec-fetch-site=same-origin + Origin 与本请求 Host 同源
```

### 残余风险（如实写明，不粉饰）

本判据依赖上游 `/remote` 的头处理策略。**若将来上游不再剥离 `sec-*` / `origin`，
这层会被削弱。** 所以写路径上另有三道防线（见 `lib/index.js` 的 `registerApiGate` 与
`lib/preflight.js`），并且每次写入前会备份配置。

---

## 五、预检与回滚

切换前会先跑**预检**（`lib/preflight.js`）：确认目标通道的入口地址真的可达，
避免「切过去发现是死的，还得手动切回来」。

失败时**不改配置**，错误原因原样返回给界面。

配置改动前会备份（`backupEncrypted`），所以即使是手工回滚也有据可依。
彻底回滚：从 `cordis.patch.yml` 删掉上面那段并重启 DSH，行为回到装插件之前。

---

## 六、目录结构

```
tools/pal-channel-plugin/
├── lib/
│   ├── index.js            Cordis host 插件入口：注册接口、安全边界
│   ├── channel-service.js  读写通道状态、执行切换（业务逻辑）
│   ├── fence.js            「本机桌面 ⇄ 远程设备」判定
│   ├── preflight.js        切换前预检目标地址
│   ├── tunnel.js           隧道客户端启停协调
│   ├── overview.js         连接通道总览：聚合隧道/直连/组网状态 + 本机体检
│   ├── direct-service.js   直连入口开关（写 remote-access 配置）
│   ├── update.js           更新检查 + 更新公告解析
│   └── update-service.js   应用更新（整仓 git pull --ff-only，脏工作区拒绝）
├── test/
│   ├── fence.test.mjs              安全边界（最该看的一份）
│   ├── channel-roundtrip.test.mjs  读写往返
│   ├── switch-contract.test.mjs    切换契约
│   └── overview.test.mjs           通道总览 / 更新检查 / 直连开关
├── CHANGELOG.md            更新公告（面板里的「更新内容」读它）
├── cordis.patch.yml        本插件自己的补丁声明
├── icon.svg                设置页图标
├── package.json            dsh.client 段（前端半边所需）
└── 安装插件.cmd            双击安装入口（Windows）
```

## 七、跑测试

```powershell
cd <项目目录>\tools\pal-channel-plugin
node --test "test/*.test.mjs"
```

`fence.test.mjs` 是安全边界的回归保护 —— **改 `fence.js` 前先看它**，
它覆盖了「伪造 sec-fetch-mode / 带转发头 / Origin 不同源」等攻击情形。
`overview.test.mjs` 守的是「面板说的话必须和事实一致」：陈旧 state.json 不能报监听、
Teredo 不能算公网地址、脏工作区不能被更新覆盖。

## 八、相关文档

- `docs/connections.md` —— 各条公网通道怎么配（先配好再来装本插件）
- `docs/AI-部署契约.md` —— 部署项清单与验收标准
- `docs/features-setup.md` —— App 侧功能总览
