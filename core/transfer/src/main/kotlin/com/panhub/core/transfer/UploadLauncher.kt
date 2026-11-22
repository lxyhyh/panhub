package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 把本地文件送到某个账号的目标目录。
 *
 * 和 [DownloadLauncher] 一样是**窄接口**：视图层只知道自己要「传一个文件上去」，
 * 不需要认识 TransferEngine 的队列、状态机或 provider。
 *
 * 为什么参数是本地路径而不是 Uri/InputStream：读 content:// 需要 ContentResolver，
 * 属于 Android 平台能力，放在 feature 层更合适；核心层只处理普通文件。
 */
interface UploadLauncher {
    /**
     * 返回任务 id。界面可以拿它在传输列表里定位这一条，
     * 也可以只当副作用忽略。
     */
    fun upload(
        accountId: AccountId,
        parentId: NodeId?,
        localPath: String,
        name: String,
        size: Long,
    ): String

    /**
     * 把本地一个目录整个传上去，返回整理任务的 id。
     *
     * 和单文件一样只收本地路径：目录遍历在核心层用 [java.io.File] 就够了，
     * content:// 那套在 feature 层先落到缓存目录。
     *
     * [rootName] 是网盘上要建的文件夹名，可以不等于 [localDir] 的名字。
     */
    fun uploadFolder(
        accountId: AccountId,
        parentId: NodeId?,
        localDir: String,
        rootName: String,
    ): String
}
