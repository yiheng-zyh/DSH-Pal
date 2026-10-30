package com.dsh.mobile.core
import kotlinx.serialization.json.*
object QuickReplyTarget {
    fun matchesSession(token:String,session:String):Boolean {
        if(session.isBlank())return true
        return runCatching {
            val selection=Json.parseToJsonElement(token).jsonObject.getValue("context").jsonObject.getValue("selection").jsonPrimitive.content
            selection==session || selection=="session:$session"
        }.getOrDefault(false)
    }
    fun same(a:String,b:String):Boolean = runCatching {
        val x=Json.parseToJsonElement(a).jsonObject;val y=Json.parseToJsonElement(b).jsonObject
        val xc=x.getValue("context").jsonObject;val yc=y.getValue("context").jsonObject
        x.getValue("profile").jsonPrimitive.content.isNotBlank() && x.getValue("profile")==y.getValue("profile") &&
            listOf("origin","path","selection").all {xc.getValue(it).jsonPrimitive.content.isNotBlank() && xc.getValue(it)==yc.getValue(it)}
    }.getOrDefault(false)
}
