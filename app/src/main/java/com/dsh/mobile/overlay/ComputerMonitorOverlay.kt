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
    private var renderedStyle="curve"
    private var expanded=false
    private var gpuInstance:String?=null
    private var selectedGpuId:String?=null
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
        if(root!=null && renderedStyle!=settings.computerMonitorStyle)remove()
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
        val b=bounds();val rings=settings.computerMonitorStyle=="rings";renderedStyle=settings.computerMonitorStyle
        val compactWidth=if(rings)300 else 206;val compactHeight=if(rings)124 else 92
        val p=params?:WindowManager.LayoutParams(dp(if(expanded)330 else compactWidth),if(expanded)minOf(dp(360),b.height()-dp(40)) else dp(compactHeight),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=(settings.computerMonitorX*b.width()).roundToInt();y=(settings.computerMonitorY*b.height()).roundToInt()
        }.also{params=it}
        p.width=minOf(dp(if(expanded)330 else compactWidth),b.width()-dp(20));p.height=if(expanded)minOf(dp(360),b.height()-dp(40)) else dp(compactHeight)
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
            this.rings=rings;metric=curveMetric;gpuId=selectedGpuId;contentDescription="${metric.title}曲线，点按切换指标，长按切换显卡"
            setOnLongClickListener {
                val gpus=ComputerPerformance.state.value.current?.gpus.orEmpty()
                if(gpus.isNotEmpty()){val index=gpus.indexOfFirst{it.id==(selectedGpuId?:gpus.firstOrNull()?.id)};selectedGpuId=gpus[(index+1)%gpus.size].id;gpuId=selectedGpuId;render(ComputerPerformance.state.value)}
                true
            }
            setOnClickListener {curveMetric=ComputerMetric.entries[(curveMetric.ordinal+1)%ComputerMetric.entries.size];this.rings=rings;metric=curveMetric;gpuId=selectedGpuId;contentDescription="${metric.title}曲线，点按切换";invalidate()}
        }
        box.addView(graph,LinearLayout.LayoutParams(-1,dp(if(rings)72 else if(expanded)64 else 40)))
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
        val sample=state.current
        if(sample!=null && gpuInstance!=sample.instance){gpuInstance=sample.instance;selectedGpuId=sample.gpus.firstOrNull{it.load!=null||it.used!=null||it.shared!=null}?.id ?: sample.gpus.firstOrNull()?.id;graph?.gpuId=selectedGpuId}
        val gpu=computerGpu(sample,selectedGpuId)
        fun percent(v:Double?)=v?.let{"${it.roundToInt()}%"}?:"—"
        fun bytes(v:Double?)=v?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
        heading?.text=if(state.message.isNotBlank())"电脑性能 · 暂不可用" else "CPU ${percent(sample?.cpu)}  GPU${sample?.gpus?.indexOfFirst{it.id==gpu?.id}?.takeIf{it>=0}?:""} ${percent(gpu?.load)}"
        val list=sample?.processes.orEmpty().sortedByDescending{it.cpu?:-1.0}.take(5)
        detail?.text=android.text.SpannableStringBuilder().apply {
            fun note(text:String){
                val start=length;append(text).append("\n")
                setSpan(android.text.style.ForegroundColorSpan(Color.rgb(169,179,195)),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(.85f),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if(state.message.isNotBlank())note(state.message+" · 上次数据")
            append("内存 ${bytes(sample?.memory)} / ${bytes(sample?.memoryTotal)}\n")
            append("专用显存 ${bytes(gpu?.used)} / ${bytes(gpu?.total)}\n")
            note("共享 GPU 内存 ${bytes(gpu?.shared)}")
            note(gpu?.name?:"所选 GPU 未提供")
            if(gpu!=null && gpu.load==null && gpu.used==null && gpu.shared==null)note("此GPU暂无计数器数据，长按图表可切换")
            if((sample?.gpus?.size?:0)>1)note("长按图表切换显卡 · 进程为跨显卡统计")
            append("\n");note("CPU 占用较高的进程")
            append(list.joinToString("\n"){"${it.name.take(22)}  ${percent(it.cpu)}"})
        }
        graph?.state=state
        graph?.invalidate()
    }
    private fun remove() {updates?.cancel();updates=null;token?.let(ComputerPerformance::release);token=null;root?.let{runCatching{wm.removeView(it)}};root=null;params=null}
    fun configurationChanged(){if(root!=null){root?.let{runCatching{wm.removeView(it)}};root=null;build();render(ComputerPerformance.state.value)}}
    fun dispose(){runCatching{context.unregisterReceiver(visibilityReceiver)};remove();scope.cancel()}
    private class Graph(context:Context):View(context) {
        private var reveal=1f
        private var animator:android.animation.ValueAnimator?=null
        var state=ComputerPerformance.State()
            set(value) {
                val changed=field.current?.time!=value.current?.time
                if(changed)prior=field.current
                field=value
                if(changed){
                    animator?.cancel();reveal=1f
                    if(isShown && value.samples.size>1){
                        animator=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                            duration=250
                            interpolator=android.view.animation.DecelerateInterpolator()
                            addUpdateListener{reveal=it.animatedValue as Float;invalidate()}
                            start()
                        }
                    }
                }
                invalidate()
            }
        override fun onDetachedFromWindow(){animator?.cancel();animator=null;super.onDetachedFromWindow()}
        var rings=false
        private var prior:ComputerSample?=null
        var gpuId:String?=null
        var metric=ComputerMetric.VRAM
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:android.graphics.Canvas){
            super.onDraw(canvas)
            val samples=state.samples.takeLast(80);val last=samples.lastOrNull();val gpu=computerGpu(last,gpuId)
            if(rings){drawRings(canvas,last,gpu);return}
            val values=samples.map {computerValue(it,metric,gpuId?:gpu?.id)}
            val maximum=((when(metric){ComputerMetric.CPU,ComputerMetric.GPU->100.0;ComputerMetric.RAM->last?.memoryTotal;ComputerMetric.VRAM->gpu?.total;ComputerMetric.SHARED->gpu?.sharedTotal})?.takeIf{it>0} ?: maxOf(1.0,(values.filterNotNull().maxOrNull()?:1.0)*1.1)).coerceAtLeast(1.0)
            fun bytes(value:Double?)=value?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2fG",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"—"
            paint.color=Color.rgb(142,192,255);paint.textSize=resources.displayMetrics.scaledDensity*10;paint.style=Paint.Style.FILL
            canvas.drawText("内存 ${bytes(last?.memory)} · 显存 ${bytes(gpu?.used)}",dp(7),dp(12),paint)
            canvas.drawText(metric.title+" ›",width-dp(32),dp(26),paint)
            val baseline=height-dp(3);val chartHeight=height-dp(30)
            paint.strokeWidth=dp(.5f);paint.color=Color.argb(35,142,192,255)
            for(i in 0..4){val y=baseline-chartHeight*i/4;canvas.drawLine(dp(7),y,width-dp(7),y,paint)}
            val path=Path();var area=Path();var open=false;var lastX=0f
            val start=samples.firstOrNull()?.time?:0;val end=last?.time?:start
            val previous=samples.getOrNull(samples.lastIndex-1)?.time?:end
            val continuous=end-previous in 1..15000
            val progress=if(continuous)reveal else 1f
            val visibleEnd=previous+(end-previous)*progress.toDouble()
            val span=maxOf(1000.0,visibleEnd-start)
            fun finishArea(){
                if(open){area.lineTo(lastX,baseline);area.close();paint.style=Paint.Style.FILL;paint.color=Color.argb(46,142,192,255);canvas.drawPath(area,paint)}
                area=Path();open=false
            }
            values.forEachIndexed {i,raw->
                if(raw==null){finishArea()}else{
                    if(i>0&&samples[i].time-samples[i-1].time>15000)finishArea()
                    val newest=i==values.lastIndex;val prior=values.getOrNull(i-1)
                    val value=if(newest&&continuous&&prior!=null)prior+(raw-prior)*progress else raw
                    val time=if(newest&&continuous&&prior!=null)visibleEnd else samples[i].time.toDouble()
                    val x=dp(7)+(width-dp(14))*((time-start)/span).toFloat().coerceIn(0f,1f)
                    val y=baseline-chartHeight*(value.coerceIn(0.0,maximum)/maximum).toFloat()
                    if(open){path.lineTo(x,y);area.lineTo(x,y)}else{path.moveTo(x,y);area.moveTo(x,baseline);area.lineTo(x,y)}
                    open=true;lastX=x
                }
            }
            finishArea()
            paint.color=Color.rgb(142,192,255);paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f)
            canvas.drawPath(path,paint)
        }
        private fun drawRings(canvas:android.graphics.Canvas,sample:ComputerSample?,gpu:ComputerGpu?) {
            val memoryMetric=if(gpu?.total==0.0)ComputerMetric.SHARED else ComputerMetric.VRAM
            val metrics=listOf(ComputerMetric.CPU,ComputerMetric.GPU,ComputerMetric.RAM,memoryMetric)
            val colors=listOf(Color.rgb(107,210,176),Color.rgb(133,181,255),Color.rgb(193,166,255),Color.rgb(238,192,112))
            val column=width/4f;val radius=minOf(dp(22),column*.35f);val cy=dp(27)
            metrics.forEachIndexed {index,kind->
                val value=sample?.let{computerValue(it,kind,gpuId?:gpu?.id)}
                val old=prior?.takeIf{it.instance==sample?.instance}?.let{computerValue(it,kind,gpuId?:gpu?.id)}
                val shown=if(value!=null&&old!=null)old+(value-old)*reveal else value
                val capacity=when(kind){ComputerMetric.CPU,ComputerMetric.GPU->100.0;ComputerMetric.RAM->sample?.memoryTotal;ComputerMetric.VRAM->gpu?.total;ComputerMetric.SHARED->gpu?.sharedTotal}
                val ratio=if(shown!=null&&capacity!=null&&capacity>0)(shown/capacity).coerceIn(0.0,1.0) else null
                val cx=column*(index+.5f)
                val rect=android.graphics.RectF(cx-radius,cy-radius,cx+radius,cy+radius)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(4);paint.strokeCap=Paint.Cap.ROUND
                paint.color=Color.argb(40,255,255,255);canvas.drawArc(rect,-90f,360f,false,paint)
                if(ratio!=null){paint.color=colors[index];canvas.drawArc(rect,-90f,(ratio*360).toFloat(),false,paint)}
                paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.color=Color.WHITE
                paint.textSize=resources.displayMetrics.scaledDensity*10
                val title=when(kind){ComputerMetric.RAM->"内存";ComputerMetric.VRAM->"显存";ComputerMetric.SHARED->"共享";else->kind.title}
                canvas.drawText(title,cx,cy-dp(2),paint)
                paint.textSize=resources.displayMetrics.scaledDensity*9;paint.color=colors[index]
                canvas.drawText(ratio?.let{"${(it*100).roundToInt()}%"}?:"—",cx,cy+dp(11),paint)
                paint.color=Color.rgb(169,179,195);paint.textSize=resources.displayMetrics.scaledDensity*9
                val caption=if(kind in listOf(ComputerMetric.CPU,ComputerMetric.GPU)) {if(value==null)"未提供" else "占用率"}
                    else value?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
                canvas.drawText(caption,cx,dp(64),paint)
            }
            paint.textAlign=Paint.Align.LEFT;paint.strokeCap=Paint.Cap.BUTT
        }
        private fun dp(v:Int)=v*resources.displayMetrics.density
        private fun dp(v:Float)=v*resources.displayMetrics.density
    }
}
