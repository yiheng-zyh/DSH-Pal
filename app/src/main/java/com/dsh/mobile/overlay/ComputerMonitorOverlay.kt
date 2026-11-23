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
    private var root:FrameLayout?=null
    private var detail:TextView?=null
    private var graph:Graph?=null
    private var params:WindowManager.LayoutParams?=null
    private var anchorX=settings.computerMonitorAnchorX
    private var anchorY=settings.computerMonitorAnchorY
    private var token:Any?=null
    private var updates:Job?=null
    private var renderedItems=emptySet<String>()
    private var renderedLight=true
    private var morphAnimator:android.animation.ValueAnimator?=null
    private var tapAction:(()->Unit)?=null
    private val taps by lazy {GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapConfirmed(e:MotionEvent):Boolean {tapAction?.invoke();return true}
        override fun onDoubleTap(e:MotionEvent):Boolean {tapAction=null;settings.computerMonitorOverlay=false;reconcile();return true}
    }).apply{setIsLongpressEnabled(false)}}
    private var renderedTop=false
    private var renderedStyle="curve"
    private var renderedVertical=false
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
        if(root!=null && (renderedStyle!=settings.computerMonitorStyle || renderedVertical!=settings.computerMonitorRingVertical || renderedItems!=settings.computerMonitorItems || renderedTop!=settings.computerMonitorGpuTop || renderedLight!=settings.computerMonitorLight)) {
            morphAnimator?.cancel()
            if(renderedStyle!=settings.computerMonitorStyle)expanded=false
            root?.let{runCatching{wm.removeView(it)}};root=null
            build();render(ComputerPerformance.state.value)
            if(root==null)remove()
            return
        }
        if(root!=null){applyBackgroundTransparency();return}
        build()
        if(root==null)return
        token=ComputerPerformance.retain(context)
        updates=scope.launch {combine(ComputerPerformance.state,ComputerPerformance.pageVisible){state,visible->state to visible}.collect {(state,visible)->
            root?.visibility=if(visible)View.GONE else View.VISIBLE
            render(state)
        }}
    }
    private fun label(size:Float)=TextView(context).apply {textSize=size;setTextColor(if(settings.computerMonitorLight)Color.rgb(32,40,52) else Color.WHITE);setPadding(dp(10),dp(5),dp(10),dp(5))}
    private fun build() {
        renderedLight=settings.computerMonitorLight
        renderedItems=settings.computerMonitorItems;renderedTop=settings.computerMonitorGpuTop
        if(renderedItems.all{it=="PROCESSES"})expanded=true
        val count=renderedItems.count{it in setOf("CPU","GPU","RAM","VRAM","SHARED","DISK")}.coerceAtLeast(1)
        val b=bounds();val rings=settings.computerMonitorStyle=="rings";renderedStyle=settings.computerMonitorStyle
        val vertical=rings&&settings.computerMonitorRingVertical;renderedVertical=settings.computerMonitorRingVertical
        val compactWidth=if(vertical)72 else if(rings)maxOf(140,count*61+8) else 206
        val compactHeight=if(vertical)maxOf(78,14+count*58) else if(rings)82 else 70
        val windowHeight=minOf(dp(if(expanded){if(vertical)226 else if(rings)208 else 270}else compactHeight),maxOf(dp(120),b.height()-dp(48)))
        val expandedWidth=if(vertical)240 else if(rings)260 else 310
        val p=params?:WindowManager.LayoutParams(dp(if(expanded)expandedWidth else compactWidth),windowHeight,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=(settings.computerMonitorX*b.width()).roundToInt();y=(settings.computerMonitorY*b.height()).roundToInt()
        }.also{params=it}
        p.width=minOf(dp(if(expanded)expandedWidth else compactWidth),b.width()-dp(20));p.height=windowHeight
        val maxX=maxOf(0,b.width()-p.width);val minY=dp(24);val maxY=maxOf(minY,b.height()-p.height-dp(24))
        if(anchorX<0)anchorX=OverlayPlacement.anchor(p.x,0,maxX)
        if(anchorY<0)anchorY=OverlayPlacement.anchor(p.y,minY,maxY)
        p.x=OverlayPlacement.coordinate(anchorX,0,maxX);p.y=OverlayPlacement.coordinate(anchorY,minY,maxY)
        settings.computerMonitorAnchorX=anchorX;settings.computerMonitorAnchorY=anchorY
        fun saveDraggedPosition(){
            anchorX=OverlayPlacement.anchor(p.x,0,maxX);anchorY=OverlayPlacement.anchor(p.y,minY,maxY)
            settings.computerMonitorAnchorX=anchorX;settings.computerMonitorAnchorY=anchorY
            settings.computerMonitorX=p.x.toFloat()/b.width();settings.computerMonitorY=p.y.toFloat()/b.height()
        }
        val box=FrameLayout(context).apply {
            background=GradientDrawable().apply{setColor(if(renderedLight)Color.rgb(250,251,253) else Color.rgb(30,34,43));cornerRadius=dp(14).toFloat();setStroke(1,if(renderedLight)Color.argb(45,80,90,110) else Color.argb(65,170,180,200))}
            elevation=dp(3).toFloat()
        }
        val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(10))}
        box.addView(content,FrameLayout.LayoutParams(-1,-1))
        // All non-scrollable panel surfaces share one gesture owner. Moving cancels
        // both the pending long press and the eventual click, including after a hold.
        val touchSlop=ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downX=0f;var downY=0f;var oldX=0;var oldY=0
        var moved=false;var held=false;var active=false
        var heldView:View?=null
        val longPress=Runnable {
            val target=heldView
            if(active&&!moved&&root===box&&target?.isAttachedToWindow==true) {
                held=true;val cancel=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0f,0f,0);taps.onTouchEvent(cancel);cancel.recycle();target.performLongClick()
            }
        }
        fun cancelHold(){heldView?.removeCallbacks(longPress);heldView=null}
        val dragTouch=View.OnTouchListener {view,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    morphAnimator?.end();tapAction={view.performClick()};taps.onTouchEvent(event)
                    cancelHold();downX=event.rawX;downY=event.rawY;oldX=p.x;oldY=p.y
                    moved=false;held=false;active=true
                    if(view.isLongClickable){heldView=view;view.postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong())}
                }
                MotionEvent.ACTION_MOVE->if(active){
                    taps.onTouchEvent(event)
                    val dx=event.rawX-downX;val dy=event.rawY-downY
                    if(!moved&&dx*dx+dy*dy>touchSlop*touchSlop){moved=true;cancelHold()}
                    if(moved){
                        p.x=(oldX+dx).roundToInt().coerceIn(0,maxOf(0,b.width()-p.width))
                        p.y=(oldY+dy).roundToInt().coerceIn(dp(24),maxOf(dp(24),b.height()-p.height-dp(24)))
                        runCatching{wm.updateViewLayout(box,p)}
                    }
                }
                MotionEvent.ACTION_UP->{
                    cancelHold()
                    if(active){
                        active=false
                        if(moved)saveDraggedPosition()
                        else if(!held)taps.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{
                    taps.onTouchEvent(event);cancelHold();active=false
                    if(moved)saveDraggedPosition()
                }
            }
            true
        }
        box.setOnTouchListener(dragTouch)
        graph=Graph(context).apply {
            val allowed=ComputerMetric.entries.filter{it.name in renderedItems}
            if(curveMetric !in allowed)curveMetric=allowed.firstOrNull()?:ComputerMetric.CPU
            light=renderedLight;this.rings=rings;this.vertical=vertical;metric=curveMetric;gpuId=selectedGpuId;items=renderedItems;contentDescription=if(rings)"性能圆环，滑动拖动，点按切换横竖，长按切换显卡" else "${metric.title}曲线，滑动拖动，点按切换指标，长按切换显卡"
            setOnLongClickListener {
                val gpus=ComputerPerformance.state.value.current?.gpus.orEmpty()
                if(gpus.isNotEmpty()){val index=gpus.indexOfFirst{it.id==(selectedGpuId?:gpus.firstOrNull()?.id)};selectedGpuId=gpus[(index+1)%gpus.size].id;gpuId=selectedGpuId;render(ComputerPerformance.state.value)}
                true
            }
            setOnClickListener {
                if(rings)toggleRingOrientation()
                else {val allowed=ComputerMetric.entries.filter{it.name in items};if(allowed.isNotEmpty())curveMetric=allowed[(allowed.indexOf(curveMetric)+1).mod(allowed.size)];metric=curveMetric;gpuId=selectedGpuId;contentDescription="${metric.title}曲线，点按切换";invalidate()}
            }
        }
        graph!!.setOnTouchListener(dragTouch)
        if(vertical) {
            val body=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL}
            body.addView(graph,LinearLayout.LayoutParams(dp(64),-1))
            if(expanded){
                detail=label(10.5f).apply{setPadding(dp(6),dp(3),dp(6),dp(3));setLineSpacing(dp(1).toFloat(),1f)}
                body.addView(ScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(detail)},LinearLayout.LayoutParams(0,-1,1f))
            } else detail=null
            content.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        } else {
            content.addView(graph,LinearLayout.LayoutParams(-1,dp(if(rings)64 else if(expanded)64 else 40)))
            if(expanded){
                detail=label(if(rings)11f else 12f)
                content.addView(ScrollView(context).apply{addView(detail)},LinearLayout.LayoutParams(-1,0,1f))
            }else detail=null
        }
        if(rings)box.setOnClickListener{toggleRingOrientation()}
        detail?.apply{
            setOnClickListener{if(rings)toggleRingOrientation()}
            setOnTouchListener {view,event->if(event.actionMasked==MotionEvent.ACTION_DOWN)tapAction={view.performClick()};taps.onTouchEvent(event);true}
        }
        val grip=object:View(context){
            private val brush=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=dp(3).toFloat();strokeCap=Paint.Cap.ROUND}
            override fun onDraw(canvas:android.graphics.Canvas){
                val path=Path().apply{moveTo(dp(12).toFloat(),height-dp(7).toFloat());quadTo(width-dp(7).toFloat(),height-dp(7).toFloat(),width-dp(7).toFloat(),dp(12).toFloat())}
                brush.color=Color.argb(85,0,0,0);brush.strokeWidth=dp(4).toFloat();canvas.drawPath(path,brush)
                brush.color=Color.WHITE;brush.strokeWidth=dp(2).toFloat();canvas.drawPath(path,brush)
            }
        }.apply{contentDescription="拖动把手展开或收起性能详情"}
        var gripX=0f;var gripY=0f;var startW=0;var startH=0;var startExpanded=false
        grip.setOnTouchListener {_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{morphAnimator?.end();gripX=event.rawX;gripY=event.rawY;startW=p.width;startH=p.height;startExpanded=expanded}
                MotionEvent.ACTION_MOVE->{
                    val delta=((event.rawX-gripX)+(event.rawY-gripY))/2
                    val w=if(startExpanded)dp(compactWidth) else dp(expandedWidth)
                    val h=if(startExpanded)dp(compactHeight) else minOf(dp(if(vertical)226 else if(rings)208 else 270),b.height()-dp(48))
                    val progress=(kotlin.math.abs(delta)/dp(80)).coerceIn(0f,1f)
                    if((!startExpanded&&delta>0)||(startExpanded&&delta<0)){
                        p.width=(startW+(w-startW)*progress).roundToInt().coerceAtMost(b.width()-dp(20));p.height=(startH+(h-startH)*progress).roundToInt()
                        p.x=OverlayPlacement.coordinate(anchorX,0,maxOf(0,b.width()-p.width));p.y=OverlayPlacement.coordinate(anchorY,dp(24),maxOf(dp(24),b.height()-p.height-dp(24)))
                        runCatching{wm.updateViewLayout(box,p)}
                    }
                }
                MotionEvent.ACTION_UP->{val delta=((event.rawX-gripX)+(event.rawY-gripY))/2;animateExpanded(if(kotlin.math.abs(delta)>dp(18))delta>0 else startExpanded)}
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->animateExpanded(startExpanded)
            };true
        }
        box.addView(grip,FrameLayout.LayoutParams(dp(32),dp(32),Gravity.BOTTOM or Gravity.RIGHT))
        box.visibility=if(ComputerPerformance.pageVisible.value)View.GONE else View.VISIBLE
        root=box
        applyBackgroundTransparency()
        runCatching{wm.addView(box,p)}.onFailure{root=null;settings.computerMonitorOverlay=false}
    }
    private fun applyBackgroundTransparency(){
        // Only the panel drawable fades; labels, graphs and touch targets stay intact.
        root?.background?.alpha=((100-settings.computerMonitorBackgroundTransparency)*255f/100).roundToInt()
    }
    private fun render(state:ComputerPerformance.State) {
        val sample=state.current
        if(sample!=null && gpuInstance!=sample.instance){gpuInstance=sample.instance;selectedGpuId=sample.gpus.firstOrNull{it.load!=null||it.used!=null||it.shared!=null}?.id ?: sample.gpus.firstOrNull()?.id;graph?.gpuId=selectedGpuId}
        val gpu=computerGpu(sample,selectedGpuId)
        fun percent(v:Double?)=v?.let{"${it.roundToInt()}%"}?:"—"
        fun bytes(v:Double?)=v?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
        val list=sample?.processes.orEmpty().sortedByDescending{if(settings.computerMonitorGpuTop)it.gpu?:-1.0 else it.cpu?:-1.0}.take(if(settings.computerMonitorStyle=="rings")3 else 5)
        detail?.text=android.text.SpannableStringBuilder().apply {
            val muted=if(renderedLight)Color.rgb(92,104,120) else Color.rgb(158,169,186)
            val cpuColor=if(renderedLight)Color.rgb(18,126,91) else Color.rgb(107,210,176);val gpuColor=if(renderedLight)Color.rgb(44,102,192) else Color.rgb(133,181,255)
            val ramColor=if(renderedLight)Color.rgb(119,76,183) else Color.rgb(193,166,255);val vramColor=if(renderedLight)Color.rgb(158,101,15) else Color.rgb(238,192,112)
            fun segment(text:String,color:Int,size:Float=1f,bold:Boolean=false){
                val start=length;append(text)
                setSpan(android.text.style.ForegroundColorSpan(color),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(size!=1f)setSpan(android.text.style.RelativeSizeSpan(size),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if(bold)setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),start,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            fun resource(title:String,value:Double?,capacity:Double?,color:Int){
                segment("$title  ",muted,.9f);segment(bytes(value),color,bold=true)
                if(capacity!=null)segment(" / ${bytes(capacity)}",muted,.85f)
                append("\n")
            }
            if(state.message.isNotBlank()){segment("数据未更新\n",vramColor,.85f)}
            if("RAM" in renderedItems)resource("内存",sample?.memory,sample?.memoryTotal,ramColor)
            if("VRAM" in renderedItems)resource("专用显存",gpu?.used,gpu?.total,vramColor)
            if("SHARED" in renderedItems)resource("共享内存",gpu?.shared,null,gpuColor)
            if("GPU" in renderedItems||"VRAM" in renderedItems||"SHARED" in renderedItems)segment(gpu?.name?:"GPU 未提供",muted,.8f)
            if("DISK" in renderedItems){append("\n");segment("磁盘 ${percent(sample?.diskActive)}\n",gpuColor);segment("读 ${bytes(sample?.diskRead)}/s · 写 ${bytes(sample?.diskWrite)}/s\n",muted,.85f)}
            if("PROCESSES" in renderedItems){
            append("\n");segment("${if(settings.computerMonitorGpuTop)"GPU" else "CPU"} · TOP ${list.size}\n",if(settings.computerMonitorGpuTop)gpuColor else cpuColor,.85f,bold=true)
            list.forEachIndexed {index,process->
                segment(process.name.take(22),if(renderedLight)Color.rgb(32,40,52) else Color.rgb(226,232,241),.95f)
                segment("  ${percent(if(settings.computerMonitorGpuTop)process.gpu else process.cpu)}",if(settings.computerMonitorGpuTop)gpuColor else cpuColor,bold=true)
                if(index<list.lastIndex)append("\n")
            }
            }
        }
        graph?.state=state
        graph?.invalidate()
    }
    private fun toggleRingOrientation(){morphAnimator?.cancel();settings.computerMonitorRingVertical=!settings.computerMonitorRingVertical;reconcile()}
    private fun animateExpanded(value:Boolean){
        val old=params?:return;val fromW=old.width;val fromH=old.height
        morphAnimator?.cancel();expanded=value
        root?.let{runCatching{wm.removeView(it)}};root=null;build();render(ComputerPerformance.state.value)
        val panel=root?:return;val p=params?:return;val toW=p.width;val toH=p.height;val b=bounds()
        val enabled=android.os.Build.VERSION.SDK_INT<26||android.animation.ValueAnimator.areAnimatorsEnabled()
        if(!enabled)return
        morphAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
            duration=260;interpolator=android.view.animation.PathInterpolator(.2f,0f,.1f,1f)
            addUpdateListener{val t=it.animatedValue as Float;p.width=(fromW+(toW-fromW)*t).roundToInt();p.height=(fromH+(toH-fromH)*t).roundToInt()
                p.x=OverlayPlacement.coordinate(anchorX,0,maxOf(0,b.width()-p.width));p.y=OverlayPlacement.coordinate(anchorY,dp(24),maxOf(dp(24),b.height()-p.height-dp(24)))
                detail?.alpha=t;runCatching{wm.updateViewLayout(panel,p)}}
            start()
        }
    }
    private fun remove() {morphAnimator?.cancel();tapAction=null;updates?.cancel();updates=null;token?.let(ComputerPerformance::release);token=null;root?.let{runCatching{wm.removeView(it)}};root=null;params=null}
    fun configurationChanged(){morphAnimator?.cancel();if(root!=null){root?.let{runCatching{wm.removeView(it)}};root=null;build();render(ComputerPerformance.state.value)}}
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
        var items=emptySet<String>()
        var light=true
        var rings=false
        var vertical=false
        private var prior:ComputerSample?=null
        var gpuId:String?=null
        var metric=ComputerMetric.VRAM
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:android.graphics.Canvas){
            super.onDraw(canvas)
            val samples=state.samples.takeLast(80);val last=samples.lastOrNull();val gpu=computerGpu(last,gpuId)
            if(rings){drawRings(canvas,last,gpu);return}
            val diskOnly=items.none{it in setOf("CPU","GPU","RAM","VRAM","SHARED")}&&"DISK" in items
            val values=samples.map {if(diskOnly)it.diskActive else computerValue(it,metric,gpuId?:gpu?.id)}
            val maximum=if(diskOnly)100.0 else ((when(metric){ComputerMetric.CPU,ComputerMetric.GPU->100.0;ComputerMetric.RAM->last?.memoryTotal;ComputerMetric.VRAM->gpu?.total;ComputerMetric.SHARED->gpu?.sharedTotal})?.takeIf{it>0} ?: maxOf(1.0,(values.filterNotNull().maxOrNull()?:1.0)*1.1)).coerceAtLeast(1.0)
            fun bytes(value:Double?)=value?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2fG",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"—"
            paint.color=Color.rgb(142,192,255);paint.textSize=resources.displayMetrics.scaledDensity*10;paint.style=Paint.Style.FILL
            canvas.drawText(if(diskOnly)"磁盘活动 · 最忙物理磁盘" else metric.title,dp(7),dp(12),paint)
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
            val memoryMetric=ComputerMetric.VRAM
            val metrics=listOf(ComputerMetric.CPU,ComputerMetric.GPU,ComputerMetric.RAM,memoryMetric,ComputerMetric.SHARED).filter{it.name in items}.map{it as ComputerMetric?} + if("DISK" in items)listOf(null) else emptyList()
            val colors=metrics.map{if(light)when(it){ComputerMetric.CPU->Color.rgb(18,126,91);ComputerMetric.GPU,ComputerMetric.SHARED->Color.rgb(44,102,192);ComputerMetric.RAM->Color.rgb(119,76,183);ComputerMetric.VRAM->Color.rgb(158,101,15);null->Color.rgb(18,131,140)} else when(it){ComputerMetric.CPU->Color.rgb(107,210,176);ComputerMetric.GPU,ComputerMetric.SHARED->Color.rgb(133,181,255);ComputerMetric.RAM->Color.rgb(193,166,255);ComputerMetric.VRAM->Color.rgb(238,192,112);null->Color.rgb(94,211,218)}}
            val column=if(vertical)width.toFloat() else width/metrics.size.coerceAtLeast(1).toFloat()
            val row=if(vertical)height/metrics.size.coerceAtLeast(1).toFloat() else height.toFloat()
            val scale=minOf(1f,row/dp(72))
            val radius=minOf(dp(22)*scale,column*.35f)
            metrics.forEachIndexed {index,kind->
                val cy=(if(vertical)row*index else 0f)+dp(27)*scale
                val value=sample?.let{if(kind==null)it.diskActive else computerValue(it,kind,gpuId?:gpu?.id)}
                val old=prior?.takeIf{it.instance==sample?.instance}?.let{if(kind==null)it.diskActive else computerValue(it,kind,gpuId?:gpu?.id)}
                val shown=if(value!=null&&old!=null)old+(value-old)*reveal else value
                val capacity=when(kind){null->100.0;ComputerMetric.CPU,ComputerMetric.GPU->100.0;ComputerMetric.RAM->sample?.memoryTotal;ComputerMetric.VRAM->gpu?.total;ComputerMetric.SHARED->gpu?.sharedTotal}
                val ratio=if(shown!=null&&capacity!=null&&capacity>0)(shown/capacity).coerceIn(0.0,1.0) else null
                val cx=if(vertical)column*.5f else column*(index+.5f)
                val rect=android.graphics.RectF(cx-radius,cy-radius,cx+radius,cy+radius)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(4)*scale;paint.strokeCap=Paint.Cap.ROUND
                paint.color=if(light)Color.argb(28,30,40,60) else Color.argb(40,255,255,255);canvas.drawArc(rect,-90f,360f,false,paint)
                if(ratio!=null){paint.color=colors[index.coerceAtMost(colors.lastIndex)];canvas.drawArc(rect,-90f,(ratio*360).toFloat(),false,paint)}
                paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.CENTER;paint.color=if(light)Color.rgb(32,40,52) else Color.WHITE
                paint.textSize=resources.displayMetrics.scaledDensity*10*scale
                val title=when(kind){null->"磁盘";ComputerMetric.RAM->"内存";ComputerMetric.VRAM->"显存";ComputerMetric.SHARED->"共享";else->kind.title}
                canvas.drawText(title,cx,cy-dp(2)*scale,paint)
                paint.textSize=resources.displayMetrics.scaledDensity*9*scale;paint.color=colors[index.coerceAtMost(colors.lastIndex)]
                canvas.drawText(ratio?.let{"${(it*100).roundToInt()}%"}?:"—",cx,cy+dp(11)*scale,paint)
                paint.color=if(light)Color.rgb(92,104,120) else Color.rgb(169,179,195);paint.textSize=resources.displayMetrics.scaledDensity*9*scale
                val caption=if(kind==null)"活动时间" else if(kind in listOf(ComputerMetric.CPU,ComputerMetric.GPU)) {if(value==null)"未提供" else "占用率"}
                    else value?.let{if(it>=1073741824.0)String.format(java.util.Locale.getDefault(),"%.2f GB",it/1073741824.0) else ResourceLoad.size(it.toLong())}?:"未提供"
                canvas.drawText(caption,cx,(if(vertical)row*index else 0f)+dp(64)*scale,paint)
            }
            paint.textAlign=Paint.Align.LEFT;paint.strokeCap=Paint.Cap.BUTT
        }
        private fun dp(v:Int)=v*resources.displayMetrics.density
        private fun dp(v:Float)=v*resources.displayMetrics.density
    }
}
