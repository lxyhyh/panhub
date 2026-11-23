package com.panhub.provider.baidu

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.UploadScanner
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.network.HttpEngine
import com.panhub.core.network.StreamRequestBody
import com.panhub.core.network.bounded
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.UploadDigestScan
import com.panhub.provider.api.UploadSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import java.io.IOException

/**
 * 百度网盘的上传流程（网页 Cookie 模式）。
 *
 * 四步，事实来源是 OpenList drivers/baidu_netdisk 的 Put / precreate /
 * uploadSlice / create，端点存在性另外用真实 HTTP 探测交叉验证过
 * （见 docs/recon/baidu.md）：
 *
 * 1. POST /rest/2.0/xpan/file?method=precreate 报 path/size/block_list，
 *    return_type=2 表示服务端已有同内容文件（秒传），不用再传；
 * 2. GET d.pcs.baidu.com/rest/2.0/pcs/file?method=locateupload 换上传域名；
 * 3. 每个分片 POST <域名>/rest/2.0/pcs/superfile2?method=upload&type=tmpfile，
 *    字节走 multipart 的 file 字段；
 * 4. POST /rest/2.0/xpan/file?method=create 报 uploadid 与 block_list 收尾。
 *
 * 分片大小非会员固定 4 MiB，会员更大；本项目统一按 4 MiB 切，会员账号也能用
 * （只是片数更多）。
 */
internal class BaiduUploader(
    private val engine: HttpEngine,
    private val api: BaiduApi,
    private val scanner: UploadScanner = UploadScanner.None,
) {

    suspend fun upload(
        cookie: String,
        parentPath: String,
        source: UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        if (source.size <= 0L) {
            return PanResult.Failure(PanError.notSupported("暂不支持上传空文件"))
        }

        val path = BaiduProvider.joinPath(parentPath, source.name)
        // 百度要的三个摘要（整文件 MD5、前 256 KiB 的 MD5、每 4 MiB 分片 MD5）
        // 正好是内核一次扫描的产物；没有内核时退回自己读流。
        val scanned = UploadDigestScan.scan(
            scanner = scanner,
            source = source,
            sliceSize = BaiduUploadDigest.SLICE_SIZE,
            prefixLimit = BaiduUploadDigest.SLICE_MD5_LIMIT,
        )
        val digests = if (scanned != null) {
            BaiduUploadDigests(
                contentMd5 = scanned.md5,
                sliceMd5 = scanned.prefixMd5,
                blockList = scanned.sliceMd5s,
            )
        } else {
            try {
                source.openStream().use { BaiduUploadDigest.compute(it, source.size) }
            } catch (io: IOException) {
                return PanResult.Failure(PanError.network(io.message ?: "读取本地文件失败", io))
            }
        }

        val pre = when (
            val result = precreate(cookie, path, source.size, digests)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        // return_type == 2：服务端按 MD5 命中已有文件，一个字节都不用传。
        if (pre.returnType == 2) {
            listener.onProgress(source.size, source.size)
            return PanResult.Success(nodeOf(pre.info, path, source))
        }

        val uploadId = pre.uploadId
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传会话号"))

        val host = when (val result = locateUpload(cookie, path, uploadId)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        val sliceSize = BaiduUploadDigest.SLICE_SIZE
        val count = BaiduUploadDigest.sliceCount(source.size, sliceSize)
        var sent = 0L

        for (index in 0 until count) {
            currentCoroutineContext().ensureActive()
            val offset = index.toLong() * sliceSize
            val length = minOf(sliceSize, source.size - offset)
            when (
                val result = putSlice(cookie, host, path, uploadId, index, source, offset, length)
            ) {
                is PanResult.Failure -> return result
                is PanResult.Success -> Unit
            }
            sent += length
            listener.onProgress(sent.coerceAtMost(source.size), source.size)
        }

        val created = when (
            val result = create(cookie, path, source.size, uploadId, digests.blockList)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        listener.onProgress(source.size, source.size)
        return PanResult.Success(nodeOf(created, path, source))
    }

    /** 第一步：报大小与分片清单，换 uploadid；命中已有文件时直接秒传。 */
    private suspend fun precreate(
        cookie: String,
        path: String,
        size: Long,
        digests: BaiduUploadDigests,
    ): PanResult<BaiduPrecreateResp> {
        val blockList = digests.blockList.joinToString(",", "[", "]") { "\"" + it + "\"" }
        val raw = when (
            val result = api.postForm(
                BaiduApi.PATH_MANAGE,
                cookie,
                listOf(
                    "path" to path,
                    "size" to size.toString(),
                    "isdir" to "0",
                    "autoinit" to "1",
                    "rtype" to "3",
                    "block_list" to blockList,
                    // 两个摘要只在首次上传时给：断点续传时服务端会按 uploadid 认，
                    // 重复报摘要反而会让它重新做一次秒传判定。
                    "content-md5" to digests.contentMd5,
                    "slice-md5" to digests.sliceMd5,
                ),
                listOf("method" to "precreate"),
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        return decode(raw, BaiduPrecreateResp.serializer())
    }

    /**
     * 第二步：换上传域名。
     *
     * 这个接口不需要认证，传 method 与 upload_version 就够；拿不到时退回
     * [DEFAULT_UPLOAD_HOST]，那台机器实测与返回的节点行为一致。
     */
    private suspend fun locateUpload(
        cookie: String,
        path: String,
        uploadId: String,
    ): PanResult<String> {
        val url = (LOCATE_URL + "?" + BaiduApi.encode("method") + "=locateupload" +
            "&" + BaiduApi.encode("appid") + "=" + BaiduApi.APP_ID +
            "&" + BaiduApi.encode("path") + "=" + BaiduApi.encode(path) +
            "&" + BaiduApi.encode("uploadid") + "=" + BaiduApi.encode(uploadId) +
            "&" + BaiduApi.encode("upload_version") + "=2.0")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BaiduApi.USER_AGENT)
            .header("Cookie", cookie)
            .get()
            .build()
        val raw = when (val result = engine.executeRaw(request)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value.use { response ->
                try {
                    response.body.string()
                } catch (io: IOException) {
                    return PanResult.Failure(
                        PanError.network(io.message ?: "读取响应失败", io),
                    )
                }
            }
        }
        val decoded = when (val result = decode(raw, BaiduLocateUploadResp.serializer())) {
            // 换域名失败不该让整个上传失败：退回默认节点继续传。
            is PanResult.Failure -> return PanResult.Success(DEFAULT_UPLOAD_HOST)
            is PanResult.Success -> result.value
        }
        val server = decoded.servers?.firstOrNull()?.server
            ?: decoded.bakServers?.firstOrNull()?.server
        return PanResult.Success(normalizeHost(server) ?: DEFAULT_UPLOAD_HOST)
    }

    /** 第三步：传一个分片。字节走 multipart 的 file 字段，不占内存。 */
    private suspend fun putSlice(
        cookie: String,
        host: String,
        path: String,
        uploadId: String,
        partSeq: Int,
        source: UploadSource,
        offset: Long,
        length: Long,
    ): PanResult<Unit> {
        val url = host + PATH_SLICE +
            "?method=upload" +
            "&type=tmpfile" +
            "&path=" + BaiduApi.encode(path) +
            "&uploadid=" + BaiduApi.encode(uploadId) +
            "&partseq=" + partSeq +
            "&app_id=" + BaiduApi.APP_ID

        val body = StreamRequestBody(
            openStream = { source.openStream().bounded(offset, length) },
            length = length,
            contentType = source.mimeType?.toMediaTypeOrNull(),
        )
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.name, body)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BaiduApi.USER_AGENT)
            .header("Cookie", cookie)
            .post(multipart)
            .build()

        val raw = when (val result = engine.executeRaw(request)) {
            is PanResult.Failure -> result
            is PanResult.Success -> result.value.use { response ->
                val text = try {
                    response.body.string()
                } catch (io: IOException) {
                    return@use PanResult.Failure(
                        PanError.network(io.message ?: "读取响应失败", io),
                    )
                }
                PanResult.Success(response.code to text)
            }
        }
        return when (raw) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val (code, text) = raw.value
                val slice = runCatching {
                    api.json.decodeFromString(BaiduSliceResp.serializer(), text)
                }.getOrNull()
                val ok = code in 200..299 && (slice == null || (slice.errorCode == 0 && slice.errno == 0))
                if (ok) {
                    PanResult.Success(Unit)
                } else {
                    PanResult.Failure(
                        PanError(
                            kind = if (code >= 500) ErrorKind.SERVER else ErrorKind.UNKNOWN,
                            message = "第 " + (partSeq + 1) + " 个分片上传失败（" + code + "）",
                            detail = text.take(300),
                            retryable = code >= 500,
                        ),
                    )
                }
            }
        }
    }

    /** 第四步：告诉百度「分片传完了」，服务端才开始合并与索引。 */
    private suspend fun create(
        cookie: String,
        path: String,
        size: Long,
        uploadId: String,
        blockList: List<String>,
    ): PanResult<BaiduUploadFile> {
        val blocks = blockList.joinToString(",", "[", "]") { "\"" + it + "\"" }
        val raw = when (
            val result = api.postForm(
                BaiduApi.PATH_MANAGE,
                cookie,
                listOf(
                    "path" to path,
                    "size" to size.toString(),
                    "isdir" to "0",
                    "rtype" to "3",
                    "uploadid" to uploadId,
                    "block_list" to blocks,
                ),
                listOf("method" to "create"),
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        return decode(raw, BaiduUploadFile.serializer())
    }

    private fun nodeOf(
        file: BaiduUploadFile?,
        path: String,
        source: UploadSource,
    ): RemoteNode = RemoteNode(
        id = NodeId(file?.path ?: path),
        parentId = null,
        name = file?.serverFilename ?: source.name,
        kind = NodeKind.FILE,
        size = file?.size?.takeIf { it > 0L } ?: source.size,
        mimeType = source.mimeType,
    )

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<T>): PanResult<T> =
        runCatching { api.json.decodeFromString(serializer, raw) }.fold(
            onSuccess = { PanResult.Success(it) },
            onFailure = { e -> PanResult.Failure(PanError.unknown("响应解析失败", raw.take(300), e)) },
        )

    /** locateupload 返回的可能是 https://host 也可能是裸域名，统一补成 https。 */
    internal fun normalizeHost(server: String?): String? {
        val value = server?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value.trimEnd('/')
        } else {
            "https://" + value.trimEnd('/')
        }
    }

    internal companion object {
        const val PATH_SLICE = "/rest/2.0/pcs/superfile2"

        const val LOCATE_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/file"

        /** locateupload 拿不到时用这个域名：实测与 d./c./pcs.baidu.com 行为一致。 */
        const val DEFAULT_UPLOAD_HOST = "https://d.pcs.baidu.com"
    }
}
