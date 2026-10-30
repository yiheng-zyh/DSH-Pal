package com.dsh.mobile.ui.screen

import android.content.Intent
import android.net.Uri
import com.dsh.mobile.ui.AppBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.BuildConfig
import com.dsh.mobile.ui.AppPageHeader
import com.dsh.mobile.ui.DshBrandMark
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Project URLs for the public repository. */
private const val PROJECT_URL = "https://github.com/yiheng-zyh/DSH-Pal"
private const val ISSUE_URL = "https://github.com/yiheng-zyh/DSH-Pal/issues"
private const val BILIBILI_URL = "https://b23.tv/8o1bX3M"

@Composable
fun AboutScreen(onBack:()->Unit) {
    var document by remember {mutableStateOf<Pair<String,String>?>(null)}
    val context=LocalContext.current
    val text=remember(document){document?.let {runCatching {context.assets.open("licenses/${it.second}").bufferedReader().use {it.readText()}}.getOrDefault("暂时无法读取许可，请导出诊断记录反馈。")}}
    fun back(){if(document!=null)document=null else onBack()}
    BackHandler {back()}
    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
        Column(Modifier.widthIn(max=840.dp).fillMaxSize().padding(20.dp)) {
            AppPageHeader(document?.first?:"关于",onBack={back()})
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                if(document!=null)SelectionContainer {Text(text.orEmpty(),fontSize=13.sp)}
                else {
                    Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        DshBrandMark(80.dp)
                        Text("DSH Pal",fontSize=22.sp)
                        Text("版本 ${BuildConfig.VERSION_NAME}",fontSize=13.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                    }
                    Card {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("项目与社区")
                        Text("GitHub 项目 · "+PROJECT_URL,fontSize=14.sp,color=MiuixTheme.colorScheme.onBackgroundVariant,modifier=Modifier.clickable{runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(PROJECT_URL)))}})
                        Text("问题反馈 / Issue · "+ISSUE_URL,fontSize=14.sp,color=MiuixTheme.colorScheme.onBackgroundVariant,modifier=Modifier.clickable{runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ISSUE_URL)))}})
                        Text("哔哩哔哩 · 演示视频",fontSize=14.sp,color=MiuixTheme.colorScheme.onBackgroundVariant,modifier=Modifier.clickable{runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(BILIBILI_URL)))}})
                        Text("当前可从支持与诊断导出问题日志。",fontSize=12.sp)
                    }}
                    listOf("应用许可" to "APP-LICENSE.txt","第三方说明与角色署名" to "THIRD-PARTY.txt","DSHgo · MIT" to "DSHgo-MIT.txt","Tailscale · BSD" to "TAILSCALE.txt","Go · BSD" to "GO.txt").forEach {entry ->
                        Card(Modifier.fillMaxWidth().clickable {document=entry}) {Text(entry.first+"  ›",modifier=Modifier.padding(18.dp),fontSize=15.sp)}
                    }
                    Text("许可文本可离线阅读。角色、字体及第三方组件分别受各自许可约束。",fontSize=12.sp,color=MiuixTheme.colorScheme.onBackgroundVariant)
                }
            }
        }
    }
}
