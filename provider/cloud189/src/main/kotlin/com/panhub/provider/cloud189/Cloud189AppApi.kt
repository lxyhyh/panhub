package com.panhub.provider.cloud189

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 天翼云盘官方客户端通道的业务端点。
 *
 * 这一层只是把 [Cloud189AppChannel] 的签名请求翻译成有类型的调用，
 * 让将来切换通道时只需要换 provider 里的调用点，不用再碰签名与解析。
 *
 * 端点来源分两级（详见 docs/recon/cloud189.md）：
 * - 官方 APK 的 libapp.so 里能逐条对上路径的：列目录、建目录、改名；
 * - 只有 OpenList drivers/189pc 单证据的：批量任务、取下载地址、容量。
 * 单证据的那几个在注释里标了出来，等有账号时优先验证它们。
 */
internal class Cloud189AppApi(private val channel: Cloud189AppChannel) {

    /** 列目录。pageNum 从 1 起，根目录是 -11。 */
    suspend fun listFiles(
        folderId: String,
        pageNum: Int = 1,
        pageSize: Int = PAGE_SIZE,
    ): PanResult<C189Envelope> {
        val query = listOf(
            "folderId" to folderId,
            "fileType" to "0",
            "mediaAttr" to "0",
            "iconOption" to "5",
            "pageNum" to pageNum.toString(),
            "pageSize" to pageSize.toString(),
            "recursive" to "0",
            "orderBy" to "lastOpTime",
            "descending" to "true",
        )
        return decode(channel.get(PATH_FILE_LIST, query))
    }

    /**
     * 建目录。
     *
     * 参数放在 query 而不是 form body：参考实现 MakeDir 就是这么发的，
     * 放错地方服务端会当成缺参数。
     */
    suspend fun createFolder(parentFolderId: String, folderName: String): PanResult<String> =
        channel.postForm(
            path = PATH_CREATE_FOLDER,
            query = listOf(
                "folderName" to folderName,
                "relativePath" to "",
                "parentFolderId" to parentFolderId,
            ),
        )

    /** 文件改名。字段是 destFileName，和文件夹的 destFolderName 不是同一个。 */
    suspend fun renameFile(fileId: String, destName: String): PanResult<String> =
        channel.postForm(
            path = PATH_RENAME_FILE,
            query = listOf("fileId" to fileId, "destFileName" to destName),
        )

    /** 文件夹改名。 */
    suspend fun renameFolder(folderId: String, destName: String): PanResult<String> =
        channel.postForm(
            path = PATH_RENAME_FOLDER,
            query = listOf("folderId" to folderId, "destFolderName" to destName),
        )

    /**
     * 移动 / 复制 / 删除都走同一个批量任务端点。
     *
     * taskInfos 是 JSON 数组字符串，元素是 {"fileId","fileName","isFolder"}，
     * isFolder 是 1 或 0（不是布尔）。这个端点只有开源实现单证据。
     */
    suspend fun createBatchTask(
        type: String,
        targetFolderId: String?,
        fileId: String,
        fileName: String,
        isFolder: Boolean,
    ): PanResult<String> {
        val taskInfos = buildJsonArray {
            add(
                buildJsonObject {
                    put("fileId", fileId)
                    put("fileName", fileName)
                    put("isFolder", if (isFolder) 1 else 0)
                },
            )
        }.toString()
        val fields = buildList {
            add("type" to type)
            add("taskInfos" to taskInfos)
            if (!targetFolderId.isNullOrBlank()) add("targetFolderId" to targetFolderId)
        }
        return channel.postForm(PATH_BATCH_TASK, fields)
    }

    /** 轮询批量任务：taskStatus 1 初始化 / 2 冲突 / 3 执行中 / 4 完成。 */
    suspend fun checkBatchTask(type: String, taskId: String): PanResult<Int?> =
        when (val raw = channel.postForm(PATH_CHECK_BATCH_TASK, listOf("type" to type, "taskId" to taskId))) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val status = channel.objectOf(raw.value)?.let { Cloud189Decoding.textOf(it["taskStatus"]) }
                PanResult.Success(status?.toIntOrNull())
            }
        }

    /** 取下载地址。响应里的 fileDownloadUrl 还要再换一次 302 才是真直链。 */
    suspend fun downloadUrl(fileId: String): PanResult<String?> =
        when (val raw = channel.get(PATH_DOWNLOAD_URL, listOf("fileId" to fileId, "dt" to "3", "flag" to "1"))) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val url = channel.objectOf(raw.value)?.let { obj ->
                    Cloud189Decoding.textOf(obj["fileDownloadUrl"])
                        ?: Cloud189Decoding.textOf(obj["downloadUrl"])
                }
                PanResult.Success(url?.takeIf { it.isNotBlank() })
            }
        }

    /** 容量。字段名与 open 通道一致，都是 cloudCapacityInfo。 */
    suspend fun quota(): PanResult<C189Envelope> = decode(channel.get(PATH_USER_SIZE))

    private fun decode(raw: PanResult<String>): PanResult<C189Envelope> = when (raw) {
        is PanResult.Failure -> raw
        is PanResult.Success -> Cloud189Decoding.decode(raw.value)
    }

    /** 把响应体当对象取一个字段，调试与单证据端点用。 */
    fun fieldOf(raw: String, name: String): String? {
        val obj: JsonObject = channel.objectOf(raw) ?: return null
        return Cloud189Decoding.textOf(obj[name])
    }

    companion object {
        /** 参考实现每页取 1000，服务端也接受更小的页。 */
        const val PAGE_SIZE = 1000

        const val PATH_FILE_LIST = "/file/listFiles.action"
        const val PATH_CREATE_FOLDER = "/createFolder.action"
        const val PATH_RENAME_FILE = "/renameFile.action"
        const val PATH_RENAME_FOLDER = "/renameFolder.action"
        const val PATH_BATCH_TASK = "/batch/createBatchTask.action"
        const val PATH_CHECK_BATCH_TASK = "/batch/checkBatchTask.action"
        const val PATH_DOWNLOAD_URL = "/getFileDownloadUrl.action"
        const val PATH_USER_SIZE = "/portal/getUserSizeInfo.action"

        const val TYPE_MOVE = "MOVE"
        const val TYPE_COPY = "COPY"
        const val TYPE_DELETE = "DELETE"

        /** 批量任务状态：4 是完成，2 是冲突（目标已有同名）。 */
        const val TASK_DONE = 4
        const val TASK_CONFLICT = 2
    }
}
