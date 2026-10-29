package com.dsh.mobile.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.dsh.mobile.core.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

enum class VpnProvider(val title:String) { TAILSCALE("Tailscale"), WIREGUARD("WireGuard") }
data class VpnState(val active:VpnProvider?=null,val up:Boolean=false,val busy:Boolean=false,val tailscaleStatus:String="尚未启动",val message:String="未启用内置通道",val authUrl:String="",val authAttempt:Long=0,val configured:Boolean=false,val scanning:Boolean=false,val scanned:Int=0,val total:Int=0,val found:List<DiscoveredServer> = emptyList(),val rx:Long=0,val tx:Long=0,val handshakeAt:Long=0)
object VpnController {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val mutable=MutableStateFlow(VpnState())
    val state=mutable.asStateFlow()
    private lateinit var app:Context
    private val prefs get()=app.getSharedPreferences("mesh_settings",Context.MODE_PRIVATE)
    private var backend:GoBackend?=null
    private var scanJob:Job?=null
    private var epoch=0
    private var initialized=false
    private val tailscaleService = MutableStateFlow<String?>(null)
    private val wireguardService = MutableStateFlow<String?>(null)
    private var status=JSONObject()
    private var lastRestoreAt=0L
    private val session=VpnSessionGate()
    private val authAttempts=java.util.concurrent.atomic.AtomicLong()
    fun isSessionActive()=session.ticket()!=null
    fun onTaskOpened(context:Context) { initialize(context);session.enter() }
    fun onTaskRemoved() {
        session.leave()
        // Persist before scheduling cleanup: a queued restore must not win the race.
        prefs.edit().putBoolean("wanted",false).commit()
        scanJob?.cancel()
        RunLog.info("VPN","task removed; automatic VPN recovery disabled")
        scope.launch {mutex.withLock {
            if(!isSessionActive())runCatching {stopInternal()}
                .onFailure {RunLog.warn("VPN","task cleanup failed ${it.javaClass.simpleName}")}
        }}
    }
    fun onVpnRevoked() {prefs.edit().putBoolean("wanted",false).commit()}
    private val tunnel=object:Tunnel {
        override fun getName()="dsh-mobile"
        override fun onStateChange(value:Tunnel.State){if(mutable.value.active==VpnProvider.WIREGUARD)mutable.update{it.copy(up=value==Tunnel.State.UP)}}
    }
    fun initialize(context:Context) {
        synchronized(this){if(initialized)return;app=context.applicationContext;initialized=true}
        mutable.update { it.copy(configured=runCatching{TunnelStore(app,"wireguard").decryptFromPref("configuration").isNotEmpty()}.getOrDefault(false)) }
        scope.launch { while(isActive){
            val token=epoch
            runCatching {
                when(mutable.value.active) {
                    VpnProvider.TAILSCALE -> {
                        val current=TailscaleEngine.get(app).status()
                        if(token==epoch) {
                            status=current
                            val engine=TailscaleEngine.get(app)
                            mutable.update { it.copy(tailscaleStatus=current.optString("BackendState","Unknown"),authUrl=if(current.optString("BackendState")=="Running") "" else if(it.busy)it.authUrl else TailscaleEngine.authorizationUrl(engine.browseUrl.ifBlank{current.optString("AuthURL")})) }
                            if(mutable.value.up&&autoDiscover&&scanJob==null)discover()
                        }
                    }
                    VpnProvider.WIREGUARD -> backend?.let { b ->
                        val stats=b.getStatistics(tunnel)
                        if(token==epoch)mutable.update { it.copy(rx=stats.totalRx(),tx=stats.totalTx(),handshakeAt=stats.peers().map { key -> stats.peer(key)?.latestHandshakeEpochMillis()?:0 }.maxOrNull()?:0) }
                    }
                    null -> Unit
                }
            }.onFailure { if(token==epoch)RunLog.warn("VPN","status unavailable ${it.javaClass.simpleName}") }
            val now=android.os.SystemClock.elapsedRealtime()
            if(isSessionActive()&&prefs.getBoolean("wanted",false)&&mutable.value.active==null&&!mutable.value.busy&&now-lastRestoreAt>=15_000) {
                lastRestoreAt=now;restore(app)
            }
            delay(3_000)
        } }
    }
    var preferred:VpnProvider
        get()=runCatching{VpnProvider.valueOf(prefs.getString("provider","TAILSCALE")!!)}.getOrDefault(VpnProvider.TAILSCALE)
        set(value){prefs.edit().putString("provider",value.name).apply()}
    var autoDiscover:Boolean
        get()=prefs.getBoolean("auto_discover",true)
        set(value){prefs.edit().putBoolean("auto_discover",value).apply();if(!value)scanJob?.cancel() else if(mutable.value.up)discover()}
    fun reportError(message:String){mutable.update {it.copy(busy=false,message=message)};RunLog.warn("VPN",message)}
    fun inspectTailscale(){scope.launch { mutex.withLock { action("读取登录状态"){
        val engine=TailscaleEngine.get(app);engine.ensureEngine();if(mutable.value.active!=VpnProvider.TAILSCALE)engine.setWantRunning(false)
        val current=engine.status();status=current;mutable.update{it.copy(tailscaleStatus=current.optString("BackendState","Unknown"),message="登录状态已更新")}
    } } } }
    fun connect(provider:VpnProvider,userInitiated:Boolean=true){
        val ticket=session.ticket() ?: return
        scope.launch { mutex.withLock {
            if(session.permits(ticket))action("启动 ${provider.title}"){ startInternal(provider,userInitiated) }
        } }
    }
    private suspend fun startInternal(provider:VpnProvider,userInitiated:Boolean) {
        val sessionTicket=checkNotNull(session.ticket()) {"App task was closed"}
        require(VpnService.prepare(app)==null){"VPN permission required"}
        if(mutable.value.active==provider&&mutable.value.up) {
            prefs.edit().putBoolean("wanted",true).apply();preferred=provider
            RunLog.info("VPN","reuse running provider=$provider; no restart")
            return
        }
        val wgConfig=if(provider==VpnProvider.WIREGUARD) {
            val raw=TunnelStore(app,"wireguard").decryptFromPref("configuration")
            require(raw.isNotBlank()){ "WireGuard configuration required" };appOnlyConfig(raw,app.packageName)
        } else null
        if(provider==VpnProvider.TAILSCALE)TailscaleEngine.get(app).ensureEngine()
        stopInternal();preferred=provider;epoch++
        check(session.permits(sessionTicket)) {"App task was closed"}
        mutable.update {it.copy(active=provider,up=false,authUrl="",rx=0,tx=0,handshakeAt=0,message="正在启动 ${provider.title}")}
        prefs.edit().putBoolean("wanted",true).apply()
        try { when(provider){
            VpnProvider.TAILSCALE -> {
                val engine=TailscaleEngine.get(app);engine.ensureEngine()
                engine.setWantRunning(true)
                app.startForegroundService(Intent(app,TailscaleVpnService::class.java).setAction(TailscaleVpnService.ACTION_START))
                var current=engine.status()
                // Native profile restoration is asynchronous. Do not open another
                // login page just because the cold backend briefly says NeedsLogin.
                var settling=0
                while(current.optString("BackendState") in setOf("NeedsLogin","NoState") && settling++<6) {
                    delay(500);current=engine.status()
                }
                status=current
                if(userInitiated && current.optString("BackendState") in setOf("NeedsLogin","NoState"))engine.login()
                mutable.update {it.copy(tailscaleStatus=current.optString("BackendState","Unknown"),message="正在连接；需要登录时将打开官方授权页")}
            }
            VpnProvider.WIREGUARD -> {
                val config=wgConfig!!
                app.startForegroundService(Intent(app,WireGuardKeepaliveService::class.java))
                val b=backend?:GoBackend(app).also {backend=it}
                b.setState(tunnel,Tunnel.State.UP,config)
                check(session.permits(sessionTicket)) {"App task was closed"}
                mutable.update {it.copy(up=true,message="通道已启用；目标可达性需检测")}
            }
        }
            check(session.permits(sessionTicket)) {"App task was closed"}
        } catch(error:Throwable){stopInternal();prefs.edit().putBoolean("wanted",false).apply();throw error}
    }
    private suspend fun action(label:String,block:suspend()->Unit){
        mutable.update{it.copy(busy=true,message=label)}
        runCatching {block()}.onFailure {if(it is CancellationException)throw it;RunLog.error("VPN","$label failed ${it.javaClass.simpleName}");mutable.update {s->s.copy(message="$label 失败（${it.javaClass.simpleName}），请检查配置与授权")}}
        mutable.update{it.copy(busy=false)}
    }
    fun login():Long? {
        val ticket=session.ticket() ?: return null
        val attempt=authAttempts.incrementAndGet()
        scope.launch {mutex.withLock {
        if(!session.permits(ticket)||authAttempts.get()!=attempt)return@withLock
        action("登录 Tailscale"){
        if(mutable.value.active==VpnProvider.WIREGUARD)stopInternal()
        val engine=TailscaleEngine.get(app);engine.ensureEngine()
        epoch++;scanJob?.cancel();scanJob=null
        mutable.update{it.copy(active=VpnProvider.TAILSCALE,authUrl="",authAttempt=attempt,found=emptyList(),scanning=false)}
        app.startForegroundService(Intent(app,TailscaleVpnService::class.java).setAction(TailscaleVpnService.ACTION_START_FOREGROUND_ONLY))
        engine.login()
    }}}
        return attempt
    }
    fun disconnect(){scope.launch{mutex.withLock{stopInternal();prefs.edit().putBoolean("wanted",false).apply()}}}
    private suspend fun stopInternal(){
        val previous=mutable.value.active
        epoch++;scanJob?.cancel();scanJob=null
        mutable.update {it.copy(active=null,up=false,scanning=false,authUrl="",message="内置通道已断开")}
        if(previous==VpnProvider.TAILSCALE || tailscaleService.value!=null){TailscaleEngine.get(app).setWantRunning(false);app.stopService(Intent(app,TailscaleVpnService::class.java))}
        if(previous==VpnProvider.WIREGUARD || wireguardService.value!=null){runCatching{backend?.setState(tunnel,Tunnel.State.DOWN,null)};app.stopService(Intent(app,WireGuardKeepaliveService::class.java))}
        // stopService is asynchronous. Do not let a new route race the old
        // service's onDestroy, DNS teardown or a late status callback.
        withTimeout(8_000) { tailscaleService.first { it == null } }
        withTimeout(8_000) { wireguardService.first { it == null } }
        RunLog.info("Connection","previous VPN service stopped; safe to prepare next route")
    }
    fun logout(){
        authAttempts.incrementAndGet()
        scope.launch{mutex.withLock{action("退出 Tailscale"){
        if(mutable.value.active==VpnProvider.TAILSCALE)stopInternal()
        TailscaleEngine.get(app).logout();prefs.edit().putBoolean("wanted",false).apply()
        mutable.update{it.copy(tailscaleStatus="NeedsLogin",authUrl="",authAttempt=authAttempts.get(),found=emptyList(),message="已退出 Tailscale；点击“登录 / 重新打开授权页”重新登录")}
    }}}}
    fun importWireGuard(raw:String){scope.launch{mutex.withLock{action("导入 WireGuard 配置"){
        require(raw.toByteArray().size<=65536)
        val config=appOnlyConfig(raw,app.packageName)
        require(config.peers.isNotEmpty())
        TunnelStore(app,"wireguard").encryptToPref("configuration",raw)
        mutable.update{it.copy(configured=true,message="配置已加密保存（${config.peers.size} 个 Peer）；连接时仅作用于本 App")}
    }}}}
    fun discover(){
        scanJob?.cancel()
        val token=epoch
        scanJob=scope.launch {
            mutable.update{it.copy(scanning=true,scanned=0,total=0,message="正在检测已授权设备上的 DSH 入口")}
            try {
                val candidates=if(mutable.value.active==VpnProvider.TAILSCALE&&mutable.value.up)MeshDiscovery.candidates(status) else SettingsStore(app).profiles.filter{it.kind==ConnectionProfile.Kind.MESH}.map{DiscoveredServer(it.label,it.baseUrl)}
                val found=withTimeout(30_000){MeshDiscovery.find(candidates){done,total->if(token==epoch)mutable.update {it.copy(scanned=done,total=total)}}}
                if(token==epoch)mutable.update{it.copy(found=found,scanning=false,message=if(found.isEmpty())"尚未发现 DSH；请检查电脑端入口或手动填写地址" else "发现 ${found.size} 个入口；首次连接仍需 DSH 配对")}
            } catch(error:TimeoutCancellationException){if(token==epoch)mutable.update{it.copy(scanning=false,message="目标检测超时，保留旧连接，可重新检测")}}
            catch(error:CancellationException){if(token==epoch)mutable.update{it.copy(scanning=false)};throw error}
            catch(error:Exception){if(token==epoch)reportError("目标检测失败（${error.javaClass.simpleName}）")}
        }
    }
    fun restore(context:Context){
        initialize(context)
        val ticket=session.ticket() ?: return
        scope.launch {mutex.withLock {
            // Recheck under the same lock as an explicit disconnect; a queued
            // restore must not undo the user's Stop action.
            if(session.permits(ticket)&&prefs.getBoolean("wanted",false)&&mutable.value.active==null) {
                // A saved public/USB/LAN selection does not need an embedded
                // VPN. Ignore legacy wanted=true instead of reviving it on launch.
                if(SettingsStore(app).activeProfile?.kind!=ConnectionProfile.Kind.MESH) {
                    prefs.edit().putBoolean("wanted",false).apply()
                    return@withLock
                }
                if(VpnService.prepare(app)==null)action("恢复已开启的通道"){startInternal(preferred,false)}
                else reportError("当前停在：系统 VPN 授权，请在连接管理中重新授权")
            }
        }}
    }
    suspend fun prepareNavigation(profile:ConnectionProfile,progress:(String)->Unit={}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(isSessionActive()) {"App task was closed"}
            val tailscale = java.net.URI(profile.baseUrl).host.endsWith(".ts.net")
            val savedProvider=runCatching { VpnProvider.valueOf(prefs.getString("route_provider_${profile.id}","")!!) }.getOrNull()
            val explicitProvider=profile.meshProvider?.takeUnless {it==ConnectionProfile.MeshProvider.EXTERNAL}?.let {VpnProvider.valueOf(it.name)}
            val requestedProvider=if(profile.meshProvider==ConnectionProfile.MeshProvider.EXTERNAL)null else explicitProvider ?: if(tailscale) VpnProvider.TAILSCALE else savedProvider
                ?: preferred.takeIf { prefs.getBoolean("wanted",false) || mutable.value.active!=null }
            if(profile.kind!=ConnectionProfile.Kind.MESH) {
                // Probe the selected route without an unrelated VPN. The
                // ConnectionSwitch rollback prepares the previous mesh profile
                // again if probing or committing the new route fails.
                prefs.edit().putBoolean("wanted",false).apply()
                if(mutable.value.active!=null || tailscaleService.value!=null || wireguardService.value!=null) {
                    progress("${profile.kind.title} 无需内置 VPN，正在关闭通道；账号和配置会保留")
                    stopInternal()
                    RunLog.info("Connection","embedded VPN stopped for non-mesh route kind=${profile.kind}; recovery disabled")
                }
            } else if(requestedProvider!=null && mutable.value.active!=requestedProvider) {
                progress("正在开启 ${requestedProvider.title}；已登录的账号会直接复用，不用重新登录")
                startInternal(requestedProvider,false)
            }
            HealthProbe.resetConnections()
            if(profile.kind==ConnectionProfile.Kind.MESH && mutable.value.active!=null) {
                RunLog.info("Connection","waiting for requested VPN before first page navigation")
                progress("正在等 ${requestedProvider?.title ?: "VPN"} 连上网关；正常几秒内完成")
                withTimeout(30_000) {
                    state.first {it.up}
                    progress("VPN 已连上，正在测试电脑上的 DSH 是否响应")
                    while(true) {
                        val reachable=HealthProbe.check(profile.baseUrl).reachable
                        val handshakeReady=mutable.value.active!=VpnProvider.WIREGUARD ||
                            WireGuardReadiness.hasRecentHandshake(mutable.value.handshakeAt,System.currentTimeMillis())
                        if(reachable&&handshakeReady)break
                        progress(if(!handshakeReady)
                            "已开启 WireGuard，但电脑没回包。请确认电脑端 WireGuard 在线、配置未过期"
                        else
                            "VPN 通了，但电脑上的 DSH 没响应。请确认电脑端 DSH 正在运行、Serve 指向当前端口")
                        delay(500)
                    }
                }
                mutable.value.active?.let { prefs.edit().putString("route_provider_${profile.id}",it.name).apply() }
            }
            RunLog.info("Connection","transport prepared kind=${profile.kind} id=${profile.id.take(8)}")
            progress("通道已打通，正在打开 DSH 页面")
        }
    }
    suspend fun awaitRequestedTunnel(settings:SettingsStore) {
        settings.activeProfile?.let { prepareNavigation(it) }
    }
    fun onTailscaleServiceStarted(id:String){ tailscaleService.value=id }
    fun onTailscaleVpnStatus(up:Boolean){if(mutable.value.active==VpnProvider.TAILSCALE)mutable.update{it.copy(up=up,message=if(up)"Tailscale 通道已连接" else "Tailscale 通道正在恢复")}}
    fun onTailscaleServiceStopped(id:String){if(tailscaleService.value!=id)return;tailscaleService.value=null;if(mutable.value.active==VpnProvider.TAILSCALE)mutable.update{it.copy(active=null,up=false,message="Tailscale 服务已停止或授权被撤销")}}
    fun onWireGuardKeepaliveStarted(id:String){wireguardService.value=id}
    fun onWireGuardKeepaliveStopped(id:String){
        if(wireguardService.value!=id)return
        wireguardService.value=null
        if(mutable.value.active==VpnProvider.WIREGUARD) {
            val token=epoch
            mutable.update {it.copy(up=false)}
            scope.launch {mutex.withLock {if(token==epoch&&mutable.value.active==VpnProvider.WIREGUARD){stopInternal();prefs.edit().putBoolean("wanted",false).apply()}}}
        }
    }
    fun appOnlyConfig(raw:String,packageName:String):Config {
        val parsed=Config.parse(raw.byteInputStream(Charsets.UTF_8));val source=parsed.`interface`
        val builder=com.wireguard.config.Interface.Builder().setKeyPair(source.keyPair).addAddresses(source.addresses)
            .addDnsServers(source.dnsServers).addDnsSearchDomains(source.dnsSearchDomains).includeApplication(packageName)
        source.listenPort.ifPresent {builder.setListenPort(it)};source.mtu.ifPresent{builder.setMtu(it)}
        return Config.Builder().setInterface(builder.build()).addPeers(parsed.peers).build()
    }
}
