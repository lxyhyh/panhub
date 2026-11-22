package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/** 传输方向。这一版只做下载，上传留接口不实现。 */
enum class TransferDirection { DOWNLOAD, UPLOAD }

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
