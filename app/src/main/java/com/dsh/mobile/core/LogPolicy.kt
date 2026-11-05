package com.dsh.mobile.core

/**
 * What a log line is about, and how much room it gets in the ring buffer.
 *
 * The categories are user-facing (the diagnostics screen offers one switch per
 * category), so a tag that maps to the wrong category is either filtered out of
 * an export the user asked for, or silently dropped by the quota engine. The
 * mapping below is the single source of truth for both.
 */
enum class LogCategory(val title:String,val purpose:String) {
    CONNECTION("连接与配对","定位配对失败、VPN 授权与连接切换"),
    NETWORK("网络与请求","定位断网、超时和业务请求失败"),
    RESOURCES("页面与资源","定位加载卡住、缓存和插件资源问题"),
    WEB("网页交互","定位网页脚本、输入及通知回复问题"),
    NOTIFICATIONS("通知与任务","定位通知、灵动岛和任务状态问题"),
    APP("应用与异常","定位启动、后台生命周期和崩溃");
    companion object {
        /**
         * Map a tag to a category. Tags are lower-cased first, so the keys here are
         * lower-case literals only — `forTag("MixedContent")` works, `forTag("mixedcontent")`
         * is the same thing.
         *
         * The new observability tags from the diagnostics contract land here:
         *  - `Stage` / `Nav` / `Dom` / `Res` / `Inject` → RESOURCES (page load progress)
         *  - `Probe` / `Diag` / `NetEnv` → NETWORK (native reachability evidence)
         *  - `Console` / `JSError` / `MixedContent` → WEB (what the page itself reported)
         */
        fun forTag(tag:String)=when(tag.lowercase()) {
            "connection","pair","vpn","permission","qrscan" -> CONNECTION
            "network","health","net","probe","diag","netenv" -> NETWORK
            "cache","load","webview","stage","nav","dom","res","inject" -> RESOURCES
            "bridge","console","reply","layout","ui","files","media","file","download",
            "keyboard","motion","jserror","mixedcontent" -> WEB
            "service","island","task","action","notification" -> NOTIFICATIONS
            else -> APP
        }
    }
}

/**
 * One buffer slot as the quota engine sees it.
 *
 * Kept free of [RunLog.Entry] on purpose: the eviction rule is the part most
 * likely to be got wrong, so it is tested here on the JVM instead of through the
 * Android-dependent buffer.
 *
 * [repetitive] marks a critical line that is re-emitted with near-identical
 * content (only `Nav: pending`, which repeats every 2 s while a navigation is
 * stalled). Shedding it inside its own category before other evidence is what
 * keeps a long stall stream from evicting the `Stage:`/`Probe:` lines that explain it.
 */
data class LogSlot(val critical: Boolean, val category: LogCategory, val repetitive: Boolean = false)

object LogPolicy {
    /**
     * Ring-buffer capacity.
     *
     * Raised from 400 to 600 because the navigation stage machine alone adds a
     * bounded burst per attempt (`Stage:` x ≤ 10, `Nav: pending` x ≤ 45 at 2 s
     * intervals, `Res`/`Dom`/`Console`/`JSError`/`Inject`/`Probe`/`Diag`), and at
     * 400 those lines pushed each other out: a 90-second stall filled the buffer
     * with `Nav: pending` and evicted the `Probe: dns-fail` line that explained
     * it. 600 entries x ~250 bytes is ~150 KB, which the quota engine below
     * protects category by category, so the extra room cannot be spent entirely
     * by one chatty source.
     */
    const val MAX_ENTRIES=600
    const val MAX_MESSAGE_CHARS=2048

    /**
     * Per-run file cap.
     *
     * Re-measured for the new instrumentation. A *successful* connection adds
     * roughly 15 lines beyond the old set (10 `Stage:` transitions, 1 `Res:`
     * summary, 1 `Dom:` snapshot, 3 `Diag:` targets); at ~180 bytes per rendered
     * line that is ~2.7 KB of new steady-state content, and a full 600-entry
     * buffer renders ~150 KB. 128 KiB could still clip the oldest third of a
     * saturated buffer — i.e. exactly the part where the retry history lives —
     * so the file cap now matches the buffer: 192 KiB, still four times under the
     * 512 KiB export cap, and written once per second on a worker thread.
     */
    const val MAX_FILE_BYTES=192*1024

    /** Export cap (see [MAX_FILE_BYTES]); kept at 512 KiB. */
    const val MAX_EXPORT_BYTES=512*1024

    /**
     * Reserved room per category for [isCritical] entries. The four reservations
     * sum to exactly [MAX_ENTRIES]: when everything is critical, the plan shrinks
     * the buffer to the cap without starving any single category below its
     * reservation unless that category itself overran it.
     *
     * Without a per-category floor, "protect the critical events" degenerates
     * into "whichever critical source appends fastest owns the buffer": a long
     * `Nav: pending` stream would squeeze out every `Probe`/`Diag` line, and
     * those are the ones that say *why* it is pending.
     */
    const val MAX_CRITICAL_RESOURCES=150
    const val MAX_CRITICAL_NETWORK=120
    const val MAX_CRITICAL_WEB=150
    const val MAX_CRITICAL_CONNECTION=180

    /**
     * Soft sub-cap for the `nav` tag inside the RESOURCES reservation.
     *
     * `Nav: pending` is critical by contract (it is the stall witness) but it is
     * re-emitted every 2 s with nearly identical content: after ~60 lines it has
     * said everything once. When it overruns, its own oldest lines are the first
     * thing freed — that reclaims space before any *other* category's evidence is
     * touched, which is exactly the guarantee the case-A audit asked for.
     */
    const val MAX_CRITICAL_NAV=60

    /** Reserved room for non-critical entries, shared across categories. */
    const val MAX_NORMAL=240

    /**
     * Critical: losing this line would leave the export unable to explain the
     * failure, so heartbeat noise may never push it out.
     *
     * Rules:
     *  - every `CONNECTION` entry (pairing / VPN / switching is where a user's
     *    "it will not connect" report lives);
     *  - RESOURCES only for the navigation-stage tags — `Stage`/`Nav`/`Dom`/`Res`/
     *    `Inject` — and *not* `cache`/`load`/`webview`, which are chatty and whose
     *    summary already appears in the diagnostic section;
     *  - NETWORK only for the probe evidence `Probe`/`Diag`/`NetEnv`, not for
     *    per-request `network`/`health`/`net` heartbeats;
     *  - WEB only for what the page itself reported: `JSError`/`Console`/`MixedContent`.
     *
     * Matching is on the lower-cased tag, like [LogCategory.forTag].
     */
    fun isCritical(tag: String): Boolean {
        val key = tag.lowercase()
        if (LogCategory.forTag(tag) == LogCategory.CONNECTION) return true
        return key in CRITICAL_TAGS
    }

    private val CRITICAL_TAGS = setOf(
        "stage", "nav", "dom", "res", "inject",
        "probe", "diag", "netenv",
        "jserror", "console", "mixedcontent",
    )

    /** Reserved room for one category's critical entries; 0 = no reservation. */
    fun criticalCap(category: LogCategory): Int = when (category) {
        LogCategory.RESOURCES -> MAX_CRITICAL_RESOURCES
        LogCategory.NETWORK -> MAX_CRITICAL_NETWORK
        LogCategory.WEB -> MAX_CRITICAL_WEB
        LogCategory.CONNECTION -> MAX_CRITICAL_CONNECTION
        LogCategory.NOTIFICATIONS -> MAX_NORMAL / 4
        LogCategory.APP -> MAX_NORMAL / 4
    }

    /**
     * Decide which slots to evict so that all three budgets hold:
     * the buffer fits [max], every category fits its critical reservation, and the
     * repetitive `Nav: pending` stream fits [MAX_CRITICAL_NAV].
     *
     * The eviction *order* is the whole point — each budget is paid for by the
     * cheapest source, in this sequence:
     *  1. oldest **non-critical** entries, until [max] is met: heartbeat noise dies
     *     first and critical evidence is never sacrificed to make room for more noise;
     *  2. oldest **repetitive** critical entries (the `Nav` stall witness), until
     *     [MAX_CRITICAL_NAV] is met: the stream that repeated the same sentence
     *     every 2 s gives back its own oldest lines before any other category is asked;
     *  3. oldest critical entries **of a category that overran its reservation**,
     *     up to that overrun: a `Nav` storm cannot starve `Probe`/`Diag`, and a
     *     category inside its reservation keeps all of its evidence;
     *  4. only if the buffer is *still* over [max] (critical entries alone filled
     *     it), the oldest remaining lines.
     *
     * @param slots buffer contents, oldest first.
     * @return indices into [slots] to drop, ascending; empty when nothing must go.
     */
    fun evictionPlan(slots: List<LogSlot>, max: Int = MAX_ENTRIES): List<Int> {
        val doomed = linkedSetOf<Int>()
        fun remaining() = slots.size - doomed.size
        // 1. noise first.
        var index = 0
        while (remaining() > max && index < slots.size) {
            if (!slots[index].critical) doomed.add(index)
            index++
        }
        // 2. the repetitive stream gives back its own oldest lines.
        index = 0
        var repetitive = slots.count { it.critical && it.repetitive }
        while (repetitive > MAX_CRITICAL_NAV && index < slots.size) {
            val slot = slots[index]
            if (slot.critical && slot.repetitive && doomed.add(index)) repetitive--
            index++
        }
        // 3. only a category that overran its own reservation sacrifices critical lines,
        //    measured on what is still *kept* so a step above already paid for.
        for (category in LogCategory.values()) {
            var over = countCritical(slots.filterIndexed { i, slot -> slot.critical && slot.category == category && i !in doomed })
            over -= criticalCap(category)
            index = 0
            while (over > 0 && index < slots.size) {
                val slot = slots[index]
                if (slot.critical && slot.category == category && doomed.add(index)) over--
                index++
            }
        }
        // 4. hard cap: shed the oldest that nothing above claimed.
        index = 0
        while (remaining() > max && index < slots.size) {
            doomed.add(index)
            index++
        }
        return doomed.sorted()
    }

    private fun countCritical(slots: List<LogSlot>): Int = slots.count { it.critical }

    /** Keep a UTF-8 suffix without cutting a multi-byte character in half. */
    fun tailUtf8(text:String,limit:Int):String {
        require(limit>=0)
        val bytes=text.toByteArray(Charsets.UTF_8)
        if(bytes.size<=limit)return text
        var start=bytes.size-limit
        while(start<bytes.size && (bytes[start].toInt() and 0xc0) == 0x80)start++
        return bytes.copyOfRange(start,bytes.size).toString(Charsets.UTF_8)
    }
}
