# App 更新与公告

本地开发版从 `0.1.0-alpha.7`（versionCode 8）开始提供：设置 → 更新与公告。关于页同时保留演示视频并新增开发者的 [B 站个人主页](https://space.bilibili.com/1834666481)。本文件说明实现和后续发布步骤，不代表该版本已经上传 GitHub。

## 手机使用

- “检查更新”读取本项目 GitHub Releases 的版本、说明和附件；使用语义版本比较，正确区分 alpha.9 / alpha.10。预发布 App 默认接收开发预览，可关闭。
- 自动检查最多每天一次，仅获取小体积版本和公告信息。不会自动下载 APK；失败不影响 DSH 工作区，不触发重新配对或页面重载。
- 发现新版后显示说明、包大小和下载入口；点击后确认使用当前网络。可查看下载进度、取消及重试，进入别的 App 页面不会取消下载。进程被系统结束后需要重试，已完整校验的缓存可复用；不宣称后台保活下载。
- APK 必须有 GitHub 提供的 SHA-256 digest 或配套 `SHA256SUMS.txt`，并且有唯一的正式 APK 附件。缺失或存在多个架构包时只提供发布页入口，避免猜错安装包。
- 下载核对大小和 SHA-256，安装前再次核对包名、版本名、递增版本号、签名与文件摘要。测试包不能用正式包覆盖自身，版本倒退和签名不同会拒绝。
- 安装通过 Android 系统确认。首次可能需要允许“此来源安装应用”，返回后再次点击安装。不会静默安装、卸载旧版、清除连接或修改 VPN 配置；更新可能重启 App，建议完成当前工作后安装。

## 公告和弹窗

- 随包的 `app/src/main/assets/app-release-notes.txt` 可离线查看，首次使用该 versionCode 时可显示本版本说明。
- 远程公告由公开仓库 `main` 分支的 `updates/announcements.json` 提供，格式见下。当前文件初始为空，需由发布者同步到 GitHub 后才能在线维护。
- 公告缓存于 App 自己的偏好设置；网络失败保留缓存。缺少远程文件会显示“公告源尚未发布”，不会伪装成成功获取。
- 同一公告 ID 或发布 ID 只提示一次，每次打开最多弹一个；连接加载、切换、任务进行中会延后提示。可关闭弹窗，手动页面仍能查看说明和公告。
- 公告是纯文本，不执行 HTML/脚本，也不自动跳转外部链接。标题 160 字符、正文 12000 字符、最多 50 条；支持按 versionCode 筛选。

```json
{
  "schemaVersion": 1,
  "announcements": [
    {
      "id": "2026-11-01-service-notice",
      "title": "维护说明",
      "body": "这里填写真实、已确认的公告内容。",
      "publishedAt": "2026-11-01",
      "minVersionCode": 8,
      "maxVersionCode": 999999
    }
  ]
}
```

ID 是已读标记，修改文字但保留 ID 不会反复弹窗。需要重新通知时发布新的 ID。不要把个人入口、配对码、账号或密钥放进公告。本项目不会自动生成未经发布者确认的服务状态。

## 给后续发布 AI 的步骤

1. 同步这次本地公开源码，检查公告文件、B 站链接及更新说明。每次发版递增 `app/build.gradle.kts` 的 versionCode，并更新 versionName 与随包说明。
2. 使用原有签名构建普通 release 包；不能用 USB 开发包或独立 debug 包代替。确认包名 `com.dsh.mobile`，保留同一签名。
3. 使用 APK 内实际版本名创建对应 Release tag，例如 `v0.1.0-alpha.7`；预发布设为 prerelease。tag 必须与 APK versionName 一致，不能只修改附件名字充当新版本。
4. 对最终命名后的 APK 生成校验记录。Windows 示例（路径换成发布机真实位置，输出目录须已存在，且没有同名的校验与元数据文件）：

```powershell
python tools/prepare-app-release.py --apk '<发布目录>\DSH-Pal-0.1.0-alpha.7.apk' --aapt2 '<Android SDK>\build-tools\36.0.0\aapt2.exe' --output '<校验输出目录>'
```

5. 在同一个 Release 上传 **未改动的 APK** 和 **SHA256SUMS.txt**，说明填入 Release 正文。`release-metadata.json` 供发布者核对，无需 App 读取。上传后应以已有较旧测试版验证发现新版、完整下载、摘要及签名检查，再验证系统安装确认；不要对用户正式数据做卸载或清除。
6. 同步 `updates/announcements.json` 到公开仓库 main 才会启用远程公告；仅提交到本地不会让现有用户收到公告。需要继续维护原公告时保留 ID。

首次发布本功能前，线上旧 App 尚不具备更新入口，仍需用户手动安装一次带本功能的版本。当前本地版高于线上版本时，“未发现比当前版本更新的已发布版本”是正常结果，不能为了演示而让用户降级或发布虚假版本。

## 文件与权限边界

下载只使用 `cacheDir/app-updates/update.part` 和 `update.apk`：失败/取消只删除本次 partial；摘要完整通过后原子替换本工具的旧 APK。不递归清理目录，不访问照片、下载目录、对话、配对、VPN 或签名私钥。新增 FileProvider 授权仅覆盖 `app-updates/` 缓存子目录，安装只临时授予读取权限。

更新请求使用独立 HTTP 客户端，不携带 DSH Cookie 或配对凭据。初始源固定到本项目 GitHub；下载重定向只允许已知 GitHub HTTPS 附件域名，限制重定向数、元数据大小、APK 大小和请求时限。安装需显式点击及系统确认，使用 `REQUEST_INSTALL_PACKAGES`。

官方依据：[GitHub Release 附件及摘要](https://docs.github.com/en/rest/releases/assets)、[Android 安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())。
