package com.dsh.mobile.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Rounded corner grip. The touch target is larger than the visible stroke. */
class CornerResizeHandle(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val arc = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
        setShadowLayer(1.5f * density, 0f, .5f * density, 0x40000000)
    }
    init {
        contentDescription = "拖动右下圆角调整文字框大小"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 6f * density
        val diameter = 38f * density
        arc.set(width - inset - diameter, height - inset - diameter,
            width - inset, height - inset)
        canvas.drawArc(arc, 0f, 90f, false, paint)
    }
}
