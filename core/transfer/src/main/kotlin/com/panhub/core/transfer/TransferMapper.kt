package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * [TransferTask] 与落盘格式之间的转换。
 *
 * 所有「格式兼容」的判断都收在这一个文件里：以后 TransferTask 加字段，
 * 只需在这里决定它要不要落盘、老记录怎么补默认值。
 */
internal object TransferMapper {

    fun toRecord(task: TransferTask): TransferRecord = TransferRecord(
        id = task.id,
        accountId = task.accountId.value,
        nodeId = task.nodeId?.value,
        name = task.name,
        direction = task.direction.name,
        state = task.state.name,
        bytes = task.bytes,
        totalBytes = task.totalBytes,
        filePath = task.filePath,
        error = task.error,
        targetAccountId = task.targetAccountId?.value,
        targetParentId = task.targetParentId?.value,
        phase = task.phase,
        relativePath = task.relativePath,
        attempt = task.attempt,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
    )

    /**
     * 读回一条任务。
     *
     * 认不出的方向/状态返回 null 而不是猜一个：猜错会让一条早已不存在的上传
     * 任务在列表里变成下载任务，用户点「重试」就去下载一个不存在的文件。
     */
    fun toTask(record: TransferRecord): TransferTask? {
        val direction = enumValueOfOrNull<TransferDirection>(record.direction) ?: return null
        val state = enumValueOfOrNull<TransferState>(record.state) ?: return null
        if (record.id.isBlank() || record.accountId.isBlank() || record.name.isBlank()) return null
        return TransferTask(
            id = record.id,
            accountId = AccountId(record.accountId),
            nodeId = record.nodeId?.takeIf { it.isNotBlank() }?.let(::NodeId),
            name = record.name,
            direction = direction,
            state = state,
            bytes = record.bytes,
            totalBytes = record.totalBytes,
            filePath = record.filePath,
            error = record.error,
            targetAccountId = record.targetAccountId?.takeIf { it.isNotBlank() }?.let(::AccountId),
            targetParentId = record.targetParentId?.takeIf { it.isNotBlank() }?.let(::NodeId),
            phase = record.phase,
            relativePath = record.relativePath,
            attempt = record.attempt,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOfOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
