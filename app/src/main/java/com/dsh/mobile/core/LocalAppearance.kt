package com.dsh.mobile.core

import kotlin.math.pow
import kotlin.math.roundToInt

/** Existing ids stay stable. Custom ids contain only bounded integers, never CSS input. */
data class LocalAppearance private constructor(
    val id: String, val title: String, val background: String, val surface: String, val accent: String,
) {
    val isCustom get() = id.startsWith("custom:")
    val isLight get() = luminance(background) > .179
    val foreground get() = readableOn(background)
    val muted get() = foreground
    val onAccent get() = readableOn(accent)

    companion object {
        val DSH = LocalAppearance("dsh", "跟随 DSH 背景", "#15161a", "#1e2025", "#4c8dff")
        val GRAPHITE = LocalAppearance("graphite", "石墨灰", "#16181c", "#24272e", "#9ca8bd")
        val OCEAN = LocalAppearance("ocean", "深海蓝", "#101c2d", "#1b2e46", "#65aaff")
        val VIOLET = LocalAppearance("violet", "紫罗兰", "#21182f", "#342646", "#c299ff")
        val FOREST = LocalAppearance("forest", "森林绿", "#122720", "#203a30", "#7bd8af")
        val entries = listOf(DSH, GRAPHITE, OCEAN, VIOLET, FOREST,
            LocalAppearance("mist", "云雾白", "#f2f4f7", "#e8edf3", "#42628c"),
            LocalAppearance("sky", "晴空蓝", "#eaf3fc", "#dceafa", "#28669b"),
            LocalAppearance("mint", "薄荷绿", "#eaf5ef", "#daede2", "#256c52"),
            LocalAppearance("sakura", "樱花粉", "#fbeef3", "#f3dfe8", "#a04468"),
            LocalAppearance("apricot", "暖杏色", "#fbf1e5", "#f2e5d3", "#8c5a23"))

        fun fromId(id: String?): LocalAppearance {
            entries.firstOrNull { it.id == id }?.let { return it }
            val match = Regex("custom:(\\d{1,3}):(\\d{1,3}):(\\d{1,3})").matchEntire(id ?: "") ?: return DSH
            val (h, s, l) = match.destructured
            if (h.toInt() !in 0..360 || s.toInt() !in 0..100 || l.toInt() !in 8..98) return DSH
            return custom(h.toInt(), s.toInt(), l.toInt())
        }

        fun custom(hue: Int, saturation: Int, lightness: Int): LocalAppearance {
            val h = hue.coerceIn(0, 360); val s = saturation.coerceIn(0, 100); val l = lightness.coerceIn(8, 98)
            val background = hsl(h, s, l)
            val light = luminance(background) > .179
            // Keep both surfaces on the same side of the text contrast threshold.
            val candidate = hsl(h,s,(l + if(light) -5 else 6).coerceIn(0,100))
            val surface = if ((luminance(candidate) > .179) == light) candidate
                else hsl(h,s,(l + if(light) 5 else -4).coerceIn(0,100))
            val accent = (if(light) (30 downTo 0) else (78..100)).map { hsl(h,s.coerceIn(45,85),it) }
                .firstOrNull { contrast(it,background)>=4.5 && contrast(it,surface)>=4.5 } ?: readableOn(background)
            return LocalAppearance("custom:$h:$s:$l", "自定义配色", background,
                surface, accent)
        }

        fun readableOn(hex: String) = if (luminance(hex) > .179) "#000000" else "#ffffff"

        fun contrast(a: String, b: String): Double {
            val x=luminance(a); val y=luminance(b)
            return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
        }

        fun luminance(hex: String): Double {
            val rgb = hex.removePrefix("#").toLong(16)
            fun channel(shift: Int): Double {
                val v = ((rgb shr shift) and 255) / 255.0
                return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }

        private fun hsl(h: Int, s: Int, l: Int): String {
            val light = l / 100.0; val a = s / 100.0 * minOf(light, 1 - light)
            fun channel(n: Int): Int {
                val k = (n + h / 30.0) % 12
                return ((light - a * maxOf(-1.0, minOf(k - 3, 9 - k, 1.0))) * 255).roundToInt().coerceIn(0, 255)
            }
            return "#%02x%02x%02x".format(java.util.Locale.ROOT, channel(0), channel(8), channel(4))
        }
    }
}
