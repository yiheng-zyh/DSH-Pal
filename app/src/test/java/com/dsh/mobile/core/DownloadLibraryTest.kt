package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Test

class DownloadLibraryTest {
    private val now=1_800_000_000_000L
    private fun file(id:Long,name:String="file",bytes:Long=0,time:Long=now,conversation:String="") = DownloadStore.File(id,"content://test/$id",name,"text/plain",bytes,time,"",conversation,now)
    @Test fun unknownDatesStayLastInBothDirections() {
        val source=listOf(file(1,time=0),file(2,time=now-100),file(3,time=now))
        assertEquals(listOf(3L,2L,1L),result(source).map{it.id})
        assertEquals(listOf(2L,3L,1L),result(source,descending=false).map{it.id})
    }
    @Test fun sizeBoundariesDoNotOverlap() {
        val source=listOf(file(1,bytes=1_048_575),file(2,bytes=1_048_576),file(3,bytes=10_485_760),file(4,bytes=104_857_600))
        DownloadLibrary.Size.entries.drop(1).forEachIndexed { index,size -> assertEquals(listOf((index+1).toLong()),result(source,size=size).map{it.id}) }
    }
    @Test fun unknownTimeDoesNotPretendToBeOld() {
        val source=listOf(file(1,time=0),file(2,time=now-40*86_400_000L),file(3))
        assertEquals(listOf(2L),result(source,age=DownloadLibrary.Age.OLD_MONTH).map{it.id})
        assertEquals(listOf(1L),result(source,age=DownloadLibrary.Age.UNKNOWN).map{it.id})
    }
    @Test fun searchFindsConversationAndCombinesFilters() {
        val source=listOf(file(1,name="REPORT.PDF",conversation="机械",bytes=2_000_000),file(2,conversation="机械",bytes=0))
        assertEquals(listOf(1L),result(source,query="  report  ").map{it.id})
        assertEquals(listOf(1L),result(source,query="机械",size=DownloadLibrary.Size.MEDIUM).map{it.id})
    }
    @Test fun sortsNamesConversationsAndSizes() {
        val source=listOf(file(1,name="z",bytes=10,conversation="B"),file(2,name="A",bytes=5,conversation="A"))
        for(sort in listOf(DownloadLibrary.Sort.NAME,DownloadLibrary.Sort.CONVERSATION,DownloadLibrary.Sort.SIZE)) assertEquals(listOf(2L,1L),result(source,sort=sort,descending=false).map{it.id})
    }
    private fun result(source:List<DownloadStore.File>,query:String="",sort:DownloadLibrary.Sort=DownloadLibrary.Sort.TIME,descending:Boolean=true,age:DownloadLibrary.Age=DownloadLibrary.Age.ALL,size:DownloadLibrary.Size=DownloadLibrary.Size.ALL) = DownloadLibrary.files(source,query,sort,descending,age,size,now)
}
