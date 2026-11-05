package com.dsh.mobile.overlay

import android.content.Context
import android.graphics.Color
import android.widget.TextView

/** A local glyph halo protects text without making the whole panel opaque. */
class GlassTextView(context:Context):TextView(context){
    var glass:Boolean=false
        set(value){
            if(field==value)return
            field=value
            val d=resources.displayMetrics.density
            setShadowLayer(if(value)2f*d else 0f,0f,.5f*d,Color.BLACK)
        }
}
