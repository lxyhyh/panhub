package com.panhub.core.transfer

/**
 * 队列的汇总视图。
 *
 * 抽出来的原因：传输页的标题、系统通知的正文、以后可能出现的桌面小组件，
 * 说的都是同一件事——「现在在传什么、还剩多少」。各处自己数一遍，
 * 迟早出现通知说「正在下载」而列表里其实已经空了的情况。
 */
data class TransferDigest(
    /** 正在跑的那一个。串行队列最多只有一个，没有就是 null。 */
    val running: TransferTask? = null,
    /** 排队中（含正在退避等待重试）的数量。 */
    val queued: Int = 0,
    /** 还在进行中的总数，等于 running 与 queued 之和。 */
    val active: Int = 0,
    /** 进行中任务已经传输的字节数。 */
    val doneBytes: Long = 0L,
    /** 进行中任务的总字节数；任一任务大小未知时为 null，进度条就该是不确定的。 */
    val totalBytes: Long? = null,
) {
    val hasActive: Boolean get() = active > 0

    /** 0..100 的整数百分比；总大小未知或为 0 时返回 null。 */
    val percent: Int?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            val ratio = doneBytes.toDouble() / total.toDouble()
            return (ratio * 100).toInt().coerceIn(0, 100)
        }

    companion object {
        /** 只统计进行中的任务：已完成的历史不该继续占着通知栏的进度。 */
        fun of(tasks: List<TransferTask>): TransferDigest {
            val activeTasks = tasks.filter { it.isActive }
            if (activeTasks.isEmpty()) return TransferDigest()
            var done = 0L
            var total = 0L
            var unknown = false
            for (task in activeTasks) {
                done += task.bytes
                val size = task.totalBytes
                if (size == null) unknown = true else total += size
            }
            return TransferDigest(
                running = activeTasks.firstOrNull { it.state == TransferState.RUNNING },
                queued = activeTasks.count { it.state == TransferState.QUEUED },
                active = activeTasks.size,
                doneBytes = done,
                totalBytes = if (unknown) null else total,
            )
        }
    }
}
