# 悬浮文字框材质

设置 → 悬浮助手 → 文字框样式提供普通、液态玻璃风格、柔光玻璃风格。挂件底部 ◈ 循环切换，不需要返回 App，选择保存于本机。

当前实现是兼容原生渐变材质：液态风格采用更明显的斜向高光和边缘描边，柔光风格采用更厚、更柔和的底色。不是 Apple Liquid Glass 系统级实时折射，也没有对其他 App 或桌面截图取样。没有使用会使整个后方屏幕模糊的 blur-behind。

文字不跟随背景透明度降低；玻璃模式使用亮色字形和局部暗色阴影，不使用整块高不透明底色保护文字。2026-11-03按用户截图反馈修正：移除液态220/255、柔光240/255的最低不透明限制；液态底色为普通不透明度的26%（限制24–90/255），柔光为70%（限制100–195/255）。默认设置下三者约24%、64%、92%不透明。液态额外绘制沿圆角变化的高光边缘。参数区别和编译通过不代表所有壁纸均可读，仍需真实背景逐项验收。


参考官方资料：
- Apple Materials：https://developer.apple.com/design/human-interface-guidelines/materials
- Apple Meet Liquid Glass：https://developer.apple.com/videos/play/wwdc2025/219/
- 小米澎湃OS：https://os.mi.com/
- Android窗口模糊：https://source.android.com/docs/core/display/window-blurs

Android公开跨窗口模糊需要Android12及以上，且设备和运行状态可能禁用。当前自定义WindowManager悬浮View没有直接套用Apple或小米系统材质的能力；完整实时背景模糊/折射属于后续实现，不能把兼容渐变宣称为完整系统玻璃。
