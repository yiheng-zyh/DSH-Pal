package com.dsh.mobile.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** The touch target is larger than the visible resize strokes. */
class CornerResizeHandle(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * density
        setShadowLayer(1.5f * density, 0f, .5f * density, 0x40000000)
    }
    init {
        contentDescription = "拖动右下角调整文字框大小"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width - 9f * density
        val y = height - 9f * density
        val step = 4f * density
        canvas.drawLine(x - 3 * step, y, x, y - 3 * step, paint)
        canvas.drawLine(x - step, y, x, y - step, paint)
    }
}
