package com.dsh.mobile.vpn

import com.dsh.mobile.core.ConnectionAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class DiscoveredServer(val name:String,val origin:String)
object MeshDiscovery {
    private val client=OkHttpClient.Builder().followRedirects(false).connectTimeout(2,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(4,TimeUnit.SECONDS).build()
    fun candidates(status:JSONObject):List<DiscoveredServer> {
        val peers=status.optJSONObject("Peer")?:return emptyList()
        val result=mutableListOf<DiscoveredServer>()
        peers.keys().asSequence().take(12).forEach { id ->
            val peer=peers.optJSONObject(id)?:return@forEach
            if(!peer.optBoolean("Online"))return@forEach
            val name=peer.optString("HostName").ifBlank { "Tailscale 设备" }.take(80)
            val dns=peer.optString("DNSName").trimEnd('.')
            if(dns.endsWith(".ts.net"))result+=DiscoveredServer(name,"https://$dns")
            val ips=peer.optJSONArray("TailscaleIPs")
            for(i in 0 until (ips?.length()?:0)) {
                val host=ips!!.optString(i)
                if(!ConnectionAddress.isCgnatOrPrivate(host))continue
                val authority=if(host.contains(':')) "[$host]" else host
                listOf(8123,8124,4505,3080).forEach { port -> result+=DiscoveredServer(name,"http://$authority:$port") }
            }
        }
        return result.distinctBy { it.origin }.take(60)
    }
    suspend fun find(candidates:List<DiscoveredServer>,progress:(Int,Int)->Unit):List<DiscoveredServer> = coroutineScope {
        val semaphore=Semaphore(4);var finished=0
        candidates.map { target -> async(Dispatchers.IO) { semaphore.withPermit {
            ensureActive()
            val found=runCatching {
                // An unpaired DSH root normally returns 401, with no product name.
                // Fingerprint the pairing protocol first; it grants no credentials.
                val pairedEndpoint=client.newCall(Request.Builder().url(target.origin+"/api/pair/status").get().build()).execute().use { response ->
                    val value=runCatching{JSONObject(response.peekBody(8192).string())}.getOrNull()
                    response.code==200 && value?.optBoolean("ok")==true && value.has("requirePairingForLan") && value.has("phase")
                }
                pairedEndpoint || client.newCall(Request.Builder().url(target.origin+"/").get().build()).execute().use { response ->
                    val body=response.peekBody(16384).string()
                    body.contains("DeepSeek Harness",true)||body.contains("dsh-client",true)||response.headers.values("Set-Cookie").any { it.startsWith("dsh-auth=") }
                }
            }.getOrDefault(false)
            ensureActive();synchronized(semaphore){finished++;progress(finished,candidates.size)}
            target.takeIf { found }
        } } }.awaitAll().filterNotNull()
    }
}
