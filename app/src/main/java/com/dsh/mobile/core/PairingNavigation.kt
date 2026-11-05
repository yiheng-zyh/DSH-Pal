package com.dsh.mobile.core

import java.net.URI

/** A server's default public QR origin must not steal an explicitly chosen route. */
object PairingNavigation {
    fun selectedOriginLanding(initialUrl:String,redirectUrl:String,isRedirect:Boolean):String? = runCatching {
        if(!isRedirect)return null
        val initial=URI(initialUrl)
        val target=URI(redirectUrl)
        val origin=ConnectionAddress.origin(initialUrl) ?: return null
        val destination=ConnectionAddress.origin(redirectUrl) ?: return null
        if(origin==destination || initial.path !in setOf("/pair-accept","/pair-app"))return null
        if(target.path!="/pair-app" || target.rawFragment!=null)return null
        // Only the plugin's fresh grant redirect, never OAuth, documents, file
        // URLs or a manually entered pairing token. The grant stays on the
        // original user-approved origin, whose server validates it normally.
        val query=target.rawQuery ?: return null
        if(!query.startsWith("grant=") || query.length<=6 || '&' in query)return null
        if(ConnectionAddress.recognizedRemoteKind(redirectUrl)==null)return null
        "$origin/pair-app?$query"
    }.getOrNull()
}
