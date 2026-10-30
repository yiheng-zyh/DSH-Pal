# 国内IPv4接入候选核验（2026-10-30）

需求：用户当前网络IPv6不可用；优先国内IPv4、HTTPS、实时连接和可自动恢复。国内节点不等于必然能穿过受限网络，必须实测电脑出站、手机访问、配对跳转、资源、WebSocket、工作区和只读响应。免费限额和节点可能变化。

| 候选 | 官方证据与限制 | 本项目判断 |
|---|---|---|
| ds-harness-remote | 仓库确认默认托管服务、账号登录、端到端加密及P2P/TURN/Server Relay。未取得官方机房地域保证。 | 优先做独立协议适配评估，不是替换当前配对地址即可使用；需核验宿主版本和现有通知桥接。 |
| @mrrisea/dsh-remote | 截图提到，未找到可确认来源；所猜测GitHub路径404，npm页面不可访问。 | 暂不安装，不据截图认定免费或位于国内。 |
| ChmlFrp | 官网免费8Mbps、4条、不限流量；国内网页需已备案域名，HTTPS需本地证书。 | 可候选，先确认用户域名和国内节点要求。 |
| Sakura Frp | 官网普通用户10Mibps、2条、每月5GiB，签到补充。 | 候选，节点权限、实名及网页映射规则另行核验。 |
| NATAPP | 官网当前免费1M、2GB/月、随机域名/端口且不定时强制更换；免费列表无HTTPS。 | 不作稳定主入口，截图每日5GB不准确。 |
| NATNPS | 官网免费3Mbps、2条、5GB可用流量，免费二级域名及SSL。 | 备用候选；流量重置、国内节点与出站协议需面板确认。 |
| LoCyanFrp | 官网说明公益免费及全球节点；比较页有实名后带宽、隧道数等宣称。 | 当前网页有访问异常，旧资料不能代替当前节点和限额实测。 |
| cpolar | 官方支持大陆节点、HTTPS、WebSocket；免费随机地址、1Mbps。 | 可作验证备选，注册与低带宽/变址限制必须处理；本轮没有创建账号或付费。 |

首选工作顺序：修复App基本连接 → 验证DSH专用托管方案的协议边界与IPv4路径 → 对符合注册/节点条件的通用国内中转做独立备用入口。保留原连接；不自动把会话/凭据搬给新服务，不改NAS生产配置。

官方来源：
- https://github.com/liguobao/ds-harness-remote
- https://www.chmlfrp.net/ 和 https://docs.chmlfrp.net/docs/use/mapping
- https://www.natfrp.com/
- https://natapp.cn/
- https://natnps.com/
- https://www.locyanfrp.cn/ 和 https://www.locyanfrp.cn/compare/
- https://www.cpolar.com/docs 和 https://svip.cpolar.com/pricing
