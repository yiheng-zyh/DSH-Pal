package com.dsh.mobile.core

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object AppLinks {
    const val PROJECT="https://github.com/yiheng-zyh/DSH-Pal"
    const val RELEASES="$PROJECT/releases"
    const val RELEASE_API="https://api.github.com/repos/yiheng-zyh/DSH-Pal/releases?per_page=20"
    const val ANNOUNCEMENTS="https://raw.githubusercontent.com/yiheng-zyh/DSH-Pal/main/updates/announcements.json"
    const val BILIBILI="https://space.bilibili.com/1834666481"
    fun releaseUrl(raw:String):Boolean=runCatching {
        val u=URI(raw)
        u.scheme=="https"&&u.host=="github.com"&&u.userInfo==null&&u.port==-1&&u.rawQuery==null&&u.rawFragment==null&&
            u.rawPath.startsWith("/yiheng-zyh/DSH-Pal/releases/")&&!u.rawPath.contains("..")&&!u.rawPath.contains('%')
    }.getOrDefault(false)
}

data class ReleaseVersion(val numbers:List<Long>,val pre:List<String>):Comparable<ReleaseVersion> {
    override fun compareTo(other:ReleaseVersion):Int {
        numbers.indices.forEach { i->val c=numbers[i].compareTo(other.numbers[i]);if(c!=0)return c }
        if(pre.isEmpty()||other.pre.isEmpty())return when{pre.isEmpty()&&other.pre.isEmpty()->0;pre.isEmpty()->1;else->-1}
        for(i in 0 until minOf(pre.size,other.pre.size)){
            val a=pre[i];val b=other.pre[i];val na=a.toLongOrNull();val nb=b.toLongOrNull()
            val c=when{na!=null&&nb!=null->na.compareTo(nb);na!=null->-1;nb!=null->1;else->a.compareTo(b)}
            if(c!=0)return c
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(raw:String):ReleaseVersion?=runCatching {
            require(raw.length<=128)
            val m=Regex("^v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(raw)?:return null
            val pre=m.groupValues[4].takeIf{it.isNotBlank()}?.split('.')?:emptyList()
            require(pre.all{it.isNotEmpty()&&!(it.all(Char::isDigit)&&it.length>1&&it.startsWith('0'))&&(!it.all(Char::isDigit)||it.length<=9)})
            ReleaseVersion((1..3).map{m.groupValues[it].toLong()},pre)
        }.getOrNull()
    }
}
data class AppRelease(val id:String,val version:String,val title:String,val notes:String,val pageUrl:String,
                      val apkUrl:String?,val apkSize:Long,val sha256:String?,val checksumsUrl:String?,val publishedAt:String) {
    val downloadable get()=apkUrl!=null&&sha256!=null
    companion object {
        fun latest(json:String,current:String,includePreview:Boolean):AppRelease? {
            val installed=ReleaseVersion.parse(current)?:error("当前版本号无法识别")
            val rows=JSONArray(json);val candidates=mutableListOf<Pair<ReleaseVersion,AppRelease>>()
            for(i in 0 until minOf(rows.length(),100)){
                val row=rows.optJSONObject(i)?:continue
                if(row.optBoolean("draft"))continue
                val tag=row.optString("tag_name");val v=ReleaseVersion.parse(tag)?:continue
                if(v<=installed||(!includePreview&&(row.optBoolean("prerelease")||v.pre.isNotEmpty())))continue
                val page=row.optString("html_url");if(!AppLinks.releaseUrl(page))continue
                val assets=row.optJSONArray("assets")?:JSONArray();val apks=mutableListOf<JSONObject>();var checksums:String?=null
                for(j in 0 until minOf(assets.length(),100)){
                    val a=assets.optJSONObject(j)?:continue;val name=a.optString("name");val url=a.optString("browser_download_url")
                    if(!AppLinks.releaseUrl(url)||!url.startsWith(AppLinks.PROJECT+"/releases/download/"))continue
                    if(name.endsWith(".apk",true)&&!name.contains("debug",true)&&!name.contains("usb",true)&&a.optLong("size") in 1..262144000)apks+=a
                    if(name in setOf("SHA256SUMS.txt","SHA256SUMS"))checksums=url
                }
                val apk=apks.singleOrNull();val digest=apk?.optString("digest")?.takeIf{it.matches(Regex("sha256:[a-fA-F0-9]{64}"))}?.substringAfter(':')?.lowercase()
                candidates+=v to AppRelease(row.optLong("id").toString(),tag.removePrefix("v"),row.optString("name").ifBlank{tag}.take(160),row.optString("body").take(16000),page,
                    apk?.optString("browser_download_url"),apk?.optLong("size")?:0,digest,checksums,row.optString("published_at").take(40))
            }
            return candidates.maxByOrNull{it.first}?.second
        }
        fun checksum(text:String,assetName:String):String?=text.lineSequence().mapNotNull { line->
            val match=Regex("^([a-fA-F0-9]{64})[ \\t]+\\*?(.+)$").matchEntire(line.trim())?:return@mapNotNull null
            if(match.groupValues[2]==assetName)match.groupValues[1].lowercase()else null
        }.toList().singleOrNull()
    }
}
data class AppAnnouncement(val id:String,val title:String,val body:String,val publishedAt:String) {
    companion object {
        fun parse(json:String,versionCode:Long):List<AppAnnouncement> {
            val root=JSONObject(json);require(root.optInt("schemaVersion")==1){"公告格式暂不支持"}
            val a=root.getJSONArray("announcements");val result=mutableListOf<AppAnnouncement>()
            for(i in 0 until minOf(a.length(),50)){
                val row=a.optJSONObject(i)?:continue
                val id=row.optString("id");val title=row.optString("title");val body=row.optString("body")
                if(!id.matches(Regex("[A-Za-z0-9._-]{1,80}"))||title.isBlank()||title.length>160||body.isBlank()||body.length>12000)continue
                if(versionCode<row.optLong("minVersionCode",0)||versionCode>row.optLong("maxVersionCode",Long.MAX_VALUE))continue
                result+=AppAnnouncement(id,title,body,row.optString("publishedAt").take(40))
            }
            return result.distinctBy{it.id}
        }
    }
}
