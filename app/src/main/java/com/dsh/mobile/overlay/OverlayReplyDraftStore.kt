package com.dsh.mobile.overlay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent device-local key. No sharing with VPN credentials or backups. */
class OverlayReplyDraftStore(context:Context) {
    data class Draft(val text:String="",val target:String="",val uncertain:Boolean=false)
    private val prefs=context.getSharedPreferences("dsh_overlay_draft",Context.MODE_PRIVATE)
    private val alias="dsh.overlay.draft.v1"
    var available=true; private set
    @Synchronized private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load():Draft {
        val raw=prefs.getString("encrypted",null) ?: return Draft()
        return runCatching {
            val saved=JSONObject(raw);val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(saved.getString("iv"),Base64.NO_WRAP)))
            val data=JSONObject(String(cipher.doFinal(Base64.decode(saved.getString("data"),Base64.NO_WRAP)),Charsets.UTF_8))
            Draft(data.optString("text").take(8000),data.optString("target"),data.optBoolean("uncertain"))
        }.getOrElse {available=false;Draft()}
    }
    @Synchronized fun save(draft:Draft):Boolean {
        if(!available)return false
        return runCatching {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
            val raw=JSONObject().put("text",draft.text).put("target",draft.target).put("uncertain",draft.uncertain).toString().toByteArray(Charsets.UTF_8)
            val saved=JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
                .put("data",Base64.encodeToString(cipher.doFinal(raw),Base64.NO_WRAP))
            prefs.edit().putString("encrypted",saved.toString()).commit()
        }.getOrDefault(false)
    }
}
