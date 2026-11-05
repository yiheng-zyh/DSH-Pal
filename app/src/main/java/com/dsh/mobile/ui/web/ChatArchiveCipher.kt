package com.dsh.mobile.ui.web

import com.dsh.mobile.core.ChatTextCipher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 对话缓存正文的 AndroidKeyStore 加密（AES-256-GCM）。
 *
 * 与 `OverlayReplyDraftStore` 同一套做法：密钥生成在 AndroidKeyStore 内、不可导出，
 * 不参与云备份，换机后旧密文自然不可解（这正是期望行为：对话正文不跟着备份走）。
 *
 * 每次加密用系统生成的新 IV，IV 前置于密文，格式为 `[12字节 IV][密文+GCM tag]`。
 */
class ChatArchiveCipher(private val alias: String = "dsh.chat.archive.v1") : ChatTextCipher {
    @Volatile private var usable = true

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray? {
        if (!usable) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            iv + cipher.doFinal(plain)
        }.getOrElse {
            // 设备无 KeyStore（极旧 ROM）或密钥不可用：直接放弃缓存，绝不降级成明文。
            usable = false
            null
        }
    }

    override fun decrypt(stored: ByteArray): ByteArray? {
        if (!usable || stored.size <= IV_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, IV_LENGTH)))
            cipher.doFinal(stored.copyOfRange(IV_LENGTH, stored.size))
        }.getOrNull()
    }

    private companion object {
        const val IV_LENGTH = 12
    }
}
