package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class LogPolicyTest {
    @Test fun utf8RetentionNeverSplitsChineseOrEmoji() {
        val value="开始连接🙂\n".repeat(200)
        for(limit in 0..80) {
            val result=LogPolicy.tailUtf8(value,limit)
            assertTrue(result.toByteArray(Charsets.UTF_8).size<=limit)
            assertTrue(value.endsWith(result))
            assertFalse(result.contains('\uFFFD'))
        }
    }
    @Test fun asciiAndShortLogsAreRetainedExactly() {
        assertEquals("abc",LogPolicy.tailUtf8("abc",10))
        assertEquals("bc",LogPolicy.tailUtf8("abc",2))
        assertEquals("",LogPolicy.tailUtf8("abc",0))
    }
    @Test fun actualTagsReachTheirUserFacingCategory() {
        listOf("VPN","Pair","Connection","QrScan").forEach {assertEquals(LogCategory.CONNECTION,LogCategory.forTag(it))}
        listOf("Network","Probe").forEach {assertEquals(LogCategory.NETWORK,LogCategory.forTag(it))}
        listOf("File","Download","Keyboard","Motion","Reply").forEach {assertEquals(LogCategory.WEB,LogCategory.forTag(it))}
        assertEquals(LogCategory.NOTIFICATIONS,LogCategory.forTag("Service"))
        assertEquals(LogCategory.APP,LogCategory.forTag("Crash"))
    }
}
