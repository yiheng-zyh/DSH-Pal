package com.dsh.mobile.core

object DownloadLibrary {
    enum class Sort(val label: String) { TIME("下载时间"), NAME("文件名"), CONVERSATION("对话"), SIZE("文件大小") }
    enum class Age(val label: String) { ALL("全部时间"), TODAY("今天"), WEEK("最近 7 天"), OLD_WEEK("超过 7 天"), OLD_MONTH("超过 30 天"), UNKNOWN("时间未知") }
    enum class Size(val label: String) { ALL("全部大小"), SMALL("小于 1 MB"), MEDIUM("1–10 MB"), LARGE("10–100 MB"), HUGE("100 MB 及以上") }
    fun files(source: List<DownloadStore.File>, query: String, sort: Sort, descending: Boolean, age: Age, size: Size, now: Long = System.currentTimeMillis()): List<DownloadStore.File> {
        val day=86_400_000L
        val today=java.util.Calendar.getInstance().apply { timeInMillis=now; set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis
        val filtered=source.filter { f ->
            (f.name.contains(query.trim(),true) || f.conversation.contains(query.trim(),true)) &&
            when(age) { Age.ALL->true; Age.TODAY->f.time>=today; Age.WEEK->f.time>0 && f.time>=now-7*day; Age.OLD_WEEK->f.time in 1 until now-7*day; Age.OLD_MONTH->f.time in 1 until now-30*day; Age.UNKNOWN->f.time==0L } &&
            when(size) { Size.ALL->true; Size.SMALL->f.bytes<1_048_576; Size.MEDIUM->f.bytes in 1_048_576 until 10_485_760; Size.LARGE->f.bytes in 10_485_760 until 104_857_600; Size.HUGE->f.bytes>=104_857_600 }
        }
        val comparator: Comparator<DownloadStore.File> = when(sort) {
            Sort.TIME->compareBy { it.time }; Sort.NAME->compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.CONVERSATION->compareBy(String.CASE_INSENSITIVE_ORDER) { it.conversation.ifBlank { "未记录对话" } }; Sort.SIZE->compareBy { it.bytes }
        }
        val sorted=filtered.sortedWith((if(descending) comparator.reversed() else comparator).thenBy { it.id })
        return if(sort==Sort.TIME) sorted.filter { it.time>0 }+sorted.filter { it.time==0L } else sorted
    }
}
