package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 传输方向。
 *
 * [CLOUD_COPY] 是跨网盘中转：从源账号下载到本地临时目录，再上传到目标账号。
 * 两家的服务端不会互相说话，只能这样搬一次。
 */
enum class TransferDirection { DOWNLOAD, UPLOAD, CLOUD_COPY }

/**
 * 一个传输任务的状态机：
 *
 * QUEUED -> RUNNING -> COMPLETED
 *                   -> FAILED
 *                   -> CANCELED
 *
 * FAILED 和 CANCELED 都能被 retry 拉回 QUEUED；COMPLETED 是终态。
 */
enum class TransferState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELED }

/**
 * 一个传输任务。它是可持久化的纯数据，不含任何协程句柄，
 * 这样进程被杀掉后能从磁盘恢复（本版先只存在内存里）。
 */
data class TransferTask(
    val id: String,
    val accountId: AccountId,
    /**
     * 远端节点 id。
     *
     * 下载时是被下载的文件；上传时是目标目录（根目录为 null）。
     * 两者语义不同，所以这里允许 null，由各分支自己解释。
     */
    val nodeId: NodeId? = null,
    val name: String,
    val direction: TransferDirection = TransferDirection.DOWNLOAD,
    val state: TransferState = TransferState.QUEUED,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val filePath: String? = null,
    val error: String? = null,
    /**
     * 中转任务的目标账号与目标目录。
     *
     * 单独两个字段而不是复用一个 "to" 对象：下载/上传两类任务用不到它们，
     * 空着就是「没有这一半」，比多引一层嵌套更容易读懂。
     */
    val targetAccountId: AccountId? = null,
    val targetParentId: NodeId? = null,
    /**
     * 当前阶段，只在需要分段的传输里非空（中转要下载再上传两段）。
     * 界面直接展示这行字，不用去猜进度条为什么重新开始了。
     */
    val phase: String? = null,
    /**
     * 已经跑过几次（含正在进行的那次）。
     *
     * 界面据此告诉用户「正在第 2 次尝试」，否则自动重试期间
     * 进度条忽然回到 0 看起来像是出了新问题。
     */
    val attempt: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 进度 0..1；总大小未知时返回 null，界面应显示不确定进度。 */
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytes.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }

    val isActive: Boolean
        get() = state == TransferState.QUEUED || state == TransferState.RUNNING

    val isFinished: Boolean
        get() = state == TransferState.COMPLETED ||
            state == TransferState.FAILED ||
            state == TransferState.CANCELED
}
