package com.panhub.feature.transfers

import com.panhub.core.design.formatBytes
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferEstimate
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask

/**
 * 传输卡片上的速率文案。
 *
 * 单独拎出来是为了能测：这里全是「什么情况下该显示什么」的判断，
 * 和排版无关，却最容易写错（比如把排队中的任务也标上 0 B/s）。
 */
object TransferStatusText {

    /**
     * 运行中的速率与剩余时间；不该显示时返回空串。
     *
     * 只给「正在跑」的任务加：排队中的任务没有速度可言，结束的任务速度是历史数据，
     * 挂在那里只会让已经传完的一行显得还没完。
     *
     * 速率还没测出来（刚起步、只有一次采样）时什么都不显示——
     * 写「0 B/s」会让人以为卡住了，而它其实正在飞快地传。
     */
    fun speedNote(task: TransferTask, speed: Long?): String {
        if (task.state != TransferState.RUNNING) return ""
        if (speed == null) return ""
        // 整理型任务不传字节，它们的「速率」是列目录的速度，说出来没意义。
        if (task.direction == TransferDirection.DOWNLOAD_FOLDER ||
            task.direction == TransferDirection.CLOUD_COPY_FOLDER ||
            task.direction == TransferDirection.UPLOAD_FOLDER
        ) {
            return ""
        }
        val head = " · " + formatBytes(speed) + "/s"
        val remaining = TransferEstimate.remainingMillis(task, speed) ?: return head
        return head + " · 还剩 " + TransferEstimate.describe(remaining)
    }

    /**
     * 这个任务前面还排着几个。
     *
     * 暂停的不算：它不会自己跑起来，算进去会让后面的人以为还要等它。
     * 传入的必须是整条队列而不是筛选后的列表——切到「已结束」时
     * 前面的排队任务会被筛掉，那样数出来的位置是假的。
     */
    fun aheadOf(queue: List<TransferTask>, task: TransferTask): Int {
        if (task.state != TransferState.QUEUED) return 0
        var count = 0
        for (other in queue) {
            if (other.id == task.id) break
            // 正在跑的那个也在前面排着，它跑完才轮到我。
            if (other.state == TransferState.RUNNING || other.state == TransferState.QUEUED) count++
        }
        return count
    }

    /** 前面还有几个；0 表示下一个就是它，说「马上开始」比说「第 1 个」更像人话。 */
    fun queueNote(ahead: Int): String = when {
        ahead <= 0 -> ""
        ahead == 1 -> " · 前面还有 1 个"
        else -> " · 前面还有 " + ahead + " 个"
    }
}
