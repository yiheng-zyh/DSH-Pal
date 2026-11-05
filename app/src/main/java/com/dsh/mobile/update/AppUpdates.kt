package com.dsh.mobile.update

import android.content.Context
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.core.*
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*

data class UpdateState(val checking:Boolean=false,val checkedAt:Long=0,val message:String="尚未检查更新",val release:AppRelease?=null,
                       val announcements:List<AppAnnouncement> = emptyList(),val announcementMessage:String="",val downloading:Boolean=false,
                       val downloaded:Long=0,val readyFile:File?=null,val downloadMessage:String="",val automatic:Boolean=true,val previews:Boolean=true,val popups:Boolean=true,val readRevision:Int=0)

/** App-owned update state; no DSH Cookie, pairing credential or VPN setting is involved. */
class AppUpdates private constructor(context:Context) {
    private val app=context.applicationContext
    private val preferences=app.getSharedPreferences("app_updates",Context.MODE_PRIVATE)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(UpdateState(automatic=preferences.getBoolean("automatic",true),previews=preferences.getBoolean("previews",BuildConfig.VERSION_NAME.contains('-')),popups=preferences.getBoolean("popups",true)))
    val state=mutable.asStateFlow()
    private val checking=AtomicBoolean(false)
    private var downloading:Job?=null
    @Volatile private var downloadCall:Call?=null
    private val cancelled=AtomicBoolean(false)
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(5,TimeUnit.MINUTES).build()

    init{
        runCatching { preferences.getString("announcement_cache",null)?.let { json->
            mutable.update{it.copy(announcements=AppAnnouncement.parse(json,BuildConfig.VERSION_CODE.toLong()))}
        }}
    }
    fun configure(automatic:Boolean=state.value.automatic,previews:Boolean=state.value.previews,popups:Boolean=state.value.popups){
        preferences.edit().putBoolean("automatic",automatic).putBoolean("previews",previews).putBoolean("popups",popups).apply()
        mutable.update{it.copy(automatic=automatic,previews=previews,popups=popups)}
    }
    fun check(manual:Boolean=true){
        val now=System.currentTimeMillis()
        if(!manual&&(!state.value.automatic||now-preferences.getLong("last_attempt",0)<86_400_000))return
        if(state.value.downloading||!checking.compareAndSet(false,true))return
        preferences.edit().putLong("last_attempt",now).apply()
        mutable.update{it.copy(checking=true,message="正在检查版本与公告…")}
        scope.launch{
            try{
                var release=state.value.release;var releaseMessage=""
                try{
                    val body=text(AppLinks.RELEASE_API,1024*1024)
                    release=AppRelease.latest(body,BuildConfig.VERSION_NAME,state.value.previews)
                    val candidate=release
                    if(candidate?.apkUrl!=null&&candidate.sha256==null&&candidate.checksumsUrl!=null){
                        val hash=runCatching{AppRelease.checksum(text(candidate.checksumsUrl,32768),URI(candidate.apkUrl).path.substringAfterLast('/'))}.getOrNull()
                        release=candidate.copy(sha256=hash)
                    }
                    releaseMessage=if(release==null)"未发现比当前版本更新的已发布版本"else "发现新版本 ${release.version}"
                }catch(e:Exception){
                    releaseMessage=if(e is RateLimited)"检查过于频繁，请稍后重试或打开发布页"else "暂时无法检查更新，请检查网络后重试"
                }
                var news:List<AppAnnouncement>?=null;var newsMessage=""
                try{
                    val json=text(AppLinks.ANNOUNCEMENTS,128*1024)
                    news=AppAnnouncement.parse(json,BuildConfig.VERSION_CODE.toLong())
                    preferences.edit().putString("announcement_cache",json).apply()
                }catch(e:Exception){newsMessage=if(e is MissingFeed)"公告源尚未发布，保留已缓存公告"else "公告暂时不可用，保留已缓存公告"}
                mutable.update { old->old.copy(checking=false,checkedAt=now,release=release,readyFile=old.readyFile?.takeIf{old.release?.id==release?.id&&old.release?.sha256==release?.sha256},
                    message=releaseMessage,
                    announcements=news?:old.announcements,announcementMessage=newsMessage) }
            }catch(e:Exception){
                mutable.update{it.copy(checking=false,message=if(e is RateLimited)"检查过于频繁，请稍后重试或打开发布页"else "暂时无法检查更新，请检查网络后重试")}
            }finally{checking.set(false)}
        }
    }
    private class MissingFeed:IOException()
    private class RateLimited:IOException()
    private fun allowedRequest(raw:String):Boolean=runCatching{
        val u=URI(raw);u.scheme=="https"&&u.userInfo==null&&u.port==-1&&u.rawFragment==null&&(
            raw==AppLinks.RELEASE_API||raw==AppLinks.ANNOUNCEMENTS||AppLinks.releaseUrl(raw)||
                u.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com","github-releases.githubusercontent.com")
        )
    }.getOrDefault(false)
    private fun open(raw:String,isDownload:Boolean=false):Response {
        var url=raw
        repeat(6){
            kotlin.check(allowedRequest(url)){"更新地址不受支持"}
            val request=Request.Builder().url(url).header("User-Agent","DSH-Pal/${BuildConfig.VERSION_NAME}").header("Accept",if(url==AppLinks.RELEASE_API)"application/vnd.github+json"else "*/*").build()
            val call=client.newCall(request);if(isDownload){downloadCall=call;if(cancelled.get())call.cancel()}
            val response=call.execute()
            if(response.code in setOf(301,302,303,307,308)){
                val location=response.header("Location");val next=location?.let{response.request.url.resolve(it)?.toString()};response.close()
                url=next?:throw IOException("更新地址跳转无效")
            }else return response
        }
        throw IOException("更新地址跳转次数过多")
    }
    private fun text(url:String,limit:Int):String=open(url).use{response->
        if(response.code==404)throw MissingFeed()
        if(response.code in setOf(403,429))throw RateLimited()
        kotlin.check(response.isSuccessful){"更新信息暂不可用"}
        val body=response.body?:throw IOException("更新信息为空")
        kotlin.check(body.contentLength()<=limit){"更新信息过大"}
        body.byteStream().use{input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;kotlin.check(out.size()+n<=limit){"更新信息过大"};out.write(buffer,0,n)}
            out.toString("UTF-8")
        }
    }
    fun download(){
        val release=state.value.release?:return
        if(!release.downloadable||downloading?.isActive==true)return
        cancelled.set(false)
        mutable.update{it.copy(downloading=true,downloaded=0,readyFile=null,downloadMessage="正在下载，离开页面仍可继续；结束 App 会中断")}
        downloading=scope.launch{
            try{
                val directory=File(app.cacheDir,"app-updates")
                val saved=File(directory,"update.apk")
                val file=if(UpdateDownload.matches(saved,release.apkSize,release.sha256!!))saved else{
                    open(release.apkUrl!!,true).use{response->
                        kotlin.check(response.code==200){"下载暂不可用，请稍后重试"}
                        val body=response.body?:throw IOException("下载内容为空")
                        kotlin.check(body.contentLength()==-1L||body.contentLength()==release.apkSize){"安装包大小与发布记录不一致"}
                        var last=0L
                        body.byteStream().use{input->UpdateDownload.receive(input,directory,release.apkSize,release.sha256,{cancelled.get()||!isActive}){bytes->
                            val now=System.currentTimeMillis();if(now-last>=250||bytes==release.apkSize){last=now;mutable.update{it.copy(downloaded=bytes)}}
                        }}
                    }
                }
                if(cancelled.get()||!isActive)throw CancellationException()
                UpdateInstaller.verify(app,file,release)
                mutable.update{it.copy(downloading=false,downloaded=release.apkSize,readyFile=file,downloadMessage="下载完成，校验通过，点击安装更新")}
            }catch(e:Exception){
                val message=if(cancelled.get()||e is CancellationException||e is java.util.concurrent.CancellationException)"下载已取消，可重新下载"else when(e){
                    is IllegalStateException,is IllegalArgumentException->e.message?.take(160)?:"安装包校验失败"
                    else->"下载中断，请检查网络后重试"
                }
                mutable.update{it.copy(downloading=false,readyFile=null,downloadMessage=message)}
            }finally{downloadCall=null}
        }
    }
    fun cancelDownload(){cancelled.set(true);downloadCall?.cancel()}
    fun invalidateDownloaded(message:String){mutable.update{it.copy(readyFile=null,downloadMessage=message)}}
    fun seen(id:String)=preferences.getStringSet("seen",emptySet()).orEmpty().contains(id)
    fun markSeen(id:String){val values=preferences.getStringSet("seen",emptySet()).orEmpty().toMutableSet();values+=id;preferences.edit().putStringSet("seen",values.toList().takeLast(100).toSet()).apply();mutable.update{it.copy(readRevision=it.readRevision+1)}}
    companion object {
        @Volatile private var instance:AppUpdates?=null
        fun get(context:Context):AppUpdates=instance?:synchronized(this){instance?:AppUpdates(context).also{instance=it}}
    }
}
