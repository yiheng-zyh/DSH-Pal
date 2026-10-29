package com.dsh.mobile.vpn

import android.content.Context
import com.dsh.mobile.core.RunLog
import org.json.JSONObject
import java.io.File

/** Owns only this application's native Tailscale node, never another client's LocalAPI. */
class TailscaleEngine private constructor(private val context: Context) {
    @Volatile private var app: libtailscale.Application? = null
    private var watch: libtailscale.NotificationManager? = null
    @Volatile var browseUrl: String = ""
        private set
    @Synchronized fun ensureEngine() {
        if(app != null) return
        go.Seq.setContext(context)
        val directory=File(context.filesDir,"tailscale").apply { mkdirs() }
        val application=libtailscale.Libtailscale.start(directory.absolutePath,"",false,TunnelStore(context))
        application.setClientLoggingEnabled(false)
        app=application
        watch=application.watchNotifications(0,object:libtailscale.NotificationCallback {
            override fun onNotify(bytes:ByteArray?) {
                if(bytes==null)return
                runCatching { authorizationUrl(JSONObject(String(bytes,Charsets.UTF_8)).optString("BrowseToURL")).takeIf { it.isNotBlank() }?.let { browseUrl=it } }
            }
        })
        runCatching { call("POST","start","{\"FrontendLogID\":\"dsh-mobile\"}") }
        RunLog.info("VPN","Tailscale native engine ready; version=1.104.2")
    }
    fun call(method:String,path:String,body:String?=null):String {
        ensureEngine()
        val bytes=body?.toByteArray(Charsets.UTF_8)
        val input=bytes?.let { object:libtailscale.InputStream {
            private var sent=false
            override fun read():ByteArray? { if(sent)return null;sent=true;return bytes }
            override fun close()=Unit
        } }
        val response=app!!.callLocalAPI(6_000,method,"/localapi/v0/$path",input)
        check(response.statusCode()<400) { "Tailscale LocalAPI HTTP ${response.statusCode()}" }
        return response.bodyBytes()?.let { String(it,Charsets.UTF_8) }.orEmpty()
    }
    fun status():JSONObject=JSONObject(call("GET","status")).also {
        if(it.optString("BackendState")=="Running")browseUrl=""
    }
    fun setWantRunning(want:Boolean) {
        runCatching { call("PATCH","prefs","{\"WantRunningSet\":true,\"WantRunning\":$want,\"CorpDNSSet\":true,\"CorpDNS\":true}") }
            .onFailure { RunLog.warn("VPN","Tailscale preference error ${it.javaClass.simpleName}") }
    }
    fun login() {
        browseUrl=""
        val response=call("POST","login-interactive","")
        Regex("https://login\\.tailscale\\.com/[^\\s\"\\\\]+").find(response)?.value?.let {browseUrl=it}
        RunLog.info("VPN","interactive login requested; waiting for official authorization")
    }
    fun logout() { call("POST","logout","");browseUrl="" }
    companion object {
        fun authorizationUrl(raw:String):String = runCatching {
            val uri=java.net.URI(raw)
            if(uri.scheme=="https" && (uri.host=="login.tailscale.com" || uri.host=="tailscale.com") && uri.userInfo==null)raw else ""
        }.getOrDefault("")
        @Volatile private var instance:TailscaleEngine?=null
        @JvmStatic fun get(context:Context):TailscaleEngine=instance?:synchronized(this){instance?:TailscaleEngine(context.applicationContext).also { instance=it }}
    }
}
