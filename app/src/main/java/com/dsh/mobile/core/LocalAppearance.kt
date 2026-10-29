package com.dsh.mobile.core

enum class LocalAppearance(val id: String, val title: String, val background: String, val surface: String, val accent: String) {
    DSH("dsh", "跟随 DSH 背景", "#15161a", "#1e2025", "#4c8dff"),
    GRAPHITE("graphite", "石墨灰", "#16181c", "#24272e", "#9ca8bd"),
    OCEAN("ocean", "深海蓝", "#101c2d", "#1b2e46", "#65aaff"),
    VIOLET("violet", "紫罗兰", "#21182f", "#342646", "#c299ff"),
    FOREST("forest", "森林绿", "#122720", "#203a30", "#7bd8af");
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: DSH }
}
