package com.dsh.mobile.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/**
 * Re-typeface Miuix's type scale onto MiSans without touching any sizes.
 *
 * Miuix ships a 14-role type scale (main, paragraph, body1/2, button,
 * footnote1/2, headline1/2, subtitle, title1-4) with carefully tuned sizes and
 * line heights. We want the *font* to be MiSans while keeping every metric the
 * design system chose — overriding sizes would undo HyperOS's own rhythm.
 *
 * `TextStyles.copy(...)` makes all 14 parameters optional, so we copy the current
 * scale and override only `fontFamily` per role, leaving
 * size/lineHeight/letterSpacing exactly as the design system set them.
 */
private fun TextStyles.withMiSansFamily(): TextStyles {
    val family = DshFontFamily
    fun TextStyle.typed(): TextStyle =
        if (fontFamily == family) this else copy(fontFamily = family)

    return copy(
        main = main.typed(),
        paragraph = paragraph.typed(),
        body1 = body1.typed(),
        body2 = body2.typed(),
        button = button.typed(),
        footnote1 = footnote1.typed(),
        footnote2 = footnote2.typed(),
        headline1 = headline1.typed(),
        headline2 = headline2.typed(),
        subtitle = subtitle.typed(),
        title1 = title1.typed(),
        title2 = title2.typed(),
        title3 = title3.typed(),
        title4 = title4.typed(),
    )
}

/**
 * Build the MiSans-retyped scale from Miuix's defaults.
 *
 * `defaultTextStyles()` is the public factory Miuix itself uses for `MiuixTheme`'s
 * default argument, so starting from it keeps us on exactly the same sizes the
 * design system ships — and keeps us off the internal `LocalTextStyles`.
 *
 * Remembered because `defaultTextStyles()` allocates 14 `TextStyle`s, and the
 * result is constant for the process.
 */
@Composable
fun rememberMiSansTextStyles(): TextStyles = remember {
    defaultTextStyles().withMiSansFamily()
}
