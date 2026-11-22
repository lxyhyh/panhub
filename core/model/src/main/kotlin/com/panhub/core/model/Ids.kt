package com.panhub.core.model

import kotlin.jvm.JvmInline

/**
 * 一个网盘服务商的稳定标识。用小写字符串保证可持久化、可序列化，
 * 新增服务商时只需在这里加一个常量，不改动任何已有序号。
 */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank" }
    }

    override fun toString(): String = value
}

/** 服务商内部的文件/文件夹 id，不透明，由各实现自行解释。 */
@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "NodeId must not be blank" }
    }

    override fun toString(): String = value
}

/** 账号在本地数据库中的主键。 */
@JvmInline
value class AccountId(val value: String) {
    override fun toString(): String = value
}

/** 分享链接在服务商侧的 id。 */
@JvmInline
value class ShareId(val value: String) {
    override fun toString(): String = value
}
