package com.panhub.core.transfer

import com.panhub.core.model.AccountId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

    /**
     * 刚传完的东西。
     *
     * 让文件页能在传完之后自己把列表刷新一下，而不是让用户盯着一个
     * 明明已经传上去、却要手动点刷新才出现的文件。默认没有事件，
     * 只入队不管结果的最小实现不必关心它。
     */
    val uploaded: Flow<UploadedFile> get() = emptyFlow()
}

/**
 * 一次成功的上传。
 *
 * 只带「落在哪个账号的哪个目录下、叫什么」三项：刷新列表只需要判断
 * 「这次变化跟我现在看的这一层有没有关系」，传输页那些字节数用不上。
 */
data class UploadedFile(
    val accountId: AccountId,
    /** 目标目录；null 表示根目录。 */
    val parentId: NodeId?,
    val name: String,
)
