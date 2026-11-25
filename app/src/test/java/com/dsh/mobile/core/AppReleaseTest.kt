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
    @Test fun remoteReleaseNotesOnlyApplyToTheInstalledVersion(){
        // 远端 announcement 常比新包晚一天才推。若只取 body 不看 version，
        // 新装的 0.1.5 会把还在写的 0.1.4 公告当成「本版说明」显示（0.1.5 实测到的错乱）。
        fun feed(v:String,body:String)=JSONObject().put("schemaVersion",1)
            .put("releaseNotes",JSONObject().put("version",v).put("body",body))
            .put("announcements",JSONArray()).toString()
        assertEquals("本版说明",AppAnnouncement.parseReleaseNotes(feed("0.1.5","本版说明"),"0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes(feed("0.1.4","旧版说明"),"0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes(feed("ver","x"),"0.1.5"))
        // 缺 version 字段的老格式同样不收，回退到 APK 内置文本。
        val noVersion=JSONObject().put("schemaVersion",1).put("releaseNotes",JSONObject().put("body","旧版说明")).toString()
        assertNull(AppAnnouncement.parseReleaseNotes(noVersion,"0.1.5"))
        // 其余校验照旧：空正文 / 超长 / 无 releaseNotes / 坏 JSON。
        assertNull(AppAnnouncement.parseReleaseNotes(feed("0.1.5"," "),"0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes(feed("0.1.5","x".repeat(20001)),"0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes(JSONObject().put("schemaVersion",1).toString(),"0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes("not json","0.1.5"))
        assertNull(AppAnnouncement.parseReleaseNotes(JSONObject().put("schemaVersion",2)
            .put("releaseNotes",JSONObject().put("version","0.1.5").put("body","x")).toString(),"0.1.5"))
    }
    @Test fun downloadJsonProvidesLanzouMirrorOnlyWhenNewerAndValid(){
        val sha="d".repeat(64)
        val json=JSONObject().put("version","0.2.0").put("versionCode",11).put("sizeMb",80.8)
            .put("sha256",sha.uppercase()).put("apkName","DSH-Pal-0.2.0.apk")
            .put("lanzou",JSONObject().put("url","https://wwbvv.lanzoul.com/b01i5zkceh").put("password","i1x2"))
        val r=AppRelease.fromDownloadJson(json.toString(),"0.1.1")
        assertNotNull(r);assertEquals("0.2.0",r!!.version);assertTrue(r.hasLanzou);assertFalse(r.downloadable)
        assertEquals(sha,r.sha256);assertEquals("DSH-Pal-0.2.0.apk",r.apkName)
        assertEquals((80.8*1024*1024).toLong(),r.apkSize)
        // 不高于当前版本 → 不提供
        assertNull(AppRelease.fromDownloadJson(json.toString(),"0.2.0"))
        assertNull(AppRelease.fromDownloadJson(json.toString(),"0.3.0"))
        // 缺校验值 / 缺大小 / 坏 JSON → 不提供
        assertNull(AppRelease.fromDownloadJson(JSONObject().put("version","0.2.0").put("sizeMb",80.8).toString(),"0.1.1"))
        assertNull(AppRelease.fromDownloadJson(JSONObject().put("version","0.2.0").put("sha256",sha).toString(),"0.1.1"))
        assertNull(AppRelease.fromDownloadJson("not json","0.1.1"))
    }

    @Test fun midRevisionVersionsSortAboveBaseAndBelowNextPatch() {
        val base = ReleaseVersion.parse("0.1.3")!!
        val v2 = ReleaseVersion.parse("0.1.3v2")!!
        val next = ReleaseVersion.parse("0.1.4")!!
        // 0.1.3 < 0.1.3v2 < 0.1.4
        assertTrue(base < v2)
        assertTrue(v2 < next)
        // 同号不同 revision 可区分
        assertTrue(v2 > base)
        assertEquals(0, v2.compareTo(ReleaseVersion.parse("0.1.3v2")!!))
        // 常规语义化版本不受影响
        assertTrue(ReleaseVersion.parse("0.1.3-alpha.1")!! < base)
        assertTrue(ReleaseVersion.parse("v0.1.3")!!.compareTo(base) == 0)
        // 非法写法仍返回 null
        assertNull(ReleaseVersion.parse("0.1.3v0"))
        assertNull(ReleaseVersion.parse("0.1.3x2"))
        assertNull(ReleaseVersion.parse("0.1"))
    }
}