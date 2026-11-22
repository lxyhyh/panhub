package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 「把 A 账号的某个文件送到 B 账号」的最小契约。
 *
 * 这是统一网盘区别于「同时装五个客户端」的地方：文件不用先落到用户的下载目录，
 * 从源网盘下载到应用私有的临时目录、立刻上传到目标网盘，传完即删。
 *
 * 只覆盖单文件：跨网盘中转一个文件夹要先递归列目录再逐个搬，
 * 那是另一个量级的功能，不适合和单文件复制混在同一个入口里。
 */
interface CloudCopyLauncher {
    /** 返回任务 id。目标目录为 null 表示目标账号的根目录。 */
    fun copyBetween(
        fromAccountId: AccountId,
        nodeId: NodeId,
        name: String,
        size: Long?,
        toAccountId: AccountId,
        toParentId: NodeId?,
    ): String
}
