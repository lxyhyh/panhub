package com.panhub.core.transfer

import java.io.File

/**
 * 已下载到本机的文件。
 *
 * 下载落在应用私有目录里，列表只是记录，文件本身一直占着空间。
 * 用户把任务从列表里清掉之后，那些文件还在，而且再也没有入口能看见它们
 * ——所以「删除本地文件」必须和「从列表移除」分开做。
 *
 * 这里只回答两个问题：这个任务的文件能不能删、删掉了没有。
 * 队列与界面都不该自己拼路径，路径规则一变就会有一半代码漏改。
 */
object DownloadedFiles {

    /**
     * 只有「真的把文件下到本机」的任务才可以删。
     *
     * 转存任务的本地路径是中途用的临时文件，完成后早就清了；
     * 上传任务的路径是用户自己的源文件——照着删等于把用户的文件毁掉。
     * 整目录下载自己不带文件（它的子任务才是文件），所以也会被挡在外面。
     */
    fun canDelete(task: TransferTask): Boolean =
        task.state == TransferState.COMPLETED &&
            task.direction == TransferDirection.DOWNLOAD &&
            !task.filePath.isNullOrBlank()

    /**
     * 删掉任务对应的本地文件，返回文件是否已经不在。
     *
     * 文件本来就不在也算成功：调用方要的结果是「这份文件别再占地方了」，
     * 目的已经达到。
     */
    fun delete(task: TransferTask): Boolean {
        val path = task.filePath ?: return false
        val file = File(path)
        if (!file.exists()) return true
        return file.delete()
    }
}
