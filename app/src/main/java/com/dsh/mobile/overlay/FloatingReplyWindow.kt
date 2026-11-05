package com.dsh.mobile.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.dsh.mobile.bridge.DshBridge
import com.dsh.mobile.core.NetworkConnectivity
import com.dsh.mobile.core.QuickReplyTarget
import kotlinx.coroutines.*
import org.json.JSONObject

class FloatingReplyWindow(private val context:Context,private val bridge:()->DshBridge?,private val runningSession:()->String,private val openApp:()->Unit,private val openDraft:(String)->Unit) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val wm=context.getSystemService(WindowManager::class.java)
    private val store=OverlayReplyDraftStore(context)
    private var draft=OverlayReplyDraftStore.Draft()
    private var loaded=false
    private var root:LinearLayout?=null
    private var input:EditText?=null
    private var status:TextView?=null
    private var send:Button?=null
    private var saveJob:Job?=null
    private var sending=false
    private var currentTarget:String?=null
    private var generation=0
    private var disposed=false
    private fun dp(v:Int)=(v*context.resources.displayMetrics.density).toInt()
    fun show() {
        if(disposed || root!=null || NetworkConnectivity.visible.value || !Settings.canDrawOverlays(context))return
        val id=++generation
        scope.launch {
            if(!loaded){draft=withContext(Dispatchers.IO){store.load()};loaded=true}
            bridge()?.captureQuickContext { target ->
                if(id!=generation || disposed || NetworkConnectivity.visible.value)return@captureQuickContext
                currentTarget=target?.takeIf {QuickReplyTarget.matchesSession(it,runningSession())}
                if(draft.text.isBlank())draft=OverlayReplyDraftStore.Draft(target=target.orEmpty())
                create()
            } ?: run {currentTarget=null;create()}
        }
    }
    private fun save(immediate:Boolean=false) {
        saveJob?.cancel();val snapshot=draft
        saveJob=scope.launch {if(!immediate)delay(450);val ok=withContext(Dispatchers.IO){store.save(snapshot)};if(!ok)status?.text="草稿暂存于本次运行中，请复制备份"}
    }
    private fun matches()=currentTarget?.let {QuickReplyTarget.same(it,draft.target)&&QuickReplyTarget.matchesSession(draft.target,runningSession())}==true
    private fun updateButton(){send?.isEnabled=!sending&&draft.text.isNotBlank();send?.text=if(draft.uncertain || !matches())if(draft.uncertain)"查看对话" else "主界面编辑" else if(sending)"提交中" else "发送"}
    private fun create() {
        if(root!=null || disposed || NetworkConnectivity.visible.value || !Settings.canDrawOverlays(context))return
        val row=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=GradientDrawable().apply {setColor(Color.rgb(248,251,255));cornerRadius=dp(20).toFloat()};elevation=dp(4).toFloat()}
        val top=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        val title=runCatching {JSONObject(draft.target).getJSONObject("context").optString("title")}.getOrDefault("")
        top.addView(TextView(context).apply {text="插话"+if(title.isNotBlank())" · $title" else " · 目标待确认";setTextColor(Color.rgb(37,53,73));textSize=13f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,dp(40),1f))
        top.addView(Button(context).apply {text="×";contentDescription="收起快捷输入并保留草稿";setOnClickListener {hide()}},LinearLayout.LayoutParams(dp(44),dp(40)))
        row.addView(top)
        val edit=object:EditText(context){override fun onKeyPreIme(keyCode:Int,event:KeyEvent):Boolean {if(keyCode==KeyEvent.KEYCODE_BACK&&event.action==KeyEvent.ACTION_UP){hide();return true};return super.onKeyPreIme(keyCode,event)}}.apply {
            hint="补充要求或插话…";textSize=14f;minLines=2;maxLines=4;gravity=Gravity.TOP
            isFocusable=true;isFocusableInTouchMode=true;isCursorVisible=true
            setTextColor(Color.rgb(37,53,73));inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters=arrayOf(android.text.InputFilter.LengthFilter(8000));setText(draft.text);setSelection(text.length)
            doAfterTextChanged {text->if(text.toString()!=draft.text){draft=draft.copy(text=text.toString(),uncertain=false);save();updateButton()}}
        }
        input=edit;row.addView(edit,LinearLayout.LayoutParams(-1,-2))
        val bottom=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        status=TextView(context).apply {text=if(!matches())"会话已变化或尚未就绪，草稿仍保留" else if(draft.uncertain)"上次提交待核对，请到主界面确认" else "只发送到当前会话，不覆盖已有草稿";textSize=11f;setTextColor(Color.rgb(110,121,138))}
        bottom.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        send=Button(context).apply {setOnClickListener {
            if(sending)return@setOnClickListener
            if(draft.uncertain){hide();openApp();return@setOnClickListener}
            if(!matches()){val text=draft.text;draft=draft.copy(uncertain=true);hide();openDraft(text);return@setOnClickListener}
            val route=bridge() ?: run {status?.text="当前对话未就绪，草稿已保留";return@setOnClickListener}
            sending=true;updateButton();status?.text="正在交给当前对话…"
            route.submitQuick(draft.text,draft.target) { outcome ->
                sending=false
                when(outcome){
                    "dispatched" -> {draft=draft.copy(uncertain=true);status?.text="已交给对话，请在主界面确认发送结果"}
                    "timeout","staged" -> {draft=draft.copy(uncertain=true);status?.text="发送状态待核对，草稿已保留"}
                    "draft-occupied" -> status?.text="主对话已有草稿，请先处理；未覆盖"
                    "context-changed" -> {currentTarget=null;status?.text="会话已切换，未发送，草稿仍保留"}
                    else -> status?.text="暂不支持快捷发送，请回主对话；草稿已保留"
                }
                save(true);updateButton()
            }
        }}
        bottom.addView(send,LinearLayout.LayoutParams(dp(96),dp(44)));row.addView(bottom)
        val width=minOf(context.resources.displayMetrics.widthPixels-dp(24),dp(360))
        val lp=WindowManager.LayoutParams(width,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;y=dp(64);this.title="DSH Pal 快捷输入";softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        }
        row.setOnTouchListener {_,event -> if(event.actionMasked==MotionEvent.ACTION_OUTSIDE){hide();true}else false}
        root=row
        try {wm.addView(row,lp);updateButton();edit.requestFocus();edit.postDelayed({if(root===row){edit.requestFocus();context.getSystemService(InputMethodManager::class.java).showSoftInput(edit,InputMethodManager.SHOW_IMPLICIT)}},150)}
        catch(_:RuntimeException){root=null;input=null;status=null;send=null}
    }
    fun hide(){val hadWindow=root!=null;generation++;root?.let {view->context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0);runCatching {wm.removeViewImmediate(view)}};root=null;input=null;status=null;send=null;if(loaded&&hadWindow)save(true)}
    fun dispose(){disposed=true;hide();/* pending encrypted save is allowed to finish */}
}
