package com.dsh.mobile.core

/** Position within available travel, so resizing never overwrites the preferred anchor. */
object OverlayPlacement {
    fun coordinate(anchor:Float,start:Int,end:Int):Int =
        (start+(end-start).coerceAtLeast(0)*anchor.coerceIn(0f,1f)).toInt().coerceIn(start,maxOf(start,end))
    fun anchor(position:Int,start:Int,end:Int):Float =
        if(end<=start)0f else ((position-start).toFloat()/(end-start)).coerceIn(0f,1f)
}
