package com.dsh.mobile.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.dsh.mobile.R

/**
 * MiSans — Xiaomi's own typeface, the one HyperOS actually ships.
 *
 * Licensing: MiSans is free for commercial use worldwide and may be embedded in
 * software, provided the software states that MiSans is used. That notice lives
 * in `AboutLibraries` output and in the README's acknowledgements, and the licence
 * text ships next to the font files in `res/font/`.
 *
 * Why MiSans over 兰亭 Pro: MiSans is the current HyperOS default (兰亭 is the
 * older MIUI-era face), it is distributed by Xiaomi for exactly this purpose, and
 * it covers the CJK range with matching Latin metrics — so Chinese text and the
 * Latin bits (URLs, tool names, step counters) share one consistent rhythm
 * instead of two clashing ones.
 *
 * Weights: only three are bundled. Miuix requests Light/Regular/Medium/Bold;
 * Compose synthesises the rest from the nearest declared weight, which is
 * indistinguishable at UI sizes and keeps the APK ~15 MB smaller than shipping
 * all nine weights.
 */
val MiSans = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold),
    Font(R.font.misans_semibold, FontWeight.Bold),
)

/**
 * Fallback chain for text the bundled file cannot cover.
 *
 * MiSans covers CJK + Latin; anything outside that (emoji, rare scripts) falls to
 * the platform default, which is what Android would have used anyway.
 */
val DshFontFamily: FontFamily = MiSans
