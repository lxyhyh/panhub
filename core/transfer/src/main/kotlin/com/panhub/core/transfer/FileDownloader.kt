package com.panhub.core.transfer

import com.panhub.core.common.PanResult
import com.panhub.provider.api.DownloadTarget
import java.io.File

/** 一次下载进度的回调。total 为 null 表示服务端没给 Content-Length。 */
fun interface ProgressListener {
    suspend fun onProgress(bytes: Long, total: Long?)
}

/**
 * 把远端 URL 落到本地文件。
 *
 * 抽成接口是为了测试：队列的并发/取消/重试逻辑不该依赖真实网络。
 */
interface FileDownloader {
    suspend fun download(
        target: DownloadTarget,
        destination: File,
        listener: ProgressListener,
    ): PanResult<Unit>
}
