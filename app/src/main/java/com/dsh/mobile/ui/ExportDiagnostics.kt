package com.dsh.mobile.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.dsh.mobile.core.RunLog
import kotlinx.coroutines.*
fun shareDiagnostics(context:Context,scope:CoroutineScope,onError:(String)->Unit) {
    scope.launch {runCatching {
        val file=withContext(Dispatchers.IO){RunLog.export(context)}
        val uri=FileProvider.getUriForFile(context,context.packageName+".files",file)
        val share=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(share,"导出问题日志"))
    }.onFailure {onError("无法导出日志（${it.javaClass.simpleName}）")} }
}
