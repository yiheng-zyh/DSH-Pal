package com.dsh.mobile.core

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fixture(block:(File)->Unit){
        val dir=Files.createTempDirectory("dsh-update-test-").toFile()
        try{block(dir)}finally{
            // Only our isolated known filenames are touched; no recursive delete.
            File(dir,"update.part").delete();File(dir,"update.apk").delete();File(dir,"keep.txt").delete();dir.delete()
        }
    }
    @Test fun validDownloadHasExactBytesAndCanReplacePreviousOwnedArtifact()=fixture{dir->
        val bytes=ByteArray(200000){(it%251).toByte()};val counts=mutableListOf<Long>()
        File(dir,"update.apk").writeText("previous");File(dir,"keep.txt").writeText("untouched")
        val file=UpdateDownload.receive(ByteArrayInputStream(bytes),dir,bytes.size.toLong(),hash(bytes),progress={counts+=it})
        assertArrayEquals(bytes,file.readBytes());assertTrue(UpdateDownload.matches(file,bytes.size.toLong(),hash(bytes)))
        assertEquals(bytes.size.toLong(),counts.last());assertEquals("untouched",File(dir,"keep.txt").readText());assertFalse(File(dir,"update.part").exists())
    }
    @Test fun incompleteOversizeAndBadDigestNeverReplacePreviousFile()=fixture{dir->
        val bytes="fixture".toByteArray();File(dir,"update.apk").writeText("previous")
        for((size,digest)in listOf(bytes.size.toLong()+1 to hash(bytes),bytes.size.toLong()-1 to hash(bytes),bytes.size.toLong() to "a".repeat(64))){
            assertTrue(runCatching{UpdateDownload.receive(ByteArrayInputStream(bytes),dir,size,digest)}.isFailure)
            assertEquals("previous",File(dir,"update.apk").readText());assertFalse(File(dir,"update.part").exists())
        }
    }
    @Test fun cancellationLeavesNoPartialAndRetainsExistingArtifact()=fixture{dir->
        val bytes=ByteArray(200000);File(dir,"update.apk").writeText("previous");var cancelled=false
        assertTrue(runCatching{UpdateDownload.receive(ByteArrayInputStream(bytes),dir,bytes.size.toLong(),hash(bytes),{cancelled},{cancelled=true})}.isFailure)
        assertEquals("previous",File(dir,"update.apk").readText());assertFalse(File(dir,"update.part").exists())
    }
}
