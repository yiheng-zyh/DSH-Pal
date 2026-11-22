package com.dsh.mobile.core

import android.content.Context
import android.os.SystemClock
import android.webkit.CookieManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ComputerProcess(val identity:String,val pid:Int,val name:String,val cpu:Double?,val gpu:Double?,val memory:Double?,val dedicated:Double?,val shared:Double?)
data class ComputerGpu(val id:String,val name:String,val load:Double?,val used:Double?,val total:Double?,val shared:Double?,val sharedTotal:Double?)
data class ComputerSample(val time:Long,val cpu:Double?,val memory:Double?,val memoryTotal:Double?,val gpus:List<ComputerGpu>,val processes:List<ComputerProcess>,val instance:String,val diskActive:Double?=null,val diskRead:Double?=null,val diskWrite:Double?=null) {
    companion object {
        fun parse(json:JSONObject):ComputerSample {
            require(json.optInt("schema")==1 && json.optLong("time")>0)
            val gpus=json.optJSONArray("gpus")
            val processes=json.optJSONArray("processes")
            fun number(j:JSONObject,key:String):Double? = if(j.isNull(key))null else j.optDouble(key,Double.NaN).takeIf{it.isFinite()&&it>=0}
            fun percent(j:JSONObject,key:String)=number(j,key)?.takeIf{it<=100}
            return ComputerSample(json.getLong("time"),percent(json,"cpu"),number(json,"memoryUsed"),number(json,"memoryTotal"),
                (0 until minOf(gpus?.length()?:0,32)).map {gpus!!.getJSONObject(it)}.map {g->ComputerGpu(g.optString("id"),g.optString("name").take(160),percent(g,"load"),number(g,"dedicatedUsed"),number(g,"dedicatedTotal"),number(g,"sharedUsed"),number(g,"sharedTotal"))},
                (0 until minOf(processes?.length()?:0,1024)).map {processes!!.getJSONObject(it)}.map {p->ComputerProcess(p.optString("identity").take(100),p.optInt("pid"),p.optString("name").take(160),percent(p,"cpu"),percent(p,"gpu"),number(p,"memory"),number(p,"dedicated"),number(p,"shared"))},json.optString("instance"),percent(json,"diskActive"),number(json,"diskRead"),number(json,"diskWrite"))
        }
    }
}
/** Memory-only history, scoped to the connected computer; no polling without a viewer. */
object ComputerPerformance {
    data class State(val samples:List<ComputerSample> = emptyList(),val message:String="正在读取电脑性能…",val received:Long=0) {
        val current get()=samples.lastOrNull()
        val fresh get()=received>0 && SystemClock.elapsedRealtime()-received<12000 && message.isEmpty()
    }
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val owners=mutableSetOf<Any>()
    private val pageOwners=mutableSetOf<Any>()
    private val pageMutable=MutableStateFlow(false)
    val pageVisible=pageMutable.asStateFlow()
    private var job:Job?=null
    private var origin=""
    private val client=OkHttpClient.Builder().dns(DevelopmentDns).followRedirects(false).followSslRedirects(false).connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).build()
    fun retain(context:Context,fullPage:Boolean=false):Any {
        val owner=Any();owners.add(owner);if(fullPage){pageOwners.add(owner);pageMutable.value=true}
        if(job==null) job=scope.launch {poll(context.applicationContext)}
        return owner
    }
    fun release(owner:Any) {owners.remove(owner);pageOwners.remove(owner);pageMutable.value=pageOwners.isNotEmpty();if(owners.isEmpty()){job?.cancel();job=null;mutable.value=mutable.value.copy(message="监控已暂停")}}
    private suspend fun poll(context:Context) {
        var lastProcessesAt=0L
        while(currentCoroutineContext().isActive) {
            val loopStarted=SystemClock.elapsedRealtime()
            val interval=SettingsStore(context).computerMonitorIntervalMs
            val wantsProcesses=loopStarted-lastProcessesAt>=3000 || mutable.value.current==null
            val processMode=if(!wantsProcesses)"0" else if(pageVisible.value)"all" else "top"
            var healthy=false
            val next=ConnectionAddress.origin(SettingsStore(context).currentBaseUrl).orEmpty()
            if(origin!=next){origin=next;mutable.value=State()}
            try {
                check(next.isNotEmpty()) {"请先连接电脑"}
                val cookie=CookieManager.getInstance().getCookie(next+"/remote/api/session/list").orEmpty()
                val result=withContext(Dispatchers.IO) {
                    val payload=JSONObject().put("type","client-request").put("rpcId","pal-performance").put("method","session/list").put("payload",JSONObject().put("args",JSONObject().put("_request",JSONObject())))
                    val call=client.newCall(Request.Builder().url(next+"/remote/api/session/list?pal_performance=1&pal_interval=$interval&pal_processes=$processMode")
                        .header("Cookie",cookie).post(payload.toString().toRequestBody("application/json".toMediaType())).build())
                    try {call.execute().use {r->
                        check(r.isSuccessful) {if(r.code==403||r.code==401)"配对授权不可用，请先恢复电脑连接" else "电脑暂不可达（${r.code}）"}
                        val body=r.body ?: error("电脑未返回监控数据")
                        check(body.contentLength()<=2097152) {"监控响应过大"}
                        val bytes=body.byteStream().use {input->
                            val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                            while(out.size()<=2097152){val count=input.read(chunk,0,minOf(chunk.size,2097153-out.size()));if(count<0)break;out.write(chunk,0,count)}
                            out.toByteArray()
                        }
                        check(bytes.size<=2097152) {"监控响应过大"}
                        val value=JSONObject(String(bytes,Charsets.UTF_8)).optJSONObject("result")?.optJSONObject("value") ?: error("请更新电脑端 Pal 远程访问工具")
                        check(value.optInt("schema")==1) {"请更新电脑端 Pal 远程访问工具"}
                        check(value.optString("status")=="ok") {value.optString("message").ifBlank {if(value.optString("status")=="stale")"电脑采集暂停，等待恢复" else "正在读取电脑性能计数器…"}}
                        val parsed=ComputerSample.parse(value)
                        if(value.has("processes")){lastProcessesAt=loopStarted;parsed} else parsed.copy(processes=mutable.value.current?.processes.orEmpty())
                    }} finally {if(!currentCoroutineContext().isActive)call.cancel()}
                }
                currentCoroutineContext().ensureActive()
                val old=mutable.value.samples.takeIf {it.lastOrNull()?.instance==result.instance}.orEmpty()
                val samples=if(old.lastOrNull()?.time==result.time)old else (old+result).filter{it.time>=result.time-360000}.takeLast(721)
                mutable.value=State(samples,"",SystemClock.elapsedRealtime());healthy=true
            } catch(e:CancellationException){throw e}
              catch(e:Exception){mutable.value=mutable.value.copy(message=if(e is IllegalStateException)e.message?:"电脑数据暂不可用" else "连接中断，正在重试；下方为上次数据")}
            delay(if(healthy)maxOf(50L,interval-(SystemClock.elapsedRealtime()-loopStarted)) else maxOf(2000L,interval.toLong()))
        }
    }
}
