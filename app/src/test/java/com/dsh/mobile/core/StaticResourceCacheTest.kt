package com.dsh.mobile.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class StaticResourceCacheTest {
    @Test fun explicitClearPreservesUnrelatedFilesAndForcesDownload() {
        val store=cache()
        load(store)
        val unrelated=File(root,"keep.txt").apply {writeText("user data")}
        val nested=File(root,"nested").apply {mkdir()}
        File(nested,"keep.body").writeText("untouched")
        store.clear()
        assertEquals("user data",unrelated.readText())
        assertTrue(File(nested,"keep.body").exists())
        assertEquals("one",load(store))
        assertEquals(2,requests.get())
    }
    @Test fun clearPreventsAnOlderInFlightDownloadFromRepopulatingDisk() {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val delayed=client.newBuilder().addInterceptor {chain ->
            entered.countDown()
            check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
            chain.proceed(chain.request())
        }.build()
        // Application interceptor in the fixture returns directly; prepend the delay.
        val gated=delayed.newBuilder().apply {interceptors().add(0,interceptors().removeAt(interceptors().lastIndex))}.build()
        val store=StaticResourceCache(root,gated)
        val pool=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val future=pool.submit<String?> {load(store)}
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            store.clear();release.countDown()
            assertEquals("one",future.get(5,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {release.countDown();pool.shutdownNow()}
    }
    @get:Rule val temp = TemporaryFolder()
    private lateinit var client: OkHttpClient
    private lateinit var origin: String
    private val requests = AtomicInteger()
    private var version = "one"
    private var control = "public, max-age=3600"
    private var status = 200
    private var omitControlOn304 = false
    private var time = 100_000L
    private lateinit var root: File
    private val events = mutableListOf<String>()
    private fun cache(limit: Long = 96L * 1024 * 1024) = StaticResourceCache(root, client, limitBytes = limit, clock = { time }, log = { events.add(it) })

    @Before fun setUp() {
        root = temp.newFolder("owned")
        client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val code = if (chain.request().header("If-None-Match") == "\"$version\"" && status == 200) 304 else status
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (code != 304 || !omitControlOn304) header("Cache-Control", control) }.header("ETag", "\"$version\"")
                .body((if (code == 304) "" else version).toResponseBody("text/javascript; charset=utf-8".toMediaType())).build()
        }.build()
        origin = "https://desktop.example"
    }
    private fun load(store: StaticResourceCache, path: String = "/assets/index-Dy0OhsZ5.js", nav: Long = 1, headers: Map<String, String> = emptyMap()) =
        store.load(origin + path, headers, nav)?.bytes?.decodeToString()

    @Test fun versionedPluginBatchesAreStaticButArbitraryQueriesAreNot() {
        val path="/plugins/??@example/plugin/client.js,another-plugin/client.js&rev=build-42"
        assertTrue(StaticResourceCache.eligible(origin,origin+path,"GET",false,emptyMap()))
        listOf(path+"&token=secret",path.replace("rev=build-42","rev="),"/plugins/??../api/client.js&rev=42","/api/??example/client.js&rev=42").forEach {
            assertFalse(StaticResourceCache.eligible(origin,origin+it,"GET",false,emptyMap()))
        }
    }
    @Test fun immutablePluginRevisionIsReusedAndAnUpdateGetsFreshBytes() {
        control="public, max-age=31536000, immutable"
        val store=cache();val path="/plugins/??@example/plugin/client.js&rev=build-42"
        assertEquals("one",load(store,path));assertEquals("one",load(store,path,nav=2))
        assertEquals(1,requests.get())
        version="two";assertEquals("two",load(store,path.replace("build-42","build-43"),nav=3))
        assertEquals(2,requests.get())
    }

    @Test fun fingerprintReusedAcrossInstancesAndNavigations() {
        assertEquals("one", load(cache()))
        assertEquals("one", load(cache(), nav = 2))
        assertEquals(1, requests.get())
    }
    @Test fun urlSafeAndBareContentHashesAreReusedAcrossNavigations() {
        val store = cache()
        for (path in listOf("/assets/index-BXV-oxvX.js", "/skin-assets/skin/405917afdb68d725624bbf7e4f1619a35fc4004039b7d553c5528ca5f65308d3.webp")) {
            load(store, path, nav = 1)
            load(store, path, nav = 2)
        }
        assertEquals(2, requests.get())
    }
    @Test fun fixedNameValidatesAndGetsNewVersionOnNextNavigation() {
        val store = cache()
        assertEquals("one", load(store, "/client/plugin.js"))
        version = "two"
        assertEquals("one", load(store, "/client/plugin.js"))
        assertEquals("two", load(store, "/client/plugin.js", nav = 2))
        assertEquals(2, requests.get())
    }
    @Test fun unchangedFixedNameUses304AndKeepsBody() {
        val store = cache()
        load(store, "/client/plugin.js")
        assertEquals("one", load(store, "/client/plugin.js", nav = 2))
        assertTrue(events.any { it.startsWith("validated") })
        assertEquals(2, requests.get())
    }
    @Test fun bare304DoesNotLoseNoCachePolicy() {
        control = "no-cache"
        omitControlOn304 = true
        val store = cache()
        load(store); load(store); load(store)
        assertEquals(3, requests.get())
    }
    @Test fun policyChangedBy304RemainsInEffectOnLaterBare304() {
        val store = cache()
        control = "max-age=1"
        load(store)
        time += 2000
        control = "no-cache"
        load(store)
        omitControlOn304 = true
        load(store); load(store)
        assertEquals(4, requests.get())
    }
    @Test fun noStoreLeavesNoFiles() {
        control = "no-store"
        val store = cache()
        load(store); load(store)
        assertEquals(2, requests.get())
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun requestNoStoreLeavesNoFiles() {
        val store = cache()
        load(store, headers = mapOf("Cache-Control" to "no-store"))
        load(store, headers = mapOf("Cache-Control" to "no-store"))
        assertEquals(2, requests.get())
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun fixedNameThatLooksLikeWordDoesNotBecomeImmutable() {
        val store = cache()
        load(store, "/client/plugin-frontend.js")
        version = "two"
        assertEquals("two", load(store, "/client/plugin-frontend.js", nav = 2))
    }
    @Test fun pluginFilenameStillValidatesOutsideContentAddressedAssetRoutes() {
        val store = cache()
        load(store, "/plugins/Feature-Ab1Cd2Ef.js")
        version = "two"
        assertEquals("two", load(store, "/plugins/Feature-Ab1Cd2Ef.js", nav = 2))
    }
    @Test fun expiredFingerprintRevalidates() {
        control = "max-age=1"
        val store = cache()
        load(store)
        time += 2000
        version = "two"
        assertEquals("two", load(store))
        assertEquals(2, requests.get())
    }
    @Test fun corruptCacheRedownloads() {
        val store = cache()
        load(store)
        root.listFiles()!!.first { it.extension == "body" }.writeText("corrupt")
        assertEquals("one", load(store))
        assertEquals(2, requests.get())
    }
    @Test fun authContextsAreIsolatedWithoutPersistingCredentials() {
        val store = cache()
        load(store, headers = mapOf("Cookie" to "secret-alpha"))
        version = "two"
        assertEquals("two", load(store, headers = mapOf("Cookie" to "secret-beta")))
        assertEquals(2, requests.get())
        assertTrue(root.listFiles()!!.none { it.readText().contains("secret-") })
    }
    @Test fun failureNeverReturnsStaleExecutable() {
        control = "max-age=0"
        val store = cache()
        load(store)
        status = 403
        assertNull(load(store))
        assertTrue(root.listFiles()!!.none { it.extension == "body" })
    }
    @Test fun evictionOnlyTouchesOwnedFiles() {
        val sentinel = File(root, "user-sentinel.txt").apply { writeText("keep") }
        load(cache(limit = 1))
        assertEquals("keep", sentinel.readText())
        assertTrue(root.listFiles()!!.none { it.extension == "body" })
    }
    @Test fun changedFingerprintDownloadsNewAsset() {
        val store = cache()
        load(store)
        version = "two"
        assertEquals("two", load(store, "/assets/index-Ab1Cd2Ef.js"))
        assertEquals(2, requests.get())
    }
    @Test fun onlyStaticSameOriginGetWithoutSecretsIsEligible() {
        fun accepts(path: String, method: String = "GET", main: Boolean = false, headers: Map<String, String> = emptyMap()) =
            StaticResourceCache.eligible(origin, origin + path, method, main, headers)
        assertTrue(accepts("/assets/index-Dy0OhsZ5.js"))
        assertTrue(accepts("/client/plugin.js?v=2"))
        for (path in listOf("/", "/index.html", "/api/task.js", "/remote/api/task.js", "/pair-app.js", "/assets/a.js?token=secret", "/assets/a.js?file=private", "/attachments/a.svg", "/assets/%2e%2e/a.js")) assertFalse(path, accepts(path))
        assertFalse(accepts("/assets/a.js", "POST"))
        assertFalse(accepts("/assets/a.js", main = true))
        assertFalse(accepts("/assets/a.js", headers = mapOf("Range" to "bytes=0-10")))
        assertFalse(StaticResourceCache.eligible(origin, "https://example.org/assets/a.js", "GET", false, emptyMap()))
    }
}
