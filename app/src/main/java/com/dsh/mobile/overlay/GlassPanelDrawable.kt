package com.dsh.mobile.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable

/** Translucent tint and a glass rim; no capture or blur of other applications. */
class GlassPanelDrawable(private val density:Float):GradientDrawable() {
    var material:Int=0
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    override fun draw(canvas:Canvas){
        super.draw(canvas)
        if(material!=1)return
        val b=bounds
        val inset=1.5f*density
        rect.set(b.left+inset,b.top+inset,b.right-inset,b.bottom-inset)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*density
        paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
            intArrayOf(Color.WHITE,0x40FFFFFF,0x10FFFFFF,0xDFFFFFFF.toInt()),
            floatArrayOf(0f,.3f,.65f,1f),Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,24*density,24*density,paint)
        paint.shader=null
    }
}
