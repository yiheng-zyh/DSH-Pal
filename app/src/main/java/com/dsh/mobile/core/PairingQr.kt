package com.dsh.mobile.core

import java.net.URI
import java.net.URLDecoder

/** Decode a DSH pairing QR without requiring the user to guess its transport. */
object PairingQr {
    data class Entry(val url: String, val kind: ConnectionProfile.Kind)

    fun parse(raw: String, usbDevelopment: Boolean = false): Entry {
        val scanned = raw.trim()
        require(scanned.length in 1..8192) { "请扫描 DSH 远程访问面板的配对二维码" }
        val wrapper = URI(scanned)
        val candidate = if (wrapper.scheme.equals("dshmobile", ignoreCase = true)) {
            require(wrapper.host == "open" && wrapper.userInfo == null)
            val values = wrapper.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "url" }
            require(values.size == 1)
            URLDecoder.decode(values.single().substringAfter('=', ""), "UTF-8")
        } else scanned
        val pairingUrl = candidate.trim()
        val base = ConnectionAddress.origin(pairingUrl) ?: error("二维码没有有效的 DSH 地址")
        require(MeshPairing.token(pairingUrl) != null) { "请扫描带配对信息的 DSH 二维码" }
        val kind = ConnectionAddress.infer(base) ?: error("二维码地址暂不支持")
        require(kind != ConnectionProfile.Kind.USB || usbDevelopment) { "USB 入口需要开发版" }
        ConnectionProfile.create("", kind, pairingUrl)
        return Entry(pairingUrl, kind)
    }
}
