package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class WebPluginBundlesTest {
    @Test fun mixedBundleIdentifiesSelectableAndProtectedModules() {
        val source = "https://dsh.example/plugins/??@deepseek-ai/dsh-client-ui-chat/client.js," +
            "@example/skin/client.js,@linxin666/dsh-remote-web-ui/client.js,dshmarket/client.js&rev=web:1"
        val modules = requireNotNull(WebPluginBundles.parse(source))
        assertEquals(listOf("@example/skin"), modules.map { it.packageName }.filter(WebPluginBundles::canPause))
        assertTrue(WebPluginBundles.canPause("dsh-plugin-wallpaper-engine-fx"))
    }

    @Test fun ambiguousAndNonAssetRequestsAreNotRewritten() {
        val origin = "https://dsh.example"
        listOf(
            "/plugins/example/status",
            "/plugins/??test/client.js&rev=one&rev=two",
            "/plugins/??test/client.js&rev=one&token=fixture",
            "/plugins/??test/../client.js&rev=one",
            "/plugins/??@test/client.js&rev=one",
            "/api/??test/client.js&rev=one",
        ).forEach { assertNull(WebPluginBundles.parse(origin + it)) }
    }
}
