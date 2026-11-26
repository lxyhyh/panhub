package com.panhub.feature.transfers

import com.panhub.core.design.formatBytes
import com.panhub.core.transfer.TransferDirection
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一个传输任务的全部信息。
 *
 * 卡片上那两行是给「扫一眼」用的：名字长的任务状态会被挤掉，
 * 失败原因更是常常只剩前十几个字，而偏偏那才是用户点进来要找的东西。
 *
 * 这里用的全是队列里现成的数据，打开详情不发请求也不转圈——
 * 传输页从头到尾不联网，详情要查服务端的话就等于给一个离线页面加了个空转的等待。
 */
object TransferDetail {

    private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"

    /** 路径与错误这几行的标签。 */
    private const val ROW_PATH = "保存在"
    private const val ROW_SOURCE = "来源文件"
    private const val ROW_SCRATCH = "中转文件"
    private const val ROW_ERROR = "错误"

    /** 弹窗标题。名字为空时给个兜底，别让标题栏空着。 */
    fun title(task: TransferTask): String = task.name.ifBlank { "传输任务" }

    fun directionLabel(direction: TransferDirection): String = when (direction) {
        TransferDirection.DOWNLOAD -> "下载"
        TransferDirection.UPLOAD -> "上传"
        TransferDirection.CLOUD_COPY -> "跨网盘转存"
        TransferDirection.DOWNLOAD_FOLDER -> "下载文件夹"
        TransferDirection.CLOUD_COPY_FOLDER -> "跨网盘转存文件夹"
        TransferDirection.UPLOAD_FOLDER -> "上传文件夹"
    }

    fun stateLabel(state: TransferState): String = when (state) {
        TransferState.QUEUED -> "排队中"
        TransferState.RUNNING -> "传输中"
        TransferState.COMPLETED -> "已完成"
        TransferState.FAILED -> "失败"
        TransferState.CANCELED -> "已取消"
        TransferState.PAUSED -> "已暂停"
    }

    /**
     * 详情里的每一行，顺序就是阅读顺序：先说这是什么，再说它到哪一步了，
     * 最后才是路径与错误这类要慢慢看的长文本。
     *
     * [accountNote] 直接用卡片上那一行（带前导「 · 」），两处说法保持一致；
     * [speed] 只有正在跑的任务才有，取不到就不会有速度这一行。
     */
    fun rows(task: TransferTask, accountNote: String, speed: Long?): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("状态" to stateLine(task))
        rows.add("方式" to directionLabel(task.direction))
        accountOf(accountNote)?.let { rows.add("账号" to it) }
        totalLine(task)?.let { rows.add("大小" to it) }
        rows.add("已传" to movedLine(task))
        val rate = TransferStatusText.speedNote(task, speed).removePrefix(" · ")
        if (rate.isNotBlank()) rows.add("速率" to rate)
        phaseOf(task)?.let { rows.add("阶段" to it) }
        pathLine(task)?.let { (label, value) -> rows.add(label to value) }
        errorOf(task)?.let { rows.add(ROW_ERROR to it) }
        val created = timeOf(task.createdAt)
        val updated = timeOf(task.updatedAt)
        if (created != null) rows.add("创建于" to created)
        // 刚建立的任务两个时间一样，重复画两行只会让人以为改过什么。
        if (updated != null && updated != created) rows.add("更新于" to updated)
        return rows
    }

    /**
     * 状态行。重试过的任务要顺带说清这是第几次——
     * 一个反复失败的任务，不说次数的话看起来就是「一直没好」。
     */
    private fun stateLine(task: TransferTask): String {
        val head = stateLabel(task.state)
        if (task.attempt <= 1) return head
        return head + " · 第 " + task.attempt + " 次尝试"
    }

    private fun accountOf(accountNote: String): String? =
        accountNote.removePrefix(" · ").trim().takeIf { it.isNotEmpty() }

    /** 总量未知时整行不画：「大小」写个「未知」只是把问题重复一遍。 */
    private fun totalLine(task: TransferTask): String? {
        val total = task.totalBytes ?: return null
        if (total <= 0L) return null
        return formatBytes(total)
    }

    /** 已传多少；知道总量时顺带给个百分比，那是用户唯一能判断「还要不要等」的依据。 */
    private fun movedLine(task: TransferTask): String {
        val moved = formatBytes(task.bytes)
        val total = task.totalBytes
        if (total == null || total <= 0L) return moved
        val percent = (task.bytes * 100L / total).coerceIn(0L, 100L)
        return moved + " / " + formatBytes(total) + "（" + percent + "%）"
    }

    private fun phaseOf(task: TransferTask): String? =
        task.phase?.takeIf { it.isNotBlank() }

    /**
     * 本地路径这一行的说法随方向变：下载出来的是成品，上传用的是源文件，
     * 转存那头只是中转用的临时文件（任务结束随时会被清掉）。
     * 三种东西都叫「路径」的话，用户会以为自己下载下来的文件被删了。
     */
    private fun pathLine(task: TransferTask): Pair<String, String>? {
        val path = task.filePath?.takeIf { it.isNotBlank() } ?: return null
        val label = when (task.direction) {
            TransferDirection.UPLOAD, TransferDirection.UPLOAD_FOLDER -> ROW_SOURCE
            TransferDirection.CLOUD_COPY, TransferDirection.CLOUD_COPY_FOLDER -> ROW_SCRATCH
            else -> ROW_PATH
        }
        return label to path
    }

    /**
     * 这几行的值可能很长：失败原因常常是整段服务器报错，路径也可能很深。
     *
     * 普通行是一行标签一行值、值超长就省略；这种行必须换行铺开，
     * 否则用户点进详情看到的仍然是被截断的半句话。
     */
    fun isLong(label: String): Boolean =
        label == ROW_ERROR || label == ROW_PATH || label == ROW_SOURCE || label == ROW_SCRATCH

    private fun errorOf(task: TransferTask): String? =
        task.error?.takeIf { it.isNotBlank() }

    /** 时间戳为 0 或负数说明服务端与引擎都没给，画出来只会是 1970 年。 */
    private fun timeOf(millis: Long?): String? {
        if (millis == null || millis <= 0L) return null
        return SimpleDateFormat(TIME_PATTERN, Locale.getDefault()).format(Date(millis))
    }
}
