package com.dsh.mobile.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

val LocalAppPredictiveBack = staticCompositionLocalOf { false }
val LocalBackGestureState = staticCompositionLocalOf<BackGestureState?> { null }

@Stable
class BackGestureState {
    var progress by mutableFloatStateOf(0f)
    var direction by mutableFloatStateOf(1f)
    var generation = 0
    fun reset() { generation++; progress=0f }
}

fun Modifier.backGesturePreview(state: BackGestureState) = graphicsLayer {
    scaleX=1f-state.progress*.035f; scaleY=scaleX
    translationX=size.width*state.progress*.06f*state.direction
    shape=RoundedCornerShape((state.progress*20f).dp);clip=state.progress>0f
}

/** One visual owner per page/overlay; child back handlers retain normal priority. */
@Composable
fun BackGestureSurface(modifier: Modifier=Modifier, content: @Composable BoxScope.()->Unit) {
    val state=remember { BackGestureState() }
    val owner=LocalLifecycleOwner.current
    DisposableEffect(owner,state) {
        val observer=LifecycleEventObserver { _,event->if(event==Lifecycle.Event.ON_STOP)state.reset() }
        owner.lifecycle.addObserver(observer)
        onDispose {owner.lifecycle.removeObserver(observer);state.reset()}
    }
    CompositionLocalProvider(LocalBackGestureState provides state) {
        Box(modifier.fillMaxSize().backGesturePreview(state),content=content)
    }
}

/** Drop-in back handler: commit on completion, preserve the route on cancellation. */
@Composable
fun AppBackHandler(enabled: Boolean=true, onBack:()->Unit) {
    val predictive=LocalAppPredictiveBack.current&&Build.VERSION.SDK_INT>=34
    val fallback=remember {BackGestureState()}
    val state=LocalBackGestureState.current?:fallback
    val action by rememberUpdatedState(onBack)
    BackHandler(enabled=enabled&&!predictive) {action()}
    PredictiveBackHandler(enabled=enabled&&predictive) { events ->
        val token=++state.generation
        try {
            events.collect { event ->
                if(token==state.generation){
                    state.direction=if(event.swipeEdge==androidx.activity.BackEventCompat.EDGE_RIGHT)-1f else 1f
                    state.progress=event.progress.coerceIn(0f,1f)
                }
            }
            if(token==state.generation){state.progress=0f;action()}
        }catch(cancelled:CancellationException){
            withContext(NonCancellable){
                if(token==state.generation)animate(state.progress,0f,animationSpec=tween(130)){value,_->if(token==state.generation)state.progress=value}
            }
            throw cancelled
        }finally{if(token==state.generation)state.progress=0f}
    }
}
