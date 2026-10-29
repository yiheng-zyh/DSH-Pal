package com.dsh.mobile.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion vocabulary for the whole app.
 *
 * Two rules drive everything here:
 *
 *  1. **No linear motion a human can see.** Linear reads as mechanical. Every
 *     transition uses a non-linear curve: HyperOS/miuix-style emphasized
 *     decelerate for entrances, accelerate for exits, and a critically-damped
 *     spring for anything the user directly manipulates (drag, expand, toggle).
 *  2. **Direction encodes meaning.** Things that appear decelerate (fast in, then
 *     settle); things that leave accelerate (start slow, leave quickly) so the
 *     eye is never waiting on something already gone.
 *
 * Durations follow Material 3 / HyperOS guidance: 150–200 ms for small state
 * changes, 300–400 ms for surfaces that move across the screen.
 */
object Motion {

    // ---- Easing curves -------------------------------------------------------

    /**
     * Emphasized decelerate — the house curve for entrances.
     * Fast start, long soft settle: content "arrives" instead of sliding in.
     */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /**
     * Emphasized accelerate — exits. Slow start, quick departure, so a
     * dismissed surface does not linger in the corner of the eye.
     */
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Standard — symmetric in/out for element-to-element transitions
     * (shared axis, content swap).
     */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * Overshoot — for the island expanding out of its pill, where a small
     * overshoot makes the surface feel physical rather than cut out.
     */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

    /** Gentle in-out for continuous loops (shimmer, pulse, progress sweep). */
    val Gentle: Easing = FastOutSlowInEasing

    val Linear: Easing = LinearEasing

    // ---- Specifications ------------------------------------------------------

    /** Small state change (icon swap, badge, ripple). */
    fun <T> quick() = tween<T>(durationMillis = 160, easing = EmphasizedDecelerate)

    /** Default surface transition (panels, cards, list items). */
    fun <T> standard() = tween<T>(durationMillis = 280, easing = Standard)

    /** Entering content. */
    fun <T> enter() = tween<T>(durationMillis = 320, easing = EmphasizedDecelerate)

    /** Leaving content — slightly faster than entering, deliberately. */
    fun <T> exit() = tween<T>(durationMillis = 220, easing = EmphasizedAccelerate)

    /** Overshooting expansion (island pill -> card). */
    fun <T> expand() = tween<T>(durationMillis = 380, easing = Overshoot)

    /**
     * Spring for anything the finger is on: no duration, no easing, just
     * physics. Critically damped by default so it never wobbles unattended;
     * [bouncy] adds the tiny overshoot that makes a drag feel alive.
     */
    fun <T> physical(bouncy: Boolean = false) = spring<T>(
        dampingRatio = if (bouncy) Spring.DampingRatioMediumBouncy else Spring.DampingRatioNoBouncy,
        stiffness = if (bouncy) Spring.StiffnessMediumLow else Spring.StiffnessMedium,
    )

    /** Snappy spring for toggles and small draggables. */
    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Slow, heavy spring for large surfaces (sheets, island body). */
    fun <T> heavy() = spring<T>(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessLow,
    )

    // ---- Durations -----------------------------------------------------------

    /** Island: pill -> expanded. Slightly slow so the morph is legible. */
    const val ISLAND_EXPAND_MS = 400

    /** Island: expanded -> pill. Faster, because the user is done looking. */
    const val ISLAND_COLLAPSE_MS = 280

    /** Progress bar fill: continuous, linear by nature (a bar must not lie). */
    const val PROGRESS_MS = 550

    /** Stagger step between list items entering. */
    const val STAGGER_MS = 40
}
