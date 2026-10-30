package com.dsh.mobile.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R
import com.dsh.mobile.core.FloatingTaskInfo
import com.dsh.mobile.core.RunLog
import com.dsh.mobile.core.SettingsStore
import com.dsh.mobile.core.TaskState
import kotlin.math.abs
import kotlin.math.roundToInt

/** A small touchable window, never a full-screen transparent input layer. Main thread only. */
class FloatingCompanion(private val context: Context, private val settings: SettingsStore, private val bridge:()->com.dsh.mobile.bridge.DshBridge? = {null}) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val reply by lazy {FloatingReplyWindow(context,bridge,{task.takeIf {it.isLive}?.sessionId.orEmpty()},{openApp()},{text->com.dsh.mobile.service.DshLiveService.current()?.pendingOverlayDraft=text;openApp(true)})}
    private var replyCreated=false
    private var root: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var fish: ImageView? = null
    private var heading: TextView? = null
    private var body: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var cardBackground: GradientDrawable? = null
    private var task = TaskState()
    private var lastInfo: FloatingTaskInfo.Info? = null
    private var lastSize = 0
    private var lastExpanded = false
    private var lastDocked = false
    private val normalBitmap by lazy { BitmapFactory.decodeResource(context.resources,R.drawable.whale_companion,BitmapFactory.Options().apply { inSampleSize=2 }) }
    private val peekBitmap by lazy { BitmapFactory.decodeResource(context.resources,R.drawable.whale_companion_peek,BitmapFactory.Options().apply { inSampleSize=2 }) }
    private var screenOff = false
    private var disposed = false
    private var registered = false
    private var unsubscribePreferences: (() -> Unit)? = null
    private var dragging = false
    private var repaintPending = false
    private val repaint = Runnable { repaintPending = false; if(!disposed) reconcile() }
    private val screenEvents = object: BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if(intent?.action == Intent.ACTION_SCREEN_OFF) { screenOff = true; remove() }
            else if(intent?.action == Intent.ACTION_USER_PRESENT || intent?.action == Intent.ACTION_SCREEN_ON) { screenOff = false; reconcile() }
        }
    }
    init {
        unsubscribePreferences = settings.observeOverlayPreferences { handler.removeCallbacks(repaint); repaintPending = true; handler.postDelayed(repaint, 180) }
        runCatching {
            ContextCompat.registerReceiver(context, screenEvents, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
    }
    fun submit(next: TaskState) {
        task = next
        // Coalesce bursts from streaming task events; no idle polling or character loop.
        if(!repaintPending) { repaintPending = true; handler.postDelayed(repaint, 180) }
    }
    fun reconcile() {
        if(disposed) return
        if(!settings.overlayEnabled || com.dsh.mobile.core.NetworkConnectivity.visible.value || !Settings.canDrawOverlays(context) || screenOff ||
            context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { remove(); return }
        if(root == null) create()
        val view = root ?: return
        val docked=settings.overlayDocked
        val changed = lastSize != settings.overlaySize || lastExpanded != settings.overlayExpanded || lastDocked != docked
        if(changed) {
            lastSize = settings.overlaySize; lastExpanded = settings.overlayExpanded
            val poseChanged = lastDocked != docked
            lastDocked=docked
            fish?.setImageBitmap(if(docked) peekBitmap else normalBitmap)
            fish?.layoutParams = LinearLayout.LayoutParams(dp(if(docked) 48 else lastSize), dp(if(docked) (lastSize*.8f).roundToInt().coerceIn(56,88) else lastSize))
            panel?.visibility = if(lastExpanded && !docked) View.VISIBLE else View.GONE
            if(poseChanged) fish?.let { image -> image.animate().cancel();if(android.animation.ValueAnimator.areAnimatorsEnabled()){image.alpha=.6f;image.animate().alpha(1f).setDuration(160).start()}else image.alpha=1f }
        }
        val info = FloatingTaskInfo.capsule(task, settings.overlayDetail, settings.overlayShowTitle)
        panel?.layoutParams = LinearLayout.LayoutParams(dp(bubbleWidth()),LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity=Gravity.CENTER_VERTICAL }
        heading?.textSize=settings.overlayFontSize.toFloat()
        body?.textSize=(settings.overlayFontSize-1).toFloat()
        body?.maxLines=if(settings.overlayDetail==2)3 else 1
        val dark=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val color=if(dark) Color.rgb(34,42,54) else Color.rgb(255,255,255)
        cardBackground?.setColor(Color.argb((255*(100-settings.overlayPanelTransparency)/100),Color.red(color),Color.green(color),Color.blue(color)))
        if(info != lastInfo) {
            heading?.text = info.heading; body?.text = info.body; lastInfo = info

        }
        fish?.scaleX=if(docked && settings.overlayX<.5f)-1f else 1f
        fish?.contentDescription=if(docked) "鲸鱼娘贴壁偷看。轻点或往外拖恢复，双击打开主对话" else "鲸鱼娘助手，${info.heading}。单击折叠信息，双击打开主对话，向屏幕边缘拖动可贴壁偷看"
        view.setPadding(dp(if(docked && settings.overlayX<.5f)0 else 4),dp(4),dp(if(docked && settings.overlayX>=.5f)0 else 4),dp(4))
        if(!dragging) position(view)
    }
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).roundToInt()
    private fun area(): Pair<Int, Int> {
        if(android.os.Build.VERSION.SDK_INT < 30) {
            val rect = android.graphics.Rect()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRectSize(rect)
            return rect.width().coerceAtLeast(dp(120)) to (rect.height()-dp(48)).coerceAtLeast(dp(160))
        }
        val metrics = wm.currentWindowMetrics
        val inset = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        return (metrics.bounds.width() - inset.left - inset.right).coerceAtLeast(dp(120)) to
            (metrics.bounds.height() - inset.top - inset.bottom).coerceAtLeast(dp(160))
    }
    private fun bubbleWidth() = minOf(settings.overlayPanelWidth, (area().first / context.resources.displayMetrics.density).toInt() - 12).coerceAtLeast(100)
    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
    }
    private fun create() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.LEFT; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if(dark) Color.rgb(236,242,249) else Color.rgb(37,53,73)
        cardBackground=GradientDrawable().apply { setColor(if(dark) Color.rgb(34,42,54) else Color.WHITE);cornerRadius=dp(40).toFloat() }
        val card = LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(6),dp(8),dp(6))
            background=cardBackground;elevation=dp(2).toFloat();minimumHeight=dp(46)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val texts=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        heading=text(12f,ink).apply {setTypeface(typeface,Typeface.BOLD);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};texts.addView(heading)
        body=text(11f,if(dark)Color.rgb(183,197,214) else Color.rgb(123,132,146)).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(2),0,0);setLineSpacing(dp(1).toFloat(),1f) };texts.addView(body)
        card.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        card.addView(android.widget.ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_edit);setColorFilter(if(dark)Color.rgb(161,207,238) else Color.rgb(80,129,157));setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(11),dp(11),dp(11),dp(11));contentDescription="快捷插话"
            setOnClickListener {replyCreated=true;reply.show()}
        },LinearLayout.LayoutParams(dp(40),dp(40)))
        panel=card
        val image = ImageView(context).apply {
            setImageBitmap(normalBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener { if(settings.overlayDocked)settings.overlayDocked=false else settings.overlayExpanded = !settings.overlayExpanded; reconcile() }
        }
        fish = image
        // ImageView still receives ordinary click/long-click for accessibility.
        var downX=0f; var downY=0f; var startX=0; var startY=0
        val detector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean {if(!dragging && image.isAttachedToWindow)image.performClick();return true}
            override fun onDoubleTap(e:MotionEvent):Boolean {if(!dragging)openApp();return true}
        }).apply {setIsLongpressEnabled(false)}
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        image.setOnTouchListener { v,e ->
            val lp=params ?: return@setOnTouchListener false
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX=e.rawX;downY=e.rawY;startX=lp.x;startY=lp.y;dragging=false;detector.onTouchEvent(e);true }
                MotionEvent.ACTION_MOVE -> {
                    if(!dragging && (abs(e.rawX-downX)>slop || abs(e.rawY-downY)>slop)) { dragging=true;val cancel=MotionEvent.obtain(e);cancel.action=MotionEvent.ACTION_CANCEL;detector.onTouchEvent(cancel);cancel.recycle();v.isPressed=false }
                    if(dragging) { val a=area();lp.x=(startX+e.rawX-downX).roundToInt().coerceIn(0,(a.first-row.width).coerceAtLeast(0));lp.y=(startY+e.rawY-downY).roundToInt().coerceIn(0,(a.second-row.height).coerceAtLeast(0));safeUpdate(row,lp) };true
                }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    val was=dragging; dragging=false
                    if(was) {
                        val a=area();val density=context.resources.displayMetrics.density
                        val peek=e.actionMasked==MotionEvent.ACTION_UP && com.dsh.mobile.core.OverlayDocking.requestsPeek(settings.overlayPeekOnEdge,a.first.toFloat(),e.rawX,e.rawX-downX,density)
                        val side=if(peek) {if(e.rawX<a.first/2)0f else 1f} else if(lp.x+row.width/2>a.first/2)1f else 0f
                        settings.setOverlayDock(side,lp.y.toFloat()/(a.second-row.height).coerceAtLeast(1),if(e.actionMasked==MotionEvent.ACTION_CANCEL)settings.overlayDocked else peek || (settings.overlayDocked && abs(e.rawX-downX)<dp(24)))
                        reconcile()
                    } else detector.onTouchEvent(e)
                    true
                }
                else -> {detector.onTouchEvent(e);true}
            }
        }
        row.addView(image); row.addView(card)
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.LEFT; title="DSH Pal 悬浮助手"; softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING }
        root=row;params=lp;lastSize=0;lastInfo=null
        try { wm.addView(row,lp);row.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> if(!dragging) position(row) };RunLog.info("Overlay","companion attached") }
        catch(e:RuntimeException) { root=null;params=null;fish=null;panel=null;RunLog.warn("Overlay","attach unavailable: ${e.javaClass.simpleName}") }
    }
    private fun position(view: LinearLayout) {
        val lp=params ?: return;val a=area()
        val image=fish;val card=panel
        if(image!=null && card!=null) {
            if(view.getChildAt(0)!==image){view.removeAllViews();view.addView(image);view.addView(card)}
            val desired=if(settings.overlayX<.5f)Gravity.LEFT else Gravity.RIGHT
            val imageParams=image.layoutParams as LinearLayout.LayoutParams
            if(imageParams.gravity!=desired){imageParams.gravity=desired;image.layoutParams=imageParams}
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(a.first,View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(a.second,View.MeasureSpec.AT_MOST))
        val newX=((a.first-view.measuredWidth).coerceAtLeast(0)*settings.overlayX).roundToInt()
        val newY=((a.second-view.measuredHeight).coerceAtLeast(0)*settings.overlayY).roundToInt()
        if(lp.x!=newX || lp.y!=newY) { lp.x=newX;lp.y=newY;safeUpdate(view,lp) }
    }
    private fun safeUpdate(view: View, lp: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(view,lp) } catch(_:RuntimeException) { remove() }
    }
    private fun openApp(withDraft:Boolean=false) { runCatching { context.startActivity(Intent(context,MainActivity::class.java).setAction("com.dsh.mobile.OPEN_WORKSPACE").putExtra("companion_draft_pending",withDraft).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } }
    fun configurationChanged() { remove();reconcile() }
    private fun remove() {
        if(replyCreated)reply.hide()
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root=null;params=null;fish=null;panel=null;heading=null;body=null;cardBackground=null;lastInfo=null;dragging=false
    }
    fun dispose() {
        if(replyCreated)reply.dispose()
        disposed=true;unsubscribePreferences?.invoke();unsubscribePreferences=null;handler.removeCallbacksAndMessages(null)
        if(registered)runCatching { context.unregisterReceiver(screenEvents) }
        registered=false;remove()
    }
}
