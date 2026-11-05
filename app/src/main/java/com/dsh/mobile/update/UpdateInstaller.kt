package com.dsh.mobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.dsh.mobile.core.AppRelease
import com.dsh.mobile.core.UpdateDownload
import java.io.File

object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun verify(context:Context,file:File,release:AppRelease){
        check(UpdateDownload.matches(file,release.apkSize,release.sha256.orEmpty())){"安装包校验失败，请重新下载"}
        val flags=if(android.os.Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val current=context.packageManager.getPackageInfo(context.packageName,flags)
        val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags)?:error("无法识别安装包")
        check(archive.packageName==context.packageName){"安装包不属于当前应用；测试版与正式版不能混装更新"}
        val installedCode=if(android.os.Build.VERSION.SDK_INT>=28)current.longVersionCode else current.versionCode.toLong()
        val code=if(android.os.Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
        check(code>installedCode){"安装包版本不高于当前版本，已阻止降级"}
        check(archive.versionName==release.version){"安装包版本与发布信息不一致"}
        val own=if(android.os.Build.VERSION.SDK_INT>=28)current.signingInfo?.apkContentsSigners else current.signatures
        val other=if(android.os.Build.VERSION.SDK_INT>=28)archive.signingInfo?.apkContentsSigners else archive.signatures
        check(!own.isNullOrEmpty()&&!other.isNullOrEmpty()&&own.map{it.toCharsString()}.toSet()==other.map{it.toCharsString()}.toSet()){"安装包签名不一致，不能覆盖当前应用"}
    }
    fun install(context:Context,file:File):String? {
        if(!context.packageManager.canRequestPackageInstalls()){
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
            return "请允许此应用安装更新，返回后再次点击“安装更新”"
        }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "已打开系统安装确认；安装完成后重新打开 App 即可"
    }
}
