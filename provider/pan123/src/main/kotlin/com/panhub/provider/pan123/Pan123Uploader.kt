package com.panhub.provider.pan123

import com.panhub.core.common.FileDigest
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.network.StreamRequestBody
import com.panhub.core.network.bounded
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.UploadSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * 123 云盘的上传流程。
 *
 * 三步（依据 OpenList drivers/123/upload.go 的真实调用序列）：
 * 1. 提交 MD5 与文件大小到 upload_request，服务端决定是秒传、直传还是分片；
 * 2. 分片：向 s3_upload_object/auth（单分片）或 s3_repare_upload_parts_batch（多分片）
 *    换取预签名 URL，然后逐个 PUT；
 * 3. upload_complete/v2 收尾，服务端才开始索引这个文件。
 *
 * 单独成类是因为它比增删改查复杂得多，混进 Provider 会淹掉别的逻辑。
 */
internal class Pan123Uploader(
    private val api: Pan123Api,
    private val json: Json,
) {

    suspend fun upload(
        token: String?,
        parentId: NodeId?,
        source: UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        if (source.size <= 0) {
            return PanResult.Failure(PanError.notSupported("暂不支持上传空文件"))
        }

        val etag = runCatching {
            source.openStream().use { FileDigest.md5(it) }
        }.getOrElse { e ->
            return PanResult.Failure(PanError.unknown("读取本地文件失败", e.message, e))
        }

        val request = UploadRequest(
            etag = etag.lowercase(),
            fileName = source.name,
            parentFileId = parentId?.value ?: ROOT_ID,
            size = source.size,
        )
        val ticket = when (
            val result = api.post(
                path = Pan123Api.PATH_UPLOAD_REQUEST,
                token = token,
                body = json.encodeToString(UploadRequest.serializer(), request),
                decode = { raw ->
                    json.decodeFromString(ApiEnvelope.serializer(UploadTicket.serializer()), raw)
                },
            )
        ) {
            is PanResult.Failure -> return PanResult.Failure(result.error)
            is PanResult.Success -> result.value
        }

        // reuse：服务端已经有内容相同的文件，直接落库，一个字节都不用传。
        if (ticket.reuse || ticket.key.isNullOrBlank()) {
            return PanResult.Success(ticket.toNode(parentId, source))
        }

        val chunkSize = CHUNK_SIZE
        val chunkCount = ((source.size + chunkSize - 1) / chunkSize).toInt()
        val lastChunkSize = source.size - (chunkCount - 1).toLong() * chunkSize
        val sent = AtomicLong(0)

        // 单分片走 s3_upload_object/auth，多分片走 s3_repare_upload_parts_batch：
        // 这是服务端两条不同的取签名入口，用错会拿不到 URL。
        val batchSize = if (chunkCount > 1) BATCH_SIZE else 1
        val authPath = if (chunkCount > 1) Pan123Api.PATH_S3_PARTS else Pan123Api.PATH_S3_AUTH

        var index = 1
        while (index <= chunkCount) {
            val end = minOf(index + batchSize, chunkCount + 1)
            val urls = when (
                val result = api.post(
                    path = authPath,
                    token = token,
                    body = json.encodeToString(
                        S3PartRequest.serializer(),
                        S3PartRequest(
                            storageNode = ticket.storageNode,
                            bucket = ticket.bucket,
                            key = ticket.key,
                            partNumberStart = index,
                            partNumberEnd = end,
                            uploadId = ticket.uploadId,
                        ),
                    ),
                    decode = { raw ->
                        json.decodeFromString(ApiEnvelope.serializer(S3PartData.serializer()), raw)
                    },
                )
            ) {
                is PanResult.Failure -> return PanResult.Failure(result.error)
                is PanResult.Success -> result.value.presignedUrls
            }

            while (index < end) {
                val url = urls[index.toString()]
                if (url.isNullOrBlank()) {
                    return PanResult.Failure(
                        PanError.unknown("服务端没有下发第 $index 个分片的上传地址"),
                    )
                }
                val offset = (index - 1).toLong() * chunkSize
                val length = if (index == chunkCount) lastChunkSize else chunkSize
                val body = StreamRequestBody(
                    openStream = { source.openStream().bounded(offset, length) },
                    length = length,
                )
                when (val put = api.putUrl(url, body)) {
                    is PanResult.Failure -> return PanResult.Failure(put.error)
                    is PanResult.Success -> Unit
                }
                sent.addAndGet(length)
                listener.onProgress(sent.get().coerceAtMost(source.size), source.size)
                index++
            }
        }

        val complete = UploadCompleteRequest(
            storageNode = ticket.storageNode,
            bucket = ticket.bucket,
            fileId = ticket.fileId,
            fileSize = source.size,
            isMultipart = chunkCount > 1,
            key = ticket.key,
            uploadId = ticket.uploadId,
        )
        return when (
            val result = api.post(
                path = Pan123Api.PATH_UPLOAD_COMPLETE_V2,
                token = token,
                body = json.encodeToString(UploadCompleteRequest.serializer(), complete),
                decode = { raw -> json.decodeFromString(ApiEnvelope.serializer(JsonUnit), raw) },
            )
        ) {
            is PanResult.Failure -> PanResult.Failure(result.error)
            is PanResult.Success -> PanResult.Success(ticket.toNode(parentId, source))
        }
    }

    private fun UploadTicket.toNode(parentId: NodeId?, source: UploadSource): RemoteNode =
        RemoteNode(
            id = NodeId(fileId.toString()),
            parentId = parentId,
            name = source.name,
            kind = NodeKind.FILE,
            size = source.size,
            mimeType = source.mimeType,
        )

    private companion object {
        /** 根目录在 123 云盘用字符串 "0" 表示。 */
        const val ROOT_ID = "0"

        /** 与 OpenList 一致：16 MiB 一片。太小会打爆请求数，太大会占内存。 */
        const val CHUNK_SIZE = 16L * 1024 * 1024

        /** 一次批量最多换 10 个分片的签名。 */
        const val BATCH_SIZE = 10
    }
}
