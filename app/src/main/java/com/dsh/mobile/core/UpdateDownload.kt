package com.dsh.mobile.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object UpdateDownload {
    /** Write only our two owned cache files. Failed/cancelled downloads cannot replace a verified APK. */
    fun receive(input:InputStream,directory:File,size:Long,sha256:String,cancelled:()->Boolean={false},progress:(Long)->Unit={}):File {
        require(size in 1..262144000&&sha256.matches(Regex("[a-fA-F0-9]{64}")))
        require(directory.isDirectory||directory.mkdirs())
        val partial=File(directory,"update.part");val target=File(directory,"update.apk")
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        try{
            partial.outputStream().use { out->
                val buffer=ByteArray(65536)
                while(true){
                    if(cancelled())throw java.util.concurrent.CancellationException("已取消下载")
                    val n=input.read(buffer);if(n<0)break;if(n==0)continue
                    count+=n;check(count<=size){"安装包大小与发布记录不一致"}
                    digest.update(buffer,0,n);out.write(buffer,0,n);progress(count)
                }
                out.fd.sync()
            }
            check(count==size){"安装包未下载完整，请重试"}
            check(digest.digest().joinToString(""){"%02x".format(it)}.equals(sha256,true)){"安装包校验不一致，未进入安装"}
            java.nio.file.Files.move(partial.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return target
        }finally{if(partial.exists())partial.delete()}
    }
    fun matches(file:File,size:Long,sha256:String):Boolean=runCatching {
        file.isFile&&file.length()==size&&file.inputStream().use { stream->
            val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
            digest.digest().joinToString(""){"%02x".format(it)}.equals(sha256,true)
        }
    }.getOrDefault(false)
}
