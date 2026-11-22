package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 「把这个节点加入下载队列」的最小契约。
 *
 * 存在的理由：feature 层只想知道"能不能发起下载"，不该看到
 * [TransferEngine] 里的队列、协程、重试。宽接口会把调度细节泄漏到 UI，
 * 以后换实现（比如加并发、加断点续传）就要动 UI 代码。
 */
interface DownloadLauncher {
    fun download(accountId: AccountId, nodeId: NodeId, name: String, size: Long?)

    /**
     * 把一个文件夹整个下载下来。
     *
     * 单独一个方法而不是给 [download] 加个 isDir 开关：调用方不该在
     * 「文件」和「目录」之间做分支判断，那属于队列自己的事。
     */
    fun downloadFolder(accountId: AccountId, nodeId: NodeId, name: String)

    /** 该服务商是否支持下载；不支持时界面应隐藏入口。 */
    fun supportsDownload(accountId: AccountId): Boolean = true
}
