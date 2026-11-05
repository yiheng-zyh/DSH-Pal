# 插件本地固化指南

> 为什么需要这一步：本 App 依赖的第三方插件**上游已经放弃**，随时可能从 npm 消失。
> 把插件固化到本地后，**上游怎么变都不影响你**。

---

## 一、先搞清楚风险

本 App 完全依赖 `@linxin666/dsh-remote-web-ui` 提供的浏览器远控能力：

| 本 App 依赖的能力 | 由谁提供 |
|---|---|
| `/api/pair` 配对接口 | 该插件 |
| `/remote` 代理通道 | 该插件 |
| 移动端触屏适配层 | 该插件 |
| 通道切换配置（`autoTunnel` / `publicBaseUrl` / `relay`） | 该插件 |

**上游作者的弃用声明（原话）**：

> **Deprecation notice**: `@linxin666/dsh-remote-web-ui` is deprecated in favor of
> `@linxin666/dsh-remote-bridge`. ... **Browser-based remote control has been replaced
> by the native phone app, and this package no longer provides remote access capabilities.**

**翻译**：作者说「浏览器远程控制已被替代，本包不再提供远程访问能力」——
而那正是本 App 依赖的部分。

### 三条不可控风险

| 风险 | 后果 | 概率 |
|---|---|---|
| 作者从 npm 下架 0.4.14 | 新用户装不上，App 变砖 | 中 |
| 作者发新版删浏览器面 | 用户装 `@latest` 白屏 | 高（已声明要这样做） |
| npm 服务波动 | 暂时装不上 | 低 |

**固化到本地 = 这三条全部失效。**

---

## 二、最省事的做法：一条命令

```bash
cd <本仓库>
node tools/vendor-plugin.mjs
```

**它会自动做完这些**：

1. 用仓库自带的插件副本（`third_party/dsh-remote-web-ui`，**不联网**）
2. 复制到 `~/.dsh/vendor/dsh-remote-web-ui`
3. 改你的 profile，把依赖从版本号改成 `file:` 路径
4. 改之前自动备份 `package.json`
5. 告诉你去跑 `pnpm install`

**然后按提示做**：

```bash
cd ~/.dsh/profiles/<你的profile>
pnpm install
```

重启 DSH，验证：

```bash
curl http://127.0.0.1:<端口>/api/pair/status
```

**返回 200 就成功了。**

---

## 三、命令参数详解

### 最常用

```bash
# 自动发现 profile 并固化
node tools/vendor-plugin.mjs

# 指定 profile
node tools/vendor-plugin.mjs --profile desktop

# 先看看会做什么，不实际改
node tools/vendor-plugin.mjs --dry-run
```

### 全部参数

| 参数 | 作用 | 默认 |
|---|---|---|
| `--profile <名>` | 要改哪个 profile | 自动找声明了本插件的 |
| `--target <路径>` | 固化到哪 | `~/.dsh/vendor/dsh-remote-web-ui` |
| `--dry-run` | 只检查不写盘 | 关 |
| `--force-download` | 不用仓库副本，从 npm 重下 | 关 |
| `--revert` | 改回 npm 版本 | 关 |
| `--yes` | 跳过确认 | 关 |

### 例子

**固化的位置换个地方**（比如放在项目旁边）：

```bash
node tools/vendor-plugin.mjs --target D:/my-plugins/dsh-remote-web-ui
```

**从 npm 重新下载**（怀疑仓库副本有问题时）：

```bash
node tools/vendor-plugin.mjs --force-download
```

它会：
- 从 `registry.npmjs.org` 下载 0.4.14
- **校验 sha512**（与 registry 记录比对，防篡改）
- 校验不过就中止，不会写入

**想改回 npm 版本**：

```bash
node tools/vendor-plugin.mjs --revert
```

然后同样的 `pnpm install`。

---

## 四、手工固化（不想用脚本）

### 步骤 1：复制副本

把本仓库的 `third_party/dsh-remote-web-ui` 整个目录复制到你想放的地方，例如：

```
C:\Users\<你>\.dsh\vendor\dsh-remote-web-ui
```

### 步骤 2：改 profile 的 package.json

找到你的 profile 目录（通常是 `~/.dsh/profiles/desktop/`），编辑 `package.json`：

```json
{
  "dependencies": {
    "@linxin666/dsh-remote-web-ui": "file:C:/Users/<你>/.dsh/vendor/dsh-remote-web-ui"
  }
}
```

**注意**：
- Windows 路径用正斜杠 `/`，或者双反斜杠 `\\`
- 必须写绝对路径
- 前面必须有 `file:`

### 步骤 3：装依赖

```bash
cd ~/.dsh/profiles/desktop
pnpm install
```

### 步骤 4：重启 DSH 并验证

---

## 五、验证固化成功

### 方法 1：看 profile 的依赖

```bash
# Windows PowerShell
Select-String "$env:USERPROFILE\.dsh\profiles\desktop\package.json" -Pattern 'dsh-remote-web-ui'
```

应该看到：

```
"@linxin666/dsh-remote-web-ui": "file:..."
```

**只要有 `file:` 就说明固化了。**

### 方法 2：看插件实际来源

```bash
# 看 profile 的 node_modules 里那个包是不是符号链接
ls -la ~/.dsh/profiles/desktop/node_modules/@linxin666/
```

固化后通常是**符号链接**指向你指定的目录。

### 方法 3：断网测试（最彻底）

1. 拔掉网线 / 关掉 Wi-Fi
2. 重启 DSH
3. 手机连上

**能连上 = 插件完全不依赖网络了。**

---

## 六、固化后上游变了怎么办

**什么都不用做。**

固化后你的依赖是本地文件，npm 上发生什么都不会影响你。

### 唯一的例外：你想升级

如果你哪天想升级到新版本：

```bash
node tools/vendor-plugin.mjs --revert      # 先改回 npm
# 手动改 package.json 的版本号
pnpm install
```

**但不建议** —— 上游明确说要删掉你需要的功能。

---

## 七、常见问题

### Q：固化后占多少空间？

**3.31 MB（248 个文件）。**

其中 247 个是插件本体，另外 1 个是 `README-DSH-PAL.md`（说明为什么把它放进来）。

### Q：`pnpm install` 报错怎么办？

先看错误信息：

| 错误 | 原因 | 解决 |
|---|---|---|
| `ENOENT` / 找不到路径 | `file:` 后面的路径写错了 | 检查路径是否存在、有没有打错 |
| `EACCES` 权限不足 | 文件被占用 | 关掉 DSH 再装 |
| `ERR_PNPM_LINKED_PKG_DIR_NOT_FOUND` | 目标目录被删了 | 重新跑 `vendor-plugin.mjs` |

### Q：固化会影响我的其他插件吗？

**不会。** 它只改 `package.json` 里 `@linxin666/dsh-remote-web-ui` 这一行。

### Q：怎么完全撤销？

```bash
node tools/vendor-plugin.mjs --revert
```

或者手工把 `file:...` 改回 `0.4.14`，然后 `pnpm install`。

### Q：仓库自带的副本和 npm 上的 0.4.14 一样吗？

**代码一样，额外打了一个补丁**（IPv6 黑洞修复）。

补丁说明见 `third_party/dsh-remote-web-ui/README-DSH-PAL.md`。
它在 `lib/index.js` 里用 `// [dsh-fix-ipv6-blackhole]` 标记，可以定位。

**补丁只改本进程的 Node 行为，不碰系统设置。**

### Q：仓库副本要是坏了/被删了？

脚本会自动回退到从 npm 下载：

```bash
node tools/vendor-plugin.mjs --force-download
```

**前提是 npm 上还有 0.4.14。** 如果没了，那你只能从别的地方拿一份
（或者从本仓库的 git 历史里找回来）。

---

## 八、为什么作者要弃用（背景）

作者把远程能力迁到了新包 `@linxin666/dsh-remote-bridge`。

**但新包和本 App 的架构不兼容**：

| | 旧插件 | 新插件 |
|---|---|---|
| 形态 | 把整个 DSH 网页投到手机 | 只发 JSON，客户端自己画 UI |
| 本 App 的成本 | 0（官方 UI 白捡） | 要重画一整套 |
| 与电脑端 GUI 共存 | ✅ | ❌ 互斥 |

所以本 App 选择继续用 0.4.14，并把它固化下来。

**这不是偷懒，是唯一合理的路径** —— 除非愿意重写整个 App 的界面层。
