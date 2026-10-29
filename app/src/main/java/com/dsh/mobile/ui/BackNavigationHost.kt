package com.dsh.mobile.ui

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dsh.mobile.core.ExitBackGate
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Text

/** Root-only exit protection. Child handlers retain priority; Home is untouched. */
@Composable
fun BackNavigationHost(
    enabled: Boolean,
    predictive: Boolean,
    consumeBack: ((Boolean) -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    val owner = LocalLifecycleOwner.current
    val gate = remember { ExitBackGate() }
    var hintGeneration by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(false) }
    val gesture=remember {BackGestureState()}
    var pending by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val active by rememberUpdatedState(enabled)
    val consume by rememberUpdatedState(consumeBack)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if(event==Lifecycle.Event.ON_START)foreground=true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground=false
                gate.reset(); hint=false; gesture.reset(); pending=false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled) { if(!enabled) { gate.reset(); hint=false; gesture.reset() } }
    LaunchedEffect(hintGeneration) {
        if(hint) { kotlinx.coroutines.delay(2500); hint=false; gate.reset() }
    }
    fun back() {
        if(pending||!active)return
        pending=true
        consume { consumed ->
            pending=false
            if(!active||!foreground)return@consume
            if(consumed) {gate.reset();hint=false}
            else if(gate.request(SystemClock.elapsedRealtime())) {
                hint=false
                activity?.moveTaskToBack(true)
            } else {hint=true;hintGeneration++}
        }
    }
    val preview = predictive && Build.VERSION.SDK_INT>=34
    BackHandler(enabled=enabled&&!preview) { back() }
    PredictiveBackHandler(enabled=enabled&&preview) { events ->
        try {
            events.collect { gesture.direction=if(it.swipeEdge==androidx.activity.BackEventCompat.EDGE_RIGHT)-1f else 1f; gesture.progress=it.progress.coerceIn(0f,1f) }
            gesture.reset()
            back()
        } catch(cancelled: CancellationException) {
            gesture.reset()
            throw cancelled
        } finally { gesture.reset() }
    }
    Box(Modifier.fillMaxSize().background(com.dsh.mobile.ui.theme.LocalShellSurface.current.background)) {
        CompositionLocalProvider(LocalAppPredictiveBack provides predictive,LocalBackGestureState provides gesture) {
            Box(Modifier.fillMaxSize().backGesturePreview(gesture)) { content() }
        }
        AnimatedVisibility(hint,enter=androidx.compose.animation.fadeIn(),exit=androidx.compose.animation.fadeOut(),modifier=Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)) {
            Text("再次操作则返回桌面",fontSize=13.sp,color=com.dsh.mobile.ui.theme.LocalShellSurface.current.onBackground,
                modifier=Modifier.background(com.dsh.mobile.ui.theme.LocalShellSurface.current.raised,RoundedCornerShape(24.dp)).padding(horizontal=20.dp,vertical=12.dp))
        }
    }
}
