package com.panhub.core.transfer

/**
 * 一轮传输结束时的成绩单。
 *
 * 只统计「这一轮真正跑过、并且有了结果」的任务：冷启动时从存档恢复出来的历史任务
 * 早就结束了，把它们的成绩算进这一轮，用户会在什么都没做的时候收到一条「传输完成」
 * 通知，久而久之就会把这个通知拉黑。
 */
data class TransferReport(
    /** 成功的个数。 */
    val completed: Int = 0,
    /** 失败的个数。取消的不算：那是用户自己不要的。 */
    val failed: Int = 0,
    /** 这一轮搬过的字节数（只算成功的那些，失败的中途停下的部分不该算成绩）。 */
    val bytes: Long = 0L,
) {
    /** 有结果的任务数。 */
    val total: Int get() = completed + failed

    /** 什么都没发生：没有成功的也没有失败的，不该发通知。 */
    val isEmpty: Boolean get() = total == 0

    companion object {
        val None = TransferReport()
    }
}

/**
 * 盯着队列，在一轮任务全部结束的那一刻给出一张成绩单。
 *
 * 为什么要单独一个类：前台服务订阅的是整个队列流，「有活」与「没活」之间的跳变
 * 才是「这一轮结束了」的信号，而这个跳变本身是有状态的判断（上一帧有没有活、
 * 哪些任务是这一轮跑过的），塞在 Service 里既测不了也容易写错。
 *
 * 判定「这一轮跑过」的办法是记住所有处于进行中状态（排队或正在跑）的任务 id。
 * 只有这些 id 后来变成了 COMPLETED / FAILED 才算成绩——取消的不算，
 * 从存档恢复出来的历史任务也不算。
 */
class TransferRunTracker {

    /** 这一轮里出现过、处于进行中的任务 id。 */
    private val watched = LinkedHashSet<String>()

    /** 上一次采样时队列里还有活吗。 */
    private var active = false

    /**
     * 采样一次队列。
     *
     * 返回非 null 表示「这一轮刚刚结束」，调用方该发通知了；
     * 其余情况返回 null——包括一直是空的、还在跑、以及只结束了几个但队列还有活。
     */
    fun sample(tasks: List<TransferTask>): TransferReport? {
        var hasActive = false
        for (task in tasks) {
            if (task.isActive) {
                hasActive = true
                watched.add(task.id)
            }
        }
        val justFinished = active && !hasActive
        active = hasActive
        if (!justFinished) return null
        val report = reportOf(tasks)
        // 一轮结束就把记忆清掉：下一轮是新的成绩单，
        // 不能让上一轮的完成任务在这次没跑的情况下再被数一遍。
        watched.clear()
        return if (report.isEmpty) null else report
    }

    private fun reportOf(tasks: List<TransferTask>): TransferReport {
        var completed = 0
        var failed = 0
        var bytes = 0L
        for (task in tasks) {
            if (task.id !in watched) continue
            when (task.state) {
                TransferState.COMPLETED -> {
                    completed++
                    // 完成的任务 totalBytes 才是「搬了多少」，bytes 在中转任务里会归零。
                    bytes += task.totalBytes ?: task.bytes
                }
                TransferState.FAILED -> failed++
                // 取消、暂停、以及还在排队的都不算成绩。
                else -> Unit
            }
        }
        return TransferReport(completed = completed, failed = failed, bytes = bytes)
    }
}
