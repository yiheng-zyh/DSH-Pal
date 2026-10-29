package com.dsh.mobile.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Reusable motion primitives.
 *
 * These exist so a screen gets the house motion vocabulary by *using a wrapper*,
 * rather than by remembering to hand-wire an `animate*AsState` at every call site.
 * The earlier version of this app declared a curve set and then never applied it
 * outside one status strip — the fix is to make the correct motion the default.
 */

/**
 * Press feedback that scales with the emphasis curve.
 *
 * A touch target that does not acknowledge the press reads as broken on a phone,
 * especially when the tap has any latency. Scaling to 0.96 on a spring is cheap,
 * has no layout cost, and matches HyperOS's own press feel.
 *
 * **Pair this with the element's own `clickable`**, passing the same source:
 *
 * ```kotlin
 * val interaction = remember { MutableInteractionSource() }
 * Modifier
 *     .pressScale(interaction)              // scale only
 *     .clickable(interactionSource = interaction, indication = null, onClick = …)
 * ```
 *
 * An earlier version created its own source *and* an empty `clickable`, which
 * silently swallowed every tap placed after it — so it was never used. This one
 * only renders the scale; it never consumes input.
 *
 * @param interaction the source the element's clickable also uses.
 * @param enabled when false the modifier is inert.
 */
fun Modifier.pressScale(
    interaction: InteractionSource,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    if (!enabled) return@composed this
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.snappy(),
        label = "pressScale",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Convenience for plain rows/buttons that just need press feedback.
 *
 * Owns both the interaction source and the click, so the caller cannot get the
 * pairing wrong. Use this unless the element already has a source.
 */
fun Modifier.pressClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this
        .pressScale(interaction, enabled)
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}

/**
 * Enter/exit for a block that slides up and fades.
 *
 * Entrance decelerates (fast then settles); exit accelerates (slow then gone).
 * Used for inline results, notices and the island strip.
 */
@Composable
fun AnimatedSlideFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(animationSpec = Motion.enter()) { it / 3 } +
            expandVertically(animationSpec = Motion.enter()) +
            fadeIn(Motion.enter()),
        exit = slideOutVertically(animationSpec = Motion.exit()) { it / 3 } +
            shrinkVertically(animationSpec = Motion.exit()) +
            fadeOut(Motion.exit()),
    ) {
        content()
    }
}

/** Enter/exit for a card or badge: scale from slightly small, no sliding. */
@Composable
fun AnimatedScaleFade(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(animationSpec = Motion.expand(), initialScale = 0.92f) + fadeIn(Motion.quick()),
        exit = scaleOut(animationSpec = Motion.exit(), targetScale = 0.96f) + fadeOut(Motion.quick()),
    ) {
        content()
    }
}

/**
 * Run [block] once shortly after first composition, then flip to true.
 *
 * Used to stagger list content in: mount the list immediately (so it is scrollable
 * and laid out), then animate items in a beat later. A literal delay is the only
 * way to get a stagger in Compose without an extra animation clock.
 *
 * @param delayMillis how long to wait before signalling.
 */
@Composable
fun rememberRevealed(delayMillis: Long = 0L): Boolean {
    var revealed by remember { mutableStateOf(delayMillis <= 0L) }
    LaunchedEffect(Unit) {
        if (delayMillis > 0L) {
            delay(delayMillis)
        }
        revealed = true
    }
    return revealed
}

/**
 * Per-item stagger delay for an index in a list.
 *
 * Caps out so a long list does not take a second to appear.
 */
fun staggerDelay(index: Int, stepMillis: Int = 40, maxMillis: Int = 240): Long =
    (index * stepMillis).coerceAtMost(maxMillis).toLong()
