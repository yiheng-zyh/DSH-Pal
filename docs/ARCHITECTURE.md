# DSH Pal 架构说明

更新时间：2026-10-29。完整维护入口为 [PROJECT-HANDOFF.md](PROJECT-HANDOFF.md)，本页聚焦架构与边界。

## 工作台与原生层

DSH Web UI负责会话、工作区、插件与业务协议；Android WebView承载它。原生层负责连接入口、系统权限与文件能力、移动呈现、诊断、通知和兼容系统的媒体岛。

不重写一套固定聊天UI，避免复制上游认证、消息和插件协议；必要的布局适配由能力识别驱动，不强制用户安装作者同款插件。

```text
页面既有 WebSocket /remote/api/remote.mux
  → BridgeScript 旁听原始事件
  → DshBridge 路由、限流与线程切换
  → DshLiveService / EventReducer / TaskState
  ├→ Compose状态条
  ├→ TaskNotificationBuilder 标准任务通知
  └→ MediaIsland 标准MediaStyle/MediaSession
```

旁听不覆盖页面onmessage，不重开任务状态Socket；事件语义集中在Kotlin。系统文件选择、用户回复、资源下载和探测仍是有实际请求的交互，不能把整个App称为“从不发送数据”。

## 状态、通知与隐私

EventReducer把容错解析后的事件折叠成TaskState；未知事件按已有策略忽略，纯逻辑保持可单测，不依赖Looper/RunLog初始化。

TaskNotice仅取工具的公开说明与持久assistant/message公开text。不取思考链、命令正文、工具结果正文。收起显示最新步骤；展开标题显示简短说明或结果提示，下方最近3条步骤。较长正文提示到对话查看。公开说明仍可能包含敏感业务信息，锁屏通知由用户管理。

前台服务承担用户开启的后台状态跟踪，Android/ROM仍可限制进程、网络或dataSync时限，不能保证永不断线。任务通知、岛和后台保持开关分开处理。当前还存在平板熄屏启动前台服务超时的待修问题，不能以这段设计说明代替验收。

## 媒体岛：只走标准音乐通道

实际接入为MediaIsland.kt中的MediaStyle和MediaSession，由兼容ROM决定是否渲染为媒体岛。默认关闭，不承诺全部HyperOS机型或其他系统都显示。

小米私有焦点通知身份认证已实测失败，修改JSON不能解决白名单身份校验。IslandNotificationBuilder.kt是历史文件，不是当前接入路线；禁止重新依赖miui.focus.param。

## 连接、VPN与认证

ConnectionProfile保存origin和方式，ConnectionSwitch按准备→探测→提交执行，失败/取消恢复旧通道。VPN连接和DSH认证是独立层：内核Running、发现目标、HTTP入口、Cookie、实时Socket和业务接口需分别检查。

Tailscale/WireGuard服务按当前入口需要启停。普通返回桌面与划掉最近任务分开处理；VpnSessionGate限制迟到回调和无用户会话的自动拉起。Android单一VPN槽位与其他VPN可能冲突。

Cookie不因多个地址属于同一电脑而自动共享。MeshPairing针对当前插件的有效token复用流程，必须确认目标服务归属；不把token发送到陌生地址。

## 布局、动画与生命周期

plugin-compat.js识别已存在的区域；WebLayoutScript补几何与滚动；WebDrawerMotion等负责可安全使用的过渡。缺少能力保留原界面。

手机右栏现跳过快照拦截并由插件原生事件开合，状态/节点变化时立即修复几何，关闭整树退出触摸命中；实际tabBody自身滚动。左栏整体菜单与工作区树是两个原生滚动容器，边界允许传递。保存临时内联修改前的值，布局变化和能力移除时还原。

原生动效规范见ui/motion/Motion.kt；不把动画规范当作所有插件实际帧率已达标。键盘和视口适配按实际窗口尺寸处理，平板分屏不能仅凭物理机型判桌面。

## 缓存与诊断

StaticResourceCache只缓存允许的同origin静态GET和受约束的版本插件bundle，不缓存导航、动态API、配对或实时工作流。总容量96MiB、单文件12MiB，按响应缓存策略与版本校验更新。清资源、Cookie、页面存储和站点数据是不同操作。

资源字节统计不等于导航百分比或压缩后网络流量。网络状态应区分链路/探测/Socket/业务；近期存在探测origin不一致待办。ICMP丢包不能直接当作业务失败率。

RunLog提供六类开关和有界循环记录，导出统一脱敏；公开附件仍需人工检查。

## 修改前的检查

- 不添加Compose BOM，不升级已固定Kotlin/AGP/Miuix，不改DSH回环绑定。
- JS模板字符串内注释也不能随意包含反引号；Kotlin能编译不代表注入脚本能解析。
- 先跑语法/布局/drawer/viewport回归，再分别在手机和平板量computed几何与真实滚动。
- 当前正式包公网、平板熄屏启动、WG实网等未完成项在总交接中列明。构建/CI/截图不是端到端验收。
