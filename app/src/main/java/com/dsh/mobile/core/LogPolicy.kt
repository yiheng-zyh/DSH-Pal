package com.dsh.mobile.core

enum class LogCategory(val title:String,val purpose:String) {
    CONNECTION("连接与配对","定位配对失败、VPN 授权与连接切换"),
    NETWORK("网络与请求","定位断网、超时和业务请求失败"),
    RESOURCES("页面与资源","定位加载卡住、缓存和插件资源问题"),
    WEB("网页交互","定位网页脚本、输入及通知回复问题"),
    NOTIFICATIONS("通知与任务","定位通知、灵动岛和任务状态问题"),
    APP("应用与异常","定位启动、后台生命周期和崩溃");
    companion object {
        fun forTag(tag:String)=when(tag.lowercase()) {
            "connection","pair","vpn","permission","qrscan" -> CONNECTION
            "network","health","net","probe" -> NETWORK
            "cache","load","webview" -> RESOURCES
            "bridge","console","reply","layout","ui","files","media","file","download","keyboard","motion" -> WEB
            "service","island","task","action","notification" -> NOTIFICATIONS
            else -> APP
        }
    }
}

object LogPolicy {
    const val MAX_ENTRIES=400
    const val MAX_MESSAGE_CHARS=2048
    const val MAX_FILE_BYTES=128*1024
    /** Keep a UTF-8 suffix without cutting a multi-byte character in half. */
    fun tailUtf8(text:String,limit:Int):String {
        require(limit>=0)
        val bytes=text.toByteArray(Charsets.UTF_8)
        if(bytes.size<=limit)return text
        var start=bytes.size-limit
        while(start<bytes.size && (bytes[start].toInt() and 0xc0) == 0x80)start++
        return bytes.copyOfRange(start,bytes.size).toString(Charsets.UTF_8)
    }
}
