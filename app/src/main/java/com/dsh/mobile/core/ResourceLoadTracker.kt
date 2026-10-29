package com.dsh.mobile.core

/**
 * Byte accounting for the interface resources the shell has to pull before the
 * DSH UI can paint.
 *
 * Why this exists: the boot screen could only say "正在下载插件资源… 70%", and that
 * percentage is a *navigation progress* number, not a measure of the transfer. On
 * the tunnel route a single plugin bundle can be several megabytes, so a page
 * that sits at "70%" for half a minute looks stuck even when it is downloading
 * steadily (measured on the tablet: 7.6 MB received while the bar did not move).
 * Reporting bytes make, received and expected turns "slow" into "progressing".
 *
 * Two sources feed it, and they are deliberately kept apart:
 *  - [onDownload] — exact sizes the cache interceptor observed (authoritative).
 *  - [setDeclared] — the page's own expected total, once it reports one.
 * Progress is only a fraction when the total is actually known; otherwise the
 * caller gets `null` rather than a fabricated percentage.
 *
 * Pure data: no Android, no logging — the cache worker and the UI both touch it,
 * so it must stay testable.
 */
class ResourceLoadTracker {
    /** Total bytes this navigation has finished fetching. */
    @Volatile var loadedBytes: Long = 0L
        private set

    /** Bytes of transfers currently in flight (reported incrementally). */
    @Volatile var inFlightBytes: Long = 0L
        private set

    /** Expected total, when known (page-declared or sum of advertised lengths). */
    @Volatile var totalBytes: Long = UNKNOWN
        private set

    /** Resources that finished successfully. */
    @Volatile var completedCount: Int = 0
        private set

    /** Resources still being fetched. */
    @Volatile var activeCount: Int = 0
        private set

    /** Resources whose transfer failed. */
    @Volatile var failedCount: Int = 0
        private set

    /** Bytes served from the local cache (not re-downloaded). */
    @Volatile var reusedBytes: Long = 0L
        private set

    /** A fresh navigation: keep the totals, drop the in-flight bookkeeping. */
    fun reset() {
        loadedBytes = 0
        inFlightBytes = 0
        totalBytes = UNKNOWN
        declaredSum = 0
        completedCount = 0
        activeCount = 0
        failedCount = 0
        reusedBytes = 0
    }

    /** Sum of the Content-Length values announced in this navigation. */
    private var declaredSum: Long = 0

    /**
     * One transfer started, its size unknown until it reports.
     * @param expectedBytes advertised length, or [UNKNOWN] when the server did not say.
     */
    fun onStart(expectedBytes: Long = UNKNOWN) {
        activeCount++
        if (expectedBytes > 0) mergeDeclared(expectedBytes)
    }

    /**
     * A transfer reported incremental progress.
     *
     * The cache interceptor reports a *running total* per resource, not a delta,
     * so the difference against what this resource already contributed is added
     * to [inFlightBytes]. [previous] carries that per-resource figure.
     * @param previous - bytes already counted for this same transfer.
     * @param current - running total for this transfer.
     */
    fun onProgress(previous: Long, current: Long) {
        if (current <= previous) return
        inFlightBytes += current - previous
    }

    /**
     * A transfer finished.
     *
     * A failed transfer still *received* what [onProgress] already reported — the
     * bytes crossed the wire before the socket timed out, and the user watched
     * them arrive. Dropping them made the counter run backwards (7.3 MB → 1.7 MB),
     * which reads as a bug rather than as a failure. Keep the received volume and
     * record the failure separately.
     * @param bytes - final size of the resource, 0 when unknown.
     * @param counted - bytes of it already included via [onProgress].
     * @param failed - true when the transfer ended in an error.
     */
    fun onFinished(bytes: Long, counted: Long = 0L, failed: Boolean = false) {
        if (activeCount > 0) activeCount--
        // Whatever was in flight for this transfer is now settled, either as
        // received volume (failure) or as a completed resource (success).
        inFlightBytes = (inFlightBytes - counted).coerceAtLeast(0)
        if (failed) {
            failedCount++
            // Bytes that did arrive stay counted: they were real transfer volume.
            loadedBytes += counted
            if (totalBytes != UNKNOWN && loadedBytes > totalBytes) totalBytes = loadedBytes
            return
        }
        val size = if (bytes > 0) bytes else counted
        if (size > 0) {
            loadedBytes += size
            if (totalBytes != UNKNOWN && loadedBytes > totalBytes) totalBytes = loadedBytes
        }
        completedCount++
    }

    /** A resource was served locally; it costs no transfer but is "already there". */
    fun onReused(bytes: Long) {
        if (bytes > 0) {
            reusedBytes += bytes
            completedCount++
        }
    }

    /** The page declared how much it expects in total. */
    fun setDeclared(bytes: Long) {
        if (bytes > 0) mergeDeclared(bytes)
    }

    /**
     * One resource advertised its own length.
     *
     * The total is the sum of everything announced so far: the shell fetches a
     * handful of large bundles, so lengths arrive one resource at a time and the
     * denominator has to grow with them. A resource that never advertises a
     * length simply leaves the total unknown for its own share.
     * @param bytes - Content-Length of one resource.
     */
    fun onDeclaredSize(bytes: Long) {
        if (bytes <= 0) return
        declaredSum += bytes
        totalBytes = declaredSum
    }

    private fun mergeDeclared(bytes: Long) {
        totalBytes = when {
            totalBytes == UNKNOWN -> bytes
            else -> maxOf(totalBytes, bytes)
        }
    }

    /** Bytes known to be needed but not yet fetched. */
    val pendingBytes: Long get() = (totalBytes - loadedBytes - inFlightBytes).coerceAtLeast(0)

    /** 0..1 when the total is known, null when any percentage would be invented. */
    val fraction: Float?
        get() {
            val total = totalBytes
            if (total <= 0) return null
            return ((loadedBytes + inFlightBytes).toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }

    /** True while at least one transfer is still running. */
    val busy: Boolean get() = activeCount > 0

    /** True once something has actually been measured in this navigation. */
    val hasActivity: Boolean get() = loadedBytes > 0 || inFlightBytes > 0 || reusedBytes > 0 || failedCount > 0

    /** Snapshot for the UI; stable enough to drive a status string. */
    fun snapshot(): ResourceLoad = ResourceLoad(
        loadedBytes = loadedBytes,
        inFlightBytes = inFlightBytes,
        totalBytes = totalBytes,
        completedCount = completedCount,
        activeCount = activeCount,
        failedCount = failedCount,
        reusedBytes = reusedBytes,
    )

    companion object {
        /** Sentinel for "no total was advertised". */
        const val UNKNOWN = -1L
    }
}

/** Immutable view of the tracker, safe to hand to Compose. */
data class ResourceLoad(
    val loadedBytes: Long = 0,
    val inFlightBytes: Long = 0,
    val totalBytes: Long = -1,
    val completedCount: Int = 0,
    val activeCount: Int = 0,
    val failedCount: Int = 0,
    val reusedBytes: Long = 0,
) {
    val busy: Boolean get() = activeCount > 0

    /** True once anything has been measured in this navigation. */
    val hasActivity: Boolean get() = loadedBytes > 0 || inFlightBytes > 0 || reusedBytes > 0 || failedCount > 0

    val fraction: Float?
        get() {
            if (totalBytes <= 0) return null
            return ((loadedBytes + inFlightBytes).toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        }

    companion object {
        /** Human size for UI: 1.4 MB / 812 KB / 96 B. */
        fun size(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
            bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * Renders the resource-loading state for both surfaces (boot log box and the
 * native status strip). Kept separate from [ResourceLoad] so the wording is
 * testable without a Compose runtime.
 */
object ResourceLoadText {
    /**
     * Compact form for the native status strip, e.g. `资源 3.2/7.6 MB`.
     *
     * The strip already shows two lines; this has to fit beside the business
     * state in one. When no total is known it reports the transferred volume
     * without a denominator — never an invented percentage.
     * @param load - current snapshot.
     * @returns the compact text, or null when nothing has been measured.
     */
    fun compact(load: ResourceLoad): String? {
        if (!load.hasActivity) return null
        val loaded = ResourceLoad.size(load.loadedBytes + load.inFlightBytes)
        return if (load.totalBytes > 0) "资源 $loaded/${ResourceLoad.size(load.totalBytes)}"
        else "资源已加载 $loaded"
    }

    /**
     * Multi-line detail for the boot log box, e.g.
     * `资源 已加载 3.2 MB / 共 7.6 MB（42%）· 进行中 2 · 完成 11 · 复用 1.1 MB`.
     * @param load - current snapshot.
     * @returns the detail line, or null when nothing has been measured.
     */
    fun detail(load: ResourceLoad): String? {
        if (!load.hasActivity) return null
        val loaded = load.loadedBytes + load.inFlightBytes
        val head = if (load.totalBytes > 0) {
            val pct = ((load.fraction ?: 0f) * 100).toInt()
            "资源 已加载 ${ResourceLoad.size(loaded)} / 共 ${ResourceLoad.size(load.totalBytes)}（$pct%）"
        } else "资源 已加载 ${ResourceLoad.size(loaded)}"
        val tail = buildList {
            if (load.activeCount > 0) add("进行中 ${load.activeCount}")
            if (load.completedCount > 0) add("完成 ${load.completedCount}")
            if (load.reusedBytes > 0) add("复用 ${ResourceLoad.size(load.reusedBytes)}")
            if (load.failedCount > 0) add("失败 ${load.failedCount}")
        }
        return if (tail.isEmpty()) head else "$head · ${tail.joinToString(" · ")}"
    }
}
