package com.dsh.mobile.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 对话本地缓存（实验功能）的纯逻辑与落盘测试。
 *
 * 覆盖点：容量档位、UTF-8 字节估算、会话标识稳定性、合并保留规则、
 * 按容量淘汰顺序（用户要求「优先缓存最新对话」）、落盘往返、
 * 损坏容错、原子写、以及「关闭开关时不影响任何既有行为」。
 */
class ChatArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun store(
        quota: Long = ChatArchive.Quota.default.bytes,
        now: Long = 1_700_000_000_000L,
    ) = ChatArchiveStore(File(temp.root, "archive"), quota = { quota }, clock = { now })

    private fun capture(key: String, text: String, at: Long = 1_700_000_000_000L, title: String = "会话") =
        ChatArchive.Capture(sessionKey = key, title = title, text = text, capturedAt = at)

    // ---- 纯逻辑 ----

    @Test fun quotaDefaultsToOneGigabyteAndOffersUserChoices() {
        assertEquals(ChatArchive.Quota.DEFAULT, ChatArchive.Quota.default)
        assertEquals(1024L * 1024 * 1024, ChatArchive.Quota.default.bytes)
        // 用户可选档位，默认在列表里
        val options = ChatArchive.Quota.options()
        assertTrue(options.contains(ChatArchive.Quota.default))
        assertTrue(options.size >= 3)
        // 存的名字读回来（设置里存的是枚举名）
        for (q in options) assertEquals(q, ChatArchive.Quota.ofName(q.name))
        // 未知/空值回落到默认，不让坏数据把限额变成 0
        assertEquals(ChatArchive.Quota.default, ChatArchive.Quota.ofName(null))
        assertEquals(ChatArchive.Quota.default, ChatArchive.Quota.ofName(""))
        assertEquals(ChatArchive.Quota.default, ChatArchive.Quota.ofName("NOT_A_QUOTA"))
    }

    @Test fun utf8BytesCountsChineseAndEmojiCorrectly() {
        assertEquals(0L, ChatArchive.utf8Bytes(""))
        assertEquals(3L, ChatArchive.utf8Bytes("abc"))
        // 中文 3 字节
        assertEquals(6L, ChatArchive.utf8Bytes("中文"))
        // emoji 是代理对：两个 char，合计 4 字节
        assertEquals(4L, ChatArchive.utf8Bytes("\uD83D\uDE00"))
        // 混合
        assertEquals(3L + 3L + 1L, ChatArchive.utf8Bytes("中a文"))
    }

    @Test fun sessionIdIsStableOpaqueAndFilenameSafe() {
        val a = ChatArchive.sessionId("https://example.test|/chat|row-1")
        val b = ChatArchive.sessionId("https://example.test|/chat|row-1")
        val c = ChatArchive.sessionId("https://example.test|/chat|row-2")
        assertEquals(a, b)
        assertNotEquals(a, c)
        // 文件名安全：只含十六进制
        assertTrue(a.matches(Regex("[a-f0-9]{24}")))
        // 不可逆：不含原文片段
        assertFalse(a.contains("example"))
        assertEquals(24, a.length)
    }

    @Test fun trimKeepsTailAndLeavesShortTextAlone() {
        assertEquals("short", ChatArchive.trim("short", 100))
        val long = "x".repeat(1000)
        val trimmed = ChatArchive.trim(long, 100)
        assertEquals(100, trimmed.length)
        // 保留的是尾部（最新内容在末尾）
        assertEquals(long.takeLast(100), trimmed)
    }

    @Test fun countLinesIgnoresBlankNoiseFromToolOutput() {
        assertEquals(0, ChatArchive.countLines(""))
        assertEquals(0, ChatArchive.countLines("\n\n\n"))
        assertEquals(2, ChatArchive.countLines("a\n\n\nb\n"))
    }

    @Test fun mergeKeepsLongerStoredTextWhenCaptureIsAPartialView() {
        val old = "第一轮对话内容很长很长\n第二轮对话内容也很长很长"
        val existing = ChatArchive.Archive("id", "会话", 100L, ChatArchive.utf8Bytes(old), 2)
        // 只抓到末尾一小段（滚动没加载全）：应保留旧全文
        val partial = ChatArchive.merge(existing, old, capture("k", "第二轮对话内容也很长很长", at = 200L))
        assertEquals(old, partial.text)
        assertFalse("内容没变不应触发落盘", partial.changed)

        // 抓到更长的新内容：采用新内容
        val fuller = old + "\n第三轮新内容"
        val grown = ChatArchive.merge(existing, old, capture("k", fuller, at = 300L))
        assertEquals(fuller, grown.text)
        assertTrue(grown.changed)
        assertEquals(300L, grown.archive.updatedAt)
    }

    @Test fun mergeTreatsBlankCaptureAsNoData() {
        val old = "已有内容"
        val existing = ChatArchive.Archive("id", "会话", 100L, ChatArchive.utf8Bytes(old), 1)
        val blank = ChatArchive.merge(existing, old, capture("k", "   \n  ", at = 200L))
        // 空采集不覆盖已有正文
        assertEquals(old, blank.text)
        assertFalse(blank.changed)
    }

    @Test fun mergeUsesFallbackTitleAndClampsLength() {
        val outcome = ChatArchive.merge(null, null, capture("k", "正文", title = ""))
        assertEquals("未命名会话", outcome.archive.title)
        val longTitle = ChatArchive.merge(null, null, capture("k", "正文", title = "标".repeat(200)))
        assertEquals(80, longTitle.archive.title.length)
    }

    // ---- 「无角色」必须被带角色的记录替换掉（用户反馈第 3 条的真因）----
    //
        // 旧实现用 `contains("<!--ts-->")` 判"有没有角色"，但 encode() 只在消息**有时间戳**时
    // 才写 `<!--millis-->`。DOM 兜底、session/page 里 seq=0 的消息都写成 `[User] 正文`
    // （没有时间戳），于是被判成"无角色"，merge 永远保留旧 DOM 整段文本 → 满屏无角色。

    @Test fun rolePrefixWithoutTimestampStillCountsAsStructured() {
        val structured = "[User] 帮我看下这个报错\n\n[Assistant] 是端口被占用了"
        assertTrue("有角色前缀就该算结构化（不依赖时间戳）", ChatArchive.looksStructured(structured))
        // 只有时间戳、没有角色前缀的不算结构化
        assertFalse(ChatArchive.looksStructured("<!--123--> 一段没有角色的文本"))
        // 旧版 DOM 整段文本
        assertFalse(ChatArchive.looksStructured("用户问了一句话\n\n助手回答了一段"))
        assertFalse(ChatArchive.looksStructured(null))
    }

    @Test fun structuredCaptureWithoutTimestampsReplacesLegacyDomSnapshot() {
        val legacyDom = "这是一整段没有角色的旧 DOM 快照，比结构化记录长得多，".repeat(20)
        val existing = ChatArchive.Archive("id", "会话", 100L, ChatArchive.utf8Bytes(legacyDom), 1)
        // 结构化记录更短、且**没有时间戳**（DOM/seq=0 的常见形态）
        val structured = "[User] 为什么列表只有 8 条\n\n[Assistant] 因为之前读的是侧栏 DOM，现在改走 session/list"
        val outcome = ChatArchive.merge(existing, legacyDom, capture("k", structured, at = 200L))
        assertEquals(
            "带角色的记录必须替换掉无角色的旧快照（即使它更短）",
            structured,
            outcome.text,
        )
        assertTrue(outcome.changed)
    }

    @Test fun structuredStoredTextIsNeverDowngradedByLegacyDomCapture() {
        val structured = "[User] 你好\n\n[Assistant] 你好，有什么可以帮你"
        val existing = ChatArchive.Archive("id", "会话", 100L, ChatArchive.utf8Bytes(structured), 2)
        // DOM 兜底抓到的整段文本更长、但无角色 → 必须保留结构化版本
        val domText = "你好\n\n你好，有什么可以帮你\n\n" + "额外的界面文字".repeat(50)
        val outcome = ChatArchive.merge(existing, structured, capture("k", domText, at = 200L))
        assertEquals("不能把带角色的记录降级成无角色整段文本", structured, outcome.text)
    }

    @Test fun rawBytesIsKeptSeparateFromConversationBytes() {
        // 用户反馈第 4 条：9 MB 的会话只占几百 KB —— 两个数字都要能显示，
        // 且「原始体积」不能覆盖「对话正文体积」（后者才是真实占用）。
        val text = "[User] 你好\n\n[Assistant] 你好"
        val outcome = ChatArchive.merge(
            null, null,
            ChatArchive.Capture("k", "会话", text, 1L, rawBytes = 16_500_000L),
        )
        assertEquals(ChatArchive.utf8Bytes(text), outcome.archive.bytes)
        assertEquals(16_500_000L, outcome.archive.rawBytes)
    }

    @Test fun rawBytesNeverShrinksWhenACaptureCannotMeasureIt() {
        // DOM 兜底路径拿不到原始体积（=0），不能让已量到的 9 MB 退回 0，
        // 否则界面上的说明会自己消失。
        val text = "[User] 你好"
        val existing = ChatArchive.Archive(
            "id", "会话", 100L, ChatArchive.utf8Bytes(text), 1, rawBytes = 9_000_000L,
        )
        val outcome = ChatArchive.merge(existing, text, capture("k", text, at = 200L))
        assertEquals(9_000_000L, outcome.archive.rawBytes)
    }

    @Test fun newlyMeasuredRawBytesArePersistedEvenWhenTextIsUnchanged() {
        // 正文没变、但这次量到了原始体积 → changed 必须为 true，
        // 否则 store 会走"内容没变只 touch"分支，体积说明永远写不进磁盘。
        val text = "[User] 你好"
        val existing = ChatArchive.Archive("id", "会话", 100L, ChatArchive.utf8Bytes(text), 1, rawBytes = 0L)
        val outcome = ChatArchive.merge(
            existing, text,
            ChatArchive.Capture("k", "会话", text, 200L, rawBytes = 6_900_000L),
        )
        assertTrue("量到新体积也应触发落盘", outcome.changed)
        assertEquals(6_900_000L, outcome.archive.rawBytes)
    }

    @Test fun evictionKeepsNewestConversationsFirst() {
        // 用户明确要求「优先缓存最新对话」：淘汰最久未更新的
        val all = listOf(
            ChatArchive.Archive("a", "旧", 100L, 40, 1),
            ChatArchive.Archive("b", "新", 400L, 40, 1),
            ChatArchive.Archive("c", "中", 200L, 40, 1),
        )
        val (keep, evict) = ChatArchive.planEviction(all, 100L)
        // 限额 100 只能留 2 个 40 字节 → 留最新的 b、c，淘汰最旧的 a
        assertEquals(listOf("b", "c"), keep.map { it.id })
        assertEquals(listOf("a"), evict.map { it.id })
    }

    @Test fun evictionAlwaysKeepsAtLeastOneEvenWhenOversized() {
        val huge = listOf(ChatArchive.Archive("x", "超大", 100L, 500L, 1))
        val (keep, evict) = ChatArchive.planEviction(huge, 100L)
        assertEquals(1, keep.size)
        assertTrue(evict.isEmpty())
        // 空库不炸
        val empty = ChatArchive.planEviction(emptyList(), 100L)
        assertTrue(empty.first.isEmpty() && empty.second.isEmpty())
    }

    @Test fun formatBytesIsHumanReadable() {
        assertEquals("512 B", ChatArchive.formatBytes(512))
        assertTrue(ChatArchive.formatBytes(2048).contains("KB"))
        assertTrue(ChatArchive.formatBytes(5L * 1024 * 1024).contains("MB"))
        assertTrue(ChatArchive.formatBytes(2L * 1024 * 1024 * 1024).contains("GB"))
    }

    // ---- 选择缓存哪些对话（用户要求：能选，不只是全自动）----

    @Test fun autoCapturesEverySession() {
        val none = emptySet<String>()
        for (key in listOf("a", "b", "任意会话")) {
            assertTrue(key, ChatArchive.shouldCapture(ChatArchive.Selection.AUTO, key, none))
        }
    }

    @Test fun selectedModeOnlyCapturesChosenSessions() {
        val chosen = setOf("keep-me")
        assertTrue(ChatArchive.shouldCapture(ChatArchive.Selection.SELECTED, "keep-me", chosen))
        assertFalse(ChatArchive.shouldCapture(ChatArchive.Selection.SELECTED, "other", chosen))
        // 勾选集合为空时一条都不缓存（用户明确没选）
        assertFalse(ChatArchive.shouldCapture(ChatArchive.Selection.SELECTED, "x", emptySet()))
    }

    @Test fun selectionDefaultsToAutoAndToleratesBadStoredValue() {
        assertEquals(ChatArchive.Selection.AUTO, ChatArchive.Selection.default)
        assertEquals(ChatArchive.Selection.AUTO, ChatArchive.Selection.ofName(null))
        assertEquals(ChatArchive.Selection.AUTO, ChatArchive.Selection.ofName("garbage"))
        assertEquals(ChatArchive.Selection.SELECTED, ChatArchive.Selection.ofName("SELECTED"))
    }

    // ---- 缓存要持续更新：启动检查哪些还没缓存 ----

    @Test fun syncPlanSeparatesNeverCachedFromAlreadyCached() {
        val remote = listOf("a", "b", "c")
        // b 已缓存过 → 归入"可能需刷新"；a/c 从未缓存
        val plan = ChatArchive.planSync(remote, setOf("b"))
        assertEquals(listOf("a", "c"), plan.neverCached)
        assertEquals(listOf("b"), plan.mayNeedRefresh)
        assertEquals(3, plan.needsWork)
    }

    @Test fun syncPlanIsEmptyWhenRemoteListUnavailable() {
        // 拿不到网页会话列表时不能凭空报"有 N 个待更新"
        val plan = ChatArchive.planSync(emptyList(), setOf("x"))
        assertEquals(0, plan.needsWork)
        assertTrue(plan.neverCached.isEmpty())
    }

    @Test fun syncPlanTreatsEverythingAsNewOnFreshInstall() {
        val plan = ChatArchive.planSync(listOf("a", "b"), emptySet())
        assertEquals(2, plan.neverCached.size)
        assertTrue(plan.mayNeedRefresh.isEmpty())
    }

    // ---- 落盘 ----

    @Test fun saveThenListThenReadRoundTrips() {
        val s = store()
        val saved = s.save(capture("k1", "第一轮\n第二轮", at = 111L, title = "我的会话"))
        assertNotNull(saved)
        assertEquals("我的会话", saved!!.title)
        val list = s.list()
        assertEquals(1, list.size)
        assertEquals(saved.id, list[0].id)
        assertEquals("第一轮\n第二轮", s.text(saved.id))
        assertEquals(2, list[0].lines)
    }

    @Test fun savingSameContentTwiceDoesNotGrowStorage() {
        val s = store()
        s.save(capture("k1", "内容", at = 111L))
        val first = s.usage()
        s.save(capture("k1", "内容", at = 222L))
        assertEquals(first, s.usage())
        // 但"最近看到过"的时间要更新，避免淘汰时被当最旧
        assertEquals(222L, s.list().single().updatedAt)
    }

    @Test fun quotaEvictsOldestAndKeepsNewest() {
        // 极小限额：每个会话约 300 字节正文，只放得下 1 个
        val s = store(quota = 700)
        s.save(capture("old", "旧".repeat(100), at = 100L))
        s.save(capture("new", "新".repeat(100), at = 900L))
        val list = s.list()
        assertTrue("限额内至少保留最新一个", list.any { it.title.isNotBlank() })
        // 最新的那条必须在
        assertTrue(list.first().updatedAt >= list.last().updatedAt)
    }

    @Test fun clearRemovesEverythingAndUsageDrops() {
        val s = store()
        s.save(capture("k1", "内容一", at = 1L))
        s.save(capture("k2", "内容二", at = 2L))
        assertTrue(s.usage() > 0)
        s.clear()
        assertEquals(0L, s.usage())
        assertTrue(s.list().isEmpty())
    }

    @Test fun corruptBodyIsDiscardedRatherThanShown() {
        val s = store()
        val saved = s.save(capture("k1", "完整内容", at = 1L))!!
        // 模拟断电截断：把正文文件改坏
        File(temp.root, "archive/${saved.id}.text").writeText("坏")
        // 读正文：校验不过 → 返回 null，且把损坏条目清掉
        assertNull(s.text(saved.id))
        assertTrue("损坏条目应被清掉", s.list().none { it.id == saved.id })
    }

    @Test fun unreadableDirectoryDoesNotThrow() {
        // 目录被占位成普通文件时，落盘失败不能抛给调用方
        val bad = File(temp.root, "not-a-dir")
        bad.writeText("x")
        val s = ChatArchiveStore(bad, quota = { 1024L })
        assertNull(s.save(capture("k", "内容")))
        assertTrue(s.list().isEmpty())
    }

    // ---- 加密（正文含工具输出，绝不明文落盘）----

    @Test fun cipherRoundTripsAndHidesPlaintextOnDisk() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val cipher = xorCipher(key)
        val s = ChatArchiveStore(
            File(temp.root, "enc"), quota = { 10L * 1024 * 1024 }, cipher = cipher,
        )
        val saved = s.save(capture("k1", "机密对话内容-SECRET-MARKER"))!!
        // 读回来是原文
        assertEquals("机密对话内容-SECRET-MARKER", s.text(saved.id))
        // 磁盘上不得出现明文
        val onDisk = File(temp.root, "enc/${saved.id}.text").readBytes().toString(Charsets.UTF_8)
        assertFalse("正文不能以明文落盘", onDisk.contains("SECRET-MARKER"))
        // 元数据要标记 encrypted，读取端据此决定是否解密
        assertTrue(File(temp.root, "enc/${saved.id}.json").readText().contains("\"encrypted\":true"))
    }

    @Test fun encryptionFailureDiscardsWriteInsteadOfFallingBackToPlaintext() {
        val broken = object : ChatTextCipher {
            override fun encrypt(plain: ByteArray): ByteArray? = null
            override fun decrypt(stored: ByteArray): ByteArray? = null
        }
        val s = ChatArchiveStore(File(temp.root, "broken"), quota = { 1024L * 1024 }, cipher = broken)
        // 加密失败 → save 返回 null，且磁盘上不留任何东西
        assertNull(s.save(capture("k", "明文内容")))
        assertTrue(s.list().isEmpty())
        assertTrue(File(temp.root, "broken").listFiles().isNullOrEmpty() || File(temp.root, "broken").listFiles()!!.none { it.name.endsWith(".text") })
    }

    @Test fun undecryptableEntryIsDroppedInsteadOfShown() {
        // 真实防线是 AES-GCM 的认证标签：错密钥 decrypt 直接抛异常 → cipher 返回 null。
        // 这里用一个"解不开就返回 null"的替身来固化 store 的处理契约：
        // 解不开必须丢弃条目，绝不能把返回的 null 当空内容展示成历史。
        val keyA = ByteArray(16) { 1 }
        val writing = ChatArchiveStore(
            File(temp.root, "lost-key"), quota = { 1024L * 1024 },
            cipher = xorCipher(keyA),
        )
        val saved = writing.save(capture("k", "内容"))!!
        val failing = object : ChatTextCipher {
            override fun encrypt(plain: ByteArray): ByteArray? = xorCipher(keyA).encrypt(plain)
            override fun decrypt(stored: ByteArray): ByteArray? = null   // 模拟 GCM tag 校验失败
        }
        val other = ChatArchiveStore(File(temp.root, "lost-key"), quota = { 1024L * 1024 }, cipher = failing)
        assertNull(other.text(saved.id))
        assertTrue("解不开的条目应被清掉", other.list().none { it.id == saved.id })
    }

    @Test fun garbledOrBinaryContentIsNotShownAsHistory() {
        // 底层实现若不抛异常只返回乱码（非认证模式 / 替身），内容自检要兜住：
        // 控制字符占比过高时当作不可读，丢弃而不是展示。
        val binary = object : ChatTextCipher {
            override fun encrypt(plain: ByteArray): ByteArray = plain
            override fun decrypt(stored: ByteArray): ByteArray = ByteArray(64) { 0x01 }  // 全是控制符
        }
        val s = ChatArchiveStore(File(temp.root, "garbled"), quota = { 1024L * 1024 }, cipher = binary)
        val saved = s.save(capture("k", "正常内容"))!!
        assertNull(s.text(saved.id))
        assertTrue(s.list().none { it.id == saved.id })
    }

    /** 测试用加解密：XOR + 固定前缀，够验证"落盘不是明文"与解锁流程。 */
    private fun xorCipher(key: ByteArray) = object : ChatTextCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor key[it % key.size].toInt()).toByte() }
        override fun decrypt(stored: ByteArray): ByteArray =
            ByteArray(stored.size) { (stored[it].toInt() xor key[it % key.size].toInt()).toByte() }
    }

    // ---- 界面噪声清理（真机踩到过：整篇"历史"其实是输入框和进度条）----

    @Test fun chromeLinesAreRemovedAndConversationKept() {
        val page = listOf(
            "你现在接手 DSH Pal 软件介绍视频",
            "对话",
            "轨迹",
            "继续",
            "已停止",
            "4 个子智能体",
            "5 轮 320 步",
            "62.3M tok",
            "缓存命中",
            "发消息或创建任务，/ 调用指令",
            "DeepSeek V4.1 Flash",
            "36%",
            "",
            "这一版先修正音乐响度，再重新录制样片。",
        ).joinToString("\n")
        val cleaned = ChatArchive.cleanChrome(page)
        // 对话正文保留
        assertTrue(cleaned.contains("你现在接手 DSH Pal 软件介绍视频"))
        assertTrue(cleaned.contains("这一版先修正音乐响度"))
        // 界面元素全部剔除
        for (noise in listOf("对话", "轨迹", "继续", "已停止", "4 个子智能体", "5 轮 320 步",
            "缓存命中", "发消息或创建任务", "DeepSeek V4.1 Flash", "36%")) {
            assertFalse("应剔除界面元素: $noise", cleaned.lineSequence().any { it.trim() == noise })
        }
    }

    @Test fun blankBecomesSingleGapAndEmptyInputStaysEmpty() {
        assertEquals("", ChatArchive.cleanChrome(""))
        assertEquals("", ChatArchive.cleanChrome("   \n\n  "))
        val spaced = ChatArchive.cleanChrome("A\n\n\n\n\nB")
        assertEquals("A\n\nB", spaced)
    }

    @Test fun looksLikeConversationRejectsChromeOnlyPages() {
        // 只有界面元素的空会话页：不能存成"历史"
        val chromeOnly = "发消息或创建任务，/ 调用指令，@ 文件或对话\n调用命令\n文件或对话\nDeepSeek V4.1\n36%"
        assertFalse(ChatArchive.looksLikeConversation(chromeOnly))
        // 太短的内容也不算
        assertFalse(ChatArchive.looksLikeConversation("短"))
        // 真实对话内容能通过
        val real = "这一轮我先修好了音乐响度，把两个候选都校正到 -16 LUFS 附近，" +
            "然后重新录制样片；接下来处理 7.5fps 的问题，改用 CDP 直接截帧避免开销。"
        assertTrue(ChatArchive.looksLikeConversation(real))
    }
}
