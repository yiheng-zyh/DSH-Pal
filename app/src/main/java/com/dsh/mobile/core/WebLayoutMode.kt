package com.dsh.mobile.core

enum class WebLayoutMode(val title:String,val detail:String) {
    AUTO("自动适配","手机使用紧凑布局；可用短边达到 600 dp 时使用桌面布局"),
    PHONE("手机布局","保留手机侧栏、触控间距与键盘适配"),
    DESKTOP("桌面布局","展示 DSH 原有多栏布局；窄分屏暂用紧凑布局，展开后自动恢复")
}
