package com.dsh.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Semantic colours the design system has no role for: the task lifecycle states
 * the island and the status strip paint.
 *
 * These are *meaning* colours, not surface colours. Surfaces (background, cards,
 * text) always come from [MiuixTheme] so the app follows the system light/dark
 * setting exactly like every other HyperOS app. Mixing the two was a real bug:
 * hard-coding a dark page background while Miuix supplied light-theme text made
 * every headline invisible in light mode.
 */
@Immutable
data class DshAccentColors(
    /** Brand accent, used for the running state and primary actions. */
    val accent: Color,
    /** Thinking / reasoning in progress. */
    val thinking: Color,
    /** Waiting on the user — the one state that must grab attention. */
    val waiting: Color,
    /** Completed successfully. */
    val success: Color,
    /** Failed. */
    val error: Color,
) {
    /** Accent colour for a task phase, or [accent] when idle. */
    fun forPhase(phase: com.dsh.mobile.core.TaskState.Phase): Color = when (phase) {
        com.dsh.mobile.core.TaskState.Phase.Waiting -> waiting
        com.dsh.mobile.core.TaskState.Phase.Thinking -> thinking
        com.dsh.mobile.core.TaskState.Phase.Done -> success
        com.dsh.mobile.core.TaskState.Phase.Failed -> error
        else -> accent
    }
}

/** The single light/dark-independent accent set. */
val DshAccents = DshAccentColors(
    accent = Color(0xFF4C8DFF),
    thinking = Color(0xFF8B7BFF),
    waiting = Color(0xFFFFA23E),
    success = Color(0xFF3DD68C),
    error = Color(0xFFFF5D5D),
)

val LocalDshAccents = staticCompositionLocalOf { DshAccents }

/**
 * Surface colours that must match the WebView.
 *
 * The WebView paints the DSH page, whose own background is dark. The native
 * chrome around it therefore has to be dark too, otherwise there is a visible
 * band of light UI above a dark page — but only for the *shell*; the onboarding
 * and diagnostics screens use Miuix surfaces so they follow the system theme.
 */
@Immutable
data class ShellSurfaceColors(
    val background: Color,
    val raised: Color,
    val onBackground: Color,
    val onBackgroundMuted: Color,
)

private val DarkShell = ShellSurfaceColors(
    background = Color(0xFF15161A),
    raised = Color(0xFF1E2025),
    onBackground = Color(0xFFECEDEF),
    onBackgroundMuted = Color(0xFF9AA0A8),
)

/**
 * Light shell surfaces for the pre-connection screens.
 *
 * The boot splash and the pairing screen are the app's shop window: they are seen
 * on a bright home screen, next to other light HyperOS apps. A near-black splash
 * there reads as "something failed to load", and it drags the system status bar to
 * white-on-black so the clock and battery icons become hard to read against the
 * system's own light chrome.
 */
val LightShell = ShellSurfaceColors(
    background = Color(0xFFF5F7FA),
    raised = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1D22),
    onBackgroundMuted = Color(0xFF6B7280),
)

val LocalShellSurface = staticCompositionLocalOf { DarkShell }

/**
 * Wrap [content] in the Miuix design system.
 *
 * [MiuixTheme] provides colours, typography and component defaults for both light
 * and dark mode; the app adds only the semantic accents above. Nothing here
 * hard-codes a page background — screens take their background from the theme.
 *
 * @param forceDark shell surfaces stay dark regardless of the system theme,
 *   because the hosted WebView page is dark. Used by the paired shell only.
 */
@Composable
fun DshTheme(
    forceDark: Boolean = false,
    appearance:com.dsh.mobile.core.LocalAppearance = com.dsh.mobile.core.LocalAppearance.DSH,
    content: @Composable () -> Unit,
) {
    // MiuixTheme takes the type scale as a public parameter, so MiSans is applied
    // here rather than through the (internal) LocalTextStyles provider.
    val custom=appearance!=com.dsh.mobile.core.LocalAppearance.DSH
    val bg=Color(android.graphics.Color.parseColor(appearance.background))
    val surface=Color(android.graphics.Color.parseColor(appearance.surface))
    val accent=Color(android.graphics.Color.parseColor(appearance.accent))
    val colors=if(custom)top.yukonga.miuix.kmp.theme.darkColorScheme(
        primary=accent,onPrimary=bg,primaryVariant=accent,
        background=bg,surface=surface,surfaceVariant=surface,
        surfaceContainer=surface,surfaceContainerHigh=surface,surfaceContainerHighest=surface,
        primaryContainer=accent.copy(alpha=.18f),secondaryContainer=surface,
        secondaryContainerVariant=surface,tertiaryContainer=surface,
        outline=accent.copy(alpha=.3f),dividerLine=accent.copy(alpha=.12f),
    ) else if(forceDark||isSystemInDarkTheme())top.yukonga.miuix.kmp.theme.darkColorScheme() else top.yukonga.miuix.kmp.theme.lightColorScheme()
    MiuixTheme(colors=colors,textStyles = rememberMiSansTextStyles()) {
        CompositionLocalProvider(
            top.yukonga.miuix.kmp.theme.LocalContentColor provides colors.onBackground,
            LocalDshAccents provides if(custom)DshAccents.copy(accent=accent,thinking=accent)else DshAccents,
            LocalShellSurface provides if(custom)ShellSurfaceColors(bg,surface,colors.onBackground,colors.onBackgroundVariant)else DarkShell,
        ) {
            content()
        }
    }
}

/** Whether the system is currently in dark mode (re-exported for screens). */
@Composable
fun isDarkTheme(): Boolean = isSystemInDarkTheme()
