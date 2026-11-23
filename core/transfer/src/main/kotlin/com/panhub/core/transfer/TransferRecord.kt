package com.panhub.core.transfer

import kotlinx.serialization.Serializable

/**
 * 落盘的传输任务。
 *
 * 为什么不直接序列化 [TransferTask]：它带着 AccountId/NodeId 两个值类，
 * 值类要序列化就得让 core:model 依赖序列化插件，那是为了存档去污染领域模型。
 * 这里单独一层 DTO，字符串进出，枚举用名字存——老版本读新文件、新版本读老文件
 * 都只是「多一个未知字段」或「少一个默认值」，不会崩。
 */
@Serializable
internal data class TransferRecord(
    val id: String,
    val accountId: String,
    val nodeId: String? = null,
    val name: String,
    val direction: String = TransferDirection.DOWNLOAD.name,
    val state: String = TransferState.QUEUED.name,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val filePath: String? = null,
    val error: String? = null,
    val targetAccountId: String? = null,
    val targetParentId: String? = null,
    val phase: String? = null,
    val relativePath: String? = null,
    val attempt: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
internal data class StoredTransfers(
    val tasks: List<TransferRecord> = emptyList(),
    /** 格式版本号，将来迁移用。 */
    val version: Int = 1,
)
