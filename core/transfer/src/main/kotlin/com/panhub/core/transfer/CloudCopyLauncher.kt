package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 「把 A 账号的文件送到 B 账号」的最小契约。
 *
 * 这是统一网盘区别于「同时装五个客户端」的地方：文件不用先落到用户的下载目录，
 * 从源网盘下载到应用私有的临时目录、立刻上传到目标网盘，传完即删。
 *
 * 单文件和文件夹分成两个入口而不是一个带 [Boolean] 的方法：
 * 文件夹复制要先扫源目录、再在目标端建出目录树，失败时能说清卡在哪一段，
 * 和「一个文件下完再传」是完全不同的流程。
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

    /**
     * 跨网盘复制整个文件夹：在 [toParentId] 下建一个与 [name] 同名的文件夹，
     * 再把源文件夹的内容按原层级搬进去。
     *
     * 目标账号必须支持新建文件夹，否则连目录结构都摆不出来。
     *
     * @return 任务 id
     */
    fun copyFolderBetween(
        fromAccountId: AccountId,
        nodeId: NodeId,
        name: String,
        toAccountId: AccountId,
        toParentId: NodeId?,
    ): String
}
