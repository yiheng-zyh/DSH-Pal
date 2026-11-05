package com.dsh.mobile.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * One clock, many windows — the stagger primitive.
 *
 * ## Why not `delay()`
 *
 * The previous approach (`rememberRevealed(ms)` + `AnimatedVisibility`) faked a
 * stagger by sleeping before showing each element. Three problems:
 *
 *  - every element owned its own coroutine, so a list of ten items spawned ten
 *    independent effects;
 *  - during the sleep the element was fully absent, so it *popped* in — a delayed
 *    pop, not an animation;
 *  - any recomposition or recreation restarted the wait.
 *
 * A single monotonic clock with per-element windows fixes all three: every element
 * samples the same timeline, so they stay in sync, survive recomposition, and
 * overlap into one wave rather than a queue of pops.
 *
 * This is how a hand-drawn Canvas intro works: one animator drives a single `t`,
 * and each element does `ease(t, start, end)`. That shared origin is what makes
 * such intros feel like one motion instead of a pile of separate tweens.
 */
class Timeline internal constructor(private val clock: Animatable<Float, *>) {

    /**
     * Progress of the [startMillis]..[endMillis] window, eased to 0..1.
     *
     * Reading this inside a composable subscribes it to the clock, so the caller
     * recomposes every frame while the timeline runs.
     */
    fun at(
        startMillis: Int,
        endMillis: Int,
        easing: Easing = Motion.EmphasizedDecelerate,
    ): Float {
        val span = (endMillis - startMillis).coerceAtLeast(1).toFloat()
        val raw = ((clock.value - startMillis) / span).coerceIn(0f, 1f)
        return easing.transform(raw)
    }
}

/**
 * Run a single entrance timeline for [durationMillis], starting on first
 * composition.
 *
 * Keyed on nothing: the timeline plays once per composition. A screen that wants
 * a replay can key its content on a revision counter.
 */
@Composable
fun rememberTimeline(durationMillis: Int = 900): Timeline {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(durationMillis) {
        progress.snapTo(0f)
        // One sweep, no repeat: this is an entrance, not a loop.
        progress.animateTo(
            targetValue = durationMillis.toFloat(),
            animationSpec = tween(durationMillis = durationMillis, easing = Motion.Linear),
        )
    }
    return remember(progress) { Timeline(progress) }
}

/**
 * Stagger window for item [index]: entries overlap into a rolling wave.
 *
 * A strict queue (each item finishing before the next starts) reads as a stutter;
 * overlapping by roughly half the window reads as a single motion.
 */
@Composable
fun rememberStaggerReveal(
    index: Int,
    stepMillis: Int = Motion.STAGGER_MS,
    windowMillis: Int = 340,
    easing: Easing = Motion.EmphasizedDecelerate,
): Float {
    val timeline = rememberTimeline(640)
    val start = (index * stepMillis).coerceAtMost(640 - windowMillis)
    return timeline.at(start, start + windowMillis, easing)
}
