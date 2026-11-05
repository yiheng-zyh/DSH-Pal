package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class BrowserResourceSummaryTest {
    @Test fun initialLoadingScreenKeepsResourceCountsVisible() {
        assertEquals("已完成 0 项 · 正在加载 0 项",BusinessHealth().resourceDetail())
    }
    @Test fun completedBytesAreNotTurnedIntoAnInventedTotalOrPercentage() {
        val summary=BusinessHealth(loadingResources=2,completedResources=7,resourceBytes=1048576,oldestResourceMs=12000).resourceDetail()
        assertTrue(summary.contains("已完成 7 项"));assertTrue(summary.contains("正在加载 2 项"))
        assertTrue(summary.contains("1.0 MB"));assertTrue(summary.contains("最长等待 12 秒"))
        assertFalse(summary.contains("%"));assertFalse(summary.contains("/"))
    }
    @Test fun failuresRemainVisibleAndUnknownByteVolumeIsOmitted() {
        val summary=BusinessHealth(completedResources=1,resourceFailures=2).resourceDetail()
        assertTrue(summary.contains("失败 2 项"));assertFalse(summary.contains("已加载"))
    }
}
