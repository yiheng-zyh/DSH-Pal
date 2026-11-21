package com.dsh.mobile.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.*
import android.widget.*
import com.dsh.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/** Independent read-only performance panel; it does not replace the conversation companion. */
class ComputerMonitorOverlay(private val context:Context,private val settings:SettingsStore) {
    private val wm=context.getSystemService(WindowManager::class.java)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var root:LinearLayout?=null
    private var heading:TextView?=null
    private var detail:TextView?=null
    private var graph:Graph?=null
    private var params:WindowManager.LayoutParams?=null
    private var token:Any?=null
    private var updates:Job?=null
    private var expanded=false
    private var curveMetric=ComputerMetric.VRAM
    private val visibilityReceiver=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){reconcile()}
    }
    init {
        androidx.core.content.ContextCompat.registerReceiver(context,visibilityReceiver,android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)
        },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun dp(v:Int)=(context.resources.displayMetrics.density*v).roundToInt()
    private fun bounds():android.graphics.Rect {
        val metrics=if(android.os.Build.VERSION.SDK_INT>=30)wm.currentWindowMetrics.bounds else android.graphics.Rect(0,0,context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)
        return metrics
    }
    fun reconcile() {
        if(!settings.computerMonitorOverlay||!Settings.canDrawOverlays(context)||!context.getSystemService(android.os.PowerManager::class.java).isInteractive||context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked){remove();return}
        if(root!=null)return
        build()
        if(root==null)return
        token=ComputerPerformance.retain(context)
        updates=scope.launch {combine(ComputerPerformance.state,ComputerPerformance.pageVisible){state,visible->state to visible}.collect {(state,visible)->
            root?.visibility=if(visible)View.GONE else View.VISIBLE
            render(state)
        }}
    }
    private fun label(size:Float)=TextView(context).apply {textSize=size;setTextColor(Color.WHITE);setPadding(dp(10),dp(5),dp(10),dp(5))}
    private fun build() {
        val b=bounds()
        val p=params?:WindowManager.LayoutParams(dp(if(expanded)330 else 206),if(expanded)minOf(dp(360),b.height()-dp(40)) else dp(92),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=(settings.computerMonitorX*b.width()).roundToInt();y=(settings.computerMonitorY*b.height()).roundToInt()
        }.also{params=it}
        p.width=minOf(dp(if(expanded)330 else 206),b.width()-dp(20));p.height=if(expanded)minOf(dp(360),b.height()-dp(40)) else dp(92)
        p.x=p.x.coerceIn(0,maxOf(0,b.width()-p.width));p.y=p.y.coerceIn(dp(24),maxOf(dp(24),b.height()-p.height-dp(24)))
        val box=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;background=GradientDrawable().apply{setColor(Color.rgb(30,34,43));cornerRadius=dp(20).toFloat();setStroke(dp(1),Color.rgb(74,82,96))};elevation=dp(8).toFloat();setPadding(dp(4),dp(4),dp(4),dp(4))}
        val header=LinearLayout(context)
        heading=label(12f).apply {contentDescription="拖动性能悬浮窗，点按展开或收起"}
        header.addView(heading,LinearLayout.LayoutParams(0,dp(40),1f))
        header.addView(label(18f).apply {text="×";gravity=Gravity.CENTER;contentDescription="关闭性能悬浮窗";setOnClickListener{settings.computerMonitorOverlay=false;reconcile()}},LinearLayout.LayoutParams(dp(40),dp(40)))
        var downX=0f;var downY=0f;var oldX=0;var oldY=0;var moved=false
        heading!!.setOnTouchListener {_,e->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;oldX=p.x;oldY=p.y;moved=false}
                MotionEvent.ACTION_MOVE->{val dx=e.rawX-downX;val dy=e.rawY-downY;if(kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(8))moved=true
                    if(moved){p.x=(oldX+dx).roundToInt().coerceIn(0,maxOf(0,b.width()-p.width));p.y=(oldY+dy).roundToInt().coerceIn(dp(24),maxOf(dp(24),b.height()-p.height-dp(24)));runCatching{wm.updateViewLayout(box,p)}}}
                MotionEvent.ACTION_UP->{if(moved){settings.computerMonitorX=p.x.toFloat()/b.width();settings.computerMonitorY=p.y.toFloat()/b.height()}
                    else {expanded=!expanded;runCatching{wm.removeView(box)};root=null;build();render(ComputerPerformance.state.value)}}
            };true
        }
        box.addView(header)
        graph=Graph(context).apply {
            metric=curveMetric;contentDescription="${metric.title}曲线，点按切换 CPU、GPU、内存或显存"
            setOnClickListener {curveMetric=ComputerMetric.entries[(curveMetric.ordinal+1)%ComputerMetric.entries.size];metric=curveMetric;contentDescription="${metric.title}曲线，点按切换";invalidate()}
        }
        box.addView(graph,LinearLayout.LayoutParams(-1,dp(if(expanded)64 else 40)))
        if(expanded){
            detail=label(12f)
            box.addView(ScrollView(context).apply{addView(detail)},LinearLayout.LayoutParams(-1,0,1f))
            box.addView(label(13f).apply{text="打开完整监控 ›";gravity=Gravity.CENTER;contentDescription="打开完整电脑性能页面";setOnClickListener{
                ComputerMonitorNavigation.open()
                context.startActivity(Intent(context,com.dsh.mobile.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }},LinearLayout.LayoutParams(-1,dp(44)))
        }else detail=null
        box.visibility=if(ComputerPerformance.pageVisible.value)View.GONE else View.VISIBLE
        root=box
        runCatching{wm.addView(box,p)}.onFailure{root=null;settings.computerMonitorOverlay=false}
    }
    private fun render(state:ComputerPerformance.State) {
        val sample=state.current;val gpu=sample?.gpus?.firstOrNull()
        fun percent(v:Double?)=v?.let{"${it.roundToInt()}%"}?:"—"
        fun bytes(v:Double?)=v?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
        heading?.text=if(state.message.isNotBlank())"电脑性能 · 暂不可用" else "CPU ${percent(sample?.cpu)}  GPU ${percent(gpu?.load)}"
        val list=sample?.processes.orEmpty().sortedByDescending{it.cpu?:-1.0}.take(5)
        detail?.text=(state.message.takeIf{it.isNotBlank()}?.plus("\n上次数据\n")?:"")+"内存 ${bytes(sample?.memory)} / ${bytes(sample?.memoryTotal)}\n${gpu?.name?:"GPU 未提供"}\n专用显存 ${bytes(gpu?.used)} / ${bytes(gpu?.total)}\n共享 GPU 内存 ${bytes(gpu?.shared)}\n\nCPU 占用较高的进程\n"+list.joinToString("\n"){"${it.name.take(22)}  ${percent(it.cpu)}"}
        graph?.state=state
        graph?.invalidate()
    }
    private fun remove() {updates?.cancel();updates=null;token?.let(ComputerPerformance::release);token=null;root?.let{runCatching{wm.removeView(it)}};root=null;params=null}
    fun configurationChanged(){if(root!=null){root?.let{runCatching{wm.removeView(it)}};root=null;build();render(ComputerPerformance.state.value)}}
    fun dispose(){runCatching{context.unregisterReceiver(visibilityReceiver)};remove();scope.cancel()}
    private class Graph(context:Context):View(context) {
        var state=ComputerPerformance.State()
        var metric=ComputerMetric.VRAM
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:android.graphics.Canvas){
            super.onDraw(canvas)
            val samples=state.samples.takeLast(80);val last=samples.lastOrNull();val gpu=last?.gpus?.firstOrNull()
            val values=samples.map {computerValue(it,metric,gpu?.id)}
            val maximum=when(metric){ComputerMetric.CPU,ComputerMetric.GPU->100.0;ComputerMetric.RAM->last?.memoryTotal;ComputerMetric.VRAM->gpu?.total} ?: maxOf(1.0,(values.filterNotNull().maxOrNull()?:1.0)*1.1)
            fun bytes(value:Double?)=value?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2fG",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"—"
            paint.color=Color.rgb(142,192,255);paint.textSize=resources.displayMetrics.scaledDensity*10;paint.style=Paint.Style.FILL
            canvas.drawText("内存 ${bytes(last?.memory)} · 显存 ${bytes(gpu?.used)}",dp(7),dp(12),paint)
            canvas.drawText(metric.title+" ›",width-dp(32),dp(26),paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f)
            val path=Path();var open=false
            val start=samples.firstOrNull()?.time?:0;val span=maxOf(1000L,(last?.time?:0)-start)
            values.forEachIndexed {i,v->if(v==null){open=false}else{
                val x=dp(7)+(width-dp(14))*((samples[i].time-start).toDouble()/span).toFloat()
                val y=height-dp(3)-(height-dp(30))*(v.coerceIn(0.0,maximum)/maximum).toFloat()
                if(i>0&&samples[i].time-samples[i-1].time>15000)open=false
                if(open)path.lineTo(x,y)else path.moveTo(x,y);open=true
            }}
            canvas.drawPath(path,paint)
        }
        private fun dp(v:Int)=v*resources.displayMetrics.density
        private fun dp(v:Float)=v*resources.displayMetrics.density
    }
}
