package com.dsh.mobile.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleaseTest {
    private fun row(tag:String,id:Int=1,preview:Boolean=true):JSONObject=JSONObject().put("id",id).put("tag_name",tag).put("name",tag)
        .put("prerelease",preview).put("html_url",AppLinks.RELEASES+"/tag/$tag").put("body","更新说明")
        .put("assets",JSONArray().put(JSONObject().put("name","DSH-Pal.apk").put("size",1024)
            .put("browser_download_url",AppLinks.PROJECT+"/releases/download/$tag/DSH-Pal.apk").put("digest","sha256:"+"a".repeat(64))))
    @Test fun numericPreviewVersionsAreComparedNumerically(){
        assertTrue(ReleaseVersion.parse("v0.1.0-alpha.10")!!>ReleaseVersion.parse("0.1.0-alpha.9")!!)
        assertTrue(ReleaseVersion.parse("0.1.0")!!>ReleaseVersion.parse("0.1.0-rc.1")!!)
        assertTrue(ReleaseVersion.parse("0.2.0-alpha.1")!!>ReleaseVersion.parse("0.1.99")!!)
        assertEquals(ReleaseVersion.parse("0.1.0+local"),ReleaseVersion.parse("0.1.0"))
        for(s in listOf("0.01.0","0.1","0.1.0-alpha.01","0.1.0-a..b","latest","0.1.0/evil"))assertNull(s,ReleaseVersion.parse(s))
    }
    @Test fun catalogsNeverOfferInstalledOrOlderVersionAndRespectPreviewSetting(){
        val rows=JSONArray().put(row("v0.1.0-alpha.2")).put(row("v0.1.0-alpha.10",2)).put(row("v0.1.0-alpha.9",3))
        assertEquals("0.1.0-alpha.10",AppRelease.latest(rows.toString(),"0.1.0-alpha.7",true)?.version)
        assertNull(AppRelease.latest(rows.toString(),"0.1.0-alpha.7",false))
        assertNull(AppRelease.latest(rows.toString(),"0.1.0-alpha.10",true))
        assertEquals("0.1.0",AppRelease.latest(JSONArray().put(row("v0.1.0",4,false)).toString(),"0.1.0-alpha.7",false)?.version)
    }
    @Test fun draftsUntrustedOriginsAndAmbiguousApksAreNotDownloaded(){
        assertNull(AppRelease.latest(JSONArray().put(row("v0.2.0").put("draft",true)).toString(),"0.1.0",true))
        assertNull(AppRelease.latest(JSONArray().put(row("v0.2.0").put("html_url","https://evil.test/release")).toString(),"0.1.0",true))
        val a=row("v0.2.0");a.getJSONArray("assets").put(a.getJSONArray("assets").getJSONObject(0))
        assertFalse(AppRelease.latest(JSONArray().put(a).toString(),"0.1.0",true)!!.downloadable)
        for(url in listOf("http://github.com/yiheng-zyh/DSH-Pal/releases/tag/v1","https://github.com.evil.test/yiheng-zyh/DSH-Pal/releases/tag/v1","https://user@github.com/yiheng-zyh/DSH-Pal/releases/tag/v1","https://github.com/yiheng-zyh/DSH-Pal/releases/../evil","https://github.com/yiheng-zyh/DSH-Pal/releases/%2e%2e/evil"))assertFalse(url,AppLinks.releaseUrl(url))
    }
    @Test fun checksumsBindTheExactFilenameAndRejectAmbiguity(){
        val hash="b".repeat(64)
        assertEquals(hash,AppRelease.checksum("$hash  DSH-Pal.apk\n${"c".repeat(64)}  other.apk","DSH-Pal.apk"))
        assertNull(AppRelease.checksum("$hash  ../DSH-Pal.apk","DSH-Pal.apk"))
        assertNull(AppRelease.checksum("$hash  DSH-Pal.apk\n$hash  DSH-Pal.apk","DSH-Pal.apk"))
    }
    @Test fun announcementVersionBoundsAndIdsAreRespected(){
        val a=JSONObject().put("id","notice-1").put("title","公告").put("body","内容").put("minVersionCode",8).put("maxVersionCode",9)
        val root=JSONObject().put("schemaVersion",1).put("announcements",JSONArray().put(a).put(a))
        assertEquals(1,AppAnnouncement.parse(root.toString(),8).size)
        assertTrue(AppAnnouncement.parse(root.toString(),7).isEmpty());assertTrue(AppAnnouncement.parse(root.toString(),10).isEmpty())
        a.put("id","unsafe/id");assertTrue(AppAnnouncement.parse(root.toString(),8).isEmpty())
    }
}
