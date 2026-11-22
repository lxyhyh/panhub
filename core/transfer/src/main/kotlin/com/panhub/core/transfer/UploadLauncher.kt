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
}
