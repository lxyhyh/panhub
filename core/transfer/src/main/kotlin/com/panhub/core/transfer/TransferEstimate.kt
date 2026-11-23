package com.panhub.core.transfer

/**
 * 剩余时间的估算。
 *
 * 单独抽出来是因为「还剩多久」比「传了多少」更容易算错：
 * 大小未知、速率为 0、已经传完，这三种情况都不该给出一个数字。
 * 界面只负责把结果变成文字。
 */
object TransferEstimate {

    /**
     * 还需要多少毫秒。
     *
     * 返回 null 表示「说不好」：总大小未知、速率还没测出来、或者速率是 0
     * （真的卡住了，说「还剩 1 小时」是在骗人）。
     */
    fun remainingMillis(bytes: Long, totalBytes: Long?, bytesPerSecond: Long?): Long? {
        if (totalBytes == null || totalBytes <= 0L) return null
        if (bytesPerSecond == null || bytesPerSecond <= 0L) return null
        val remaining = totalBytes - bytes
        if (remaining <= 0L) return 0L
        return remaining * 1000L / bytesPerSecond
    }

    /** 从一个任务直接算，省得界面自己拆字段。 */
    fun remainingMillis(task: TransferTask, bytesPerSecond: Long?): Long? =
        remainingMillis(task.bytes, task.totalBytes, bytesPerSecond)

    /**
     * 把毫秒说成人话。
     *
     * 只保留一位有效量级：估算本来就有误差，说「还剩 1 小时 23 分 7 秒」
     * 精确到秒反而显得可信，实际下一分钟就可能变。
     */
    fun describe(millis: Long): String {
        val seconds = (millis / 1000L).coerceAtLeast(0L)
        return when {
            seconds < 1L -> "不到 1 秒"
            seconds < 60L -> seconds.toString() + " 秒"
            seconds < 3600L -> {
                val minutes = seconds / 60L
                val rest = seconds % 60L
                if (rest == 0L) minutes.toString() + " 分钟" else minutes.toString() + " 分 " + rest + " 秒"
            }
            else -> {
                val hours = seconds / 3600L
                val minutes = (seconds % 3600L) / 60L
                if (minutes == 0L) hours.toString() + " 小时" else hours.toString() + " 小时 " + minutes + " 分"
            }
        }
    }
}
