package com.dsh.mobile.core

/**
 * 对话缓存正文的加密器接口。
 *
 * 为什么需要：缓存内容是**对话正文与工具输出**，属于本机最敏感的数据之一。
 * 明文落盘在未加密的设备/被备份/被 root 的场景下等于直接泄露。
 * 与 `OverlayReplyDraftStore` 同一套做法：AndroidKeyStore 里的 AES-GCM，
 * 密钥不可导出、不进备份。
 *
 * 这里只定义纯逻辑侧需要的契约，真正的 KeyStore 实现在 Android 层注入，
 * 从而让 [ChatArchiveStore] 的容量/淘汰/完整性逻辑保持可单测（不依赖 Android）。
 */
interface ChatTextCipher {
    /** 加密正文。失败返回 null，由调用方决定是否放弃这次写入。 */
    fun encrypt(plain: ByteArray): ByteArray?

    /** 解密正文。失败返回 null（密钥失效、数据损坏都走这里）。 */
    fun decrypt(stored: ByteArray): ByteArray?

    companion object {
        /** 测试与"无加密能力"降级场景用的直通实现。绝不用于生产落盘。 */
        val NONE: ChatTextCipher = object : ChatTextCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain
            override fun decrypt(stored: ByteArray): ByteArray = stored
        }
    }
}
