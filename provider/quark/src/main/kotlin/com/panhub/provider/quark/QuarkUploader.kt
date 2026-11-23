package com.panhub.provider.quark

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.FileDigest
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.UploadScanner
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.RemoteNode
import com.panhub.core.network.StreamRequestBody
import com.panhub.core.network.bounded
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.UploadDigestScan
import com.panhub.provider.api.UploadSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 夸克网盘的上传流程。
 *
 * 和 123 云盘不同，夸克把分片交给阿里 OSS：夸克只发「上传票据」和每个分片的签名，
 * 真正的字节走预签名地址。四步（依据 OpenList drivers/quark_uc 的
 * Put / upPre / upHash / upPart / upCommit / upFinish）：
 *
 * 1. POST /file/upload/pre 拿 task_id、bucket、obj_key、upload_id、auth_info；
 * 2. POST /file/update/hash 报 MD5 与 SHA-1，命中已有文件就直接秒传；
 * 3. 每个分片先 POST /file/upload/auth 换 auth_key，再 PUT 到 OSS；
 * 4. 先换一次 POST 签名，向 OSS 提交 CompleteMultipartUpload，最后 POST /file/upload/finish。
 *
 * 签名串怎么拼见 [QuarkOss]：那部分单独出去是因为它是纯字符串逻辑，能单测。
 */
internal class QuarkUploader(
    private val api: QuarkApi,
    private val scanner: UploadScanner = UploadScanner.None,
) {

    suspend fun upload(
        parentId: NodeId?,
        source: UploadSource,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        if (source.size <= 0L) {
            return PanResult.Failure(PanError.notSupported("暂不支持上传空文件"))
        }

        // 摘要只算一遍：服务端同时要 MD5 和 SHA-1 才肯认秒传。
        // 有内核时这一遍走 Rust，算完顺手把 SHA-1 也带回来，不用读第二次。
        val scanned = UploadDigestScan.scan(scanner, source, sliceSize = 0L, prefixLimit = 0L)
        val digests = if (scanned != null) {
            scanned.md5 to scanned.sha1
        } else {
            try {
                source.openStream().use { FileDigest.md5AndSha1(it) }
            } catch (io: IOException) {
                return PanResult.Failure(PanError.network(io.message ?: "读取本地文件失败", io))
            }
        }

        val ticket = when (val result = pre(source, parentId, cookieProvider, onRenewed)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        val taskId = ticket.first.taskId
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传任务号"))

        // finish=true：服务端认为这个文件不用传（同内容同目录），直接当成完成。
        if (ticket.first.finish) {
            listener.onProgress(source.size, source.size)
            return PanResult.Success(nodeOf(ticket.first.fid, parentId, source))
        }

        val deduped = when (
            val result = hash(taskId, digests, cookieProvider, onRenewed)
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        if (deduped) {
            listener.onProgress(source.size, source.size)
            return PanResult.Success(nodeOf(ticket.first.fid, parentId, source))
        }

        val pre = ticket.first
        val bucket = pre.bucket
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发存储桶"))
        val objKey = pre.objKey
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发对象路径"))
        val uploadId = pre.uploadId
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发分片会话号"))
        pre.authInfo ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传凭证"))
        val host = QuarkOss.host(pre.uploadUrl)
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传地址"))

        val mime = mimeOf(source)
        val partSize = ticket.second.partSize?.takeIf { it > 0L } ?: DEFAULT_PART_SIZE
        val total = source.size
        val partCount = ((total + partSize - 1) / partSize).toInt()
        val etags = ArrayList<String>(partCount)
        var sent = 0L

        for (index in 1..partCount) {
            currentCoroutineContext().ensureActive()
            val offset = (index - 1).toLong() * partSize
            val length = minOf(partSize, total - offset)
            val timeStr = httpDate()
            val authMeta = QuarkOss.partAuthMeta(mime, timeStr, bucket, objKey, index, uploadId)
            val authKey = when (val result = auth(pre, taskId, authMeta, cookieProvider, onRenewed)) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
            val url = QuarkOss.url(host, bucket, objKey, index, uploadId)
                ?: return PanResult.Failure(PanError.unknown("上传地址不合法"))

            val body = StreamRequestBody(
                openStream = { source.openStream().bounded(offset, length) },
                length = length,
                contentType = mime.toMediaTypeOrNull(),
            )
            val response = when (
                val result = api.executeAbs(
                    method = "PUT",
                    url = url,
                    headers = mapOf(
                        "Authorization" to authKey,
                        "Content-Type" to mime,
                        "Referer" to api.brand.referer + "/",
                        "x-oss-date" to timeStr,
                        "x-oss-user-agent" to QuarkOss.USER_AGENT,
                    ),
                    body = body,
                )
            ) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
            if (!response.isSuccessful) {
                return PanResult.Failure(
                    PanError(
                        kind = if (response.code >= 500) ErrorKind.SERVER else ErrorKind.UNKNOWN,
                        message = "第 " + index + " 个分片上传失败（" + response.code + "）",
                        detail = response.text.take(300),
                        retryable = response.code >= 500,
                    ),
                )
            }
            etags += response.etag
                ?: return PanResult.Failure(PanError.unknown("第 " + index + " 个分片没有返回 ETag"))

            sent += length
            listener.onProgress(sent.coerceAtMost(total), total)
        }

        when (val result = commit(pre, taskId, bucket, objKey, uploadId, host, etags, cookieProvider, onRenewed)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> Unit
        }

        return when (val result = finish(pre, taskId, cookieProvider, onRenewed)) {
            is PanResult.Failure -> result
            is PanResult.Success -> {
                listener.onProgress(total, total)
                PanResult.Success(nodeOf(pre.fid, parentId, source))
            }
        }
    }

    /** 第一步：换上传票据；分片大小在 metadata 里，不在 data 里。 */
    private suspend fun pre(
        source: UploadSource,
        parentId: NodeId?,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Pair<QuarkUploadPre, QuarkUploadPreMeta>> {
        val cookie = cookieProvider()
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("ccp_hash_update", JsonPrimitive(true))
            put("dir_name", JsonPrimitive(""))
            put("file_name", JsonPrimitive(source.name))
            put("format_type", JsonPrimitive(mimeOf(source)))
            put("l_created_at", JsonPrimitive(now))
            put("l_updated_at", JsonPrimitive(now))
            put("pdir_fid", JsonPrimitive(parentId?.value ?: QuarkApi.ROOT_ID))
            put("size", JsonPrimitive(source.size))
        }.toString()

        val raw = when (val result = api.postJson(QuarkApi.PATH_UPLOAD_PRE, cookie, body)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        onRenewed(raw.renewed)
        return when (val decoded = api.decode(raw, QuarkUploadPreEnvelope.serializer())) {
            is PanResult.Failure -> decoded
            is PanResult.Success -> {
                val ticket = decoded.value.data
                    ?: return PanResult.Failure(PanError.unknown("服务端没有返回上传票据"))
                PanResult.Success(ticket to (decoded.value.metadata ?: QuarkUploadPreMeta()))
            }
        }
    }

    /** 第二步：报摘要，true 表示服务端已有同内容文件、无需再传。 */
    private suspend fun hash(
        taskId: String,
        digests: Pair<String, String>,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Boolean> {
        val cookie = cookieProvider()
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("md5", JsonPrimitive(digests.first))
            put("sha1", JsonPrimitive(digests.second))
            put("task_id", JsonPrimitive(taskId))
        }.toString()

        val raw = when (val result = api.postJson(QuarkApi.PATH_UPLOAD_HASH, cookie, body)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        onRenewed(raw.renewed)
        return when (val decoded = api.decode(raw, QuarkEnvelope.serializer(QuarkHashData.serializer()))) {
            is PanResult.Failure -> decoded
            is PanResult.Success -> PanResult.Success(decoded.value.data?.finish == true)
        }
    }

    /** 第三步与收尾共用：换一次 OSS 的 Authorization。 */
    private suspend fun auth(
        ticket: QuarkUploadPre,
        taskId: String,
        authMeta: String,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<String> {
        val cookie = cookieProvider()
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("auth_info", JsonPrimitive(ticket.authInfo.orEmpty()))
            put("auth_meta", JsonPrimitive(authMeta))
            put("task_id", JsonPrimitive(taskId))
        }.toString()

        val raw = when (val result = api.postJson(QuarkApi.PATH_UPLOAD_AUTH, cookie, body)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        onRenewed(raw.renewed)
        return when (val decoded = api.decode(raw, QuarkEnvelope.serializer(QuarkUploadAuth.serializer()))) {
            is PanResult.Failure -> decoded
            is PanResult.Success -> {
                val key = decoded.value.data?.authKey
                if (key.isNullOrBlank()) {
                    PanResult.Failure(PanError.unknown("服务端没有下发上传签名"))
                } else {
                    PanResult.Success(key)
                }
            }
        }
    }

    /** 收尾第一步：向 OSS 提交分片清单。 */
    private suspend fun commit(
        ticket: QuarkUploadPre,
        taskId: String,
        bucket: String,
        objKey: String,
        uploadId: String,
        host: String,
        etags: List<String>,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Unit> {
        val callback = ticket.callback
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传回调信息"))
        val callbackJson = buildJsonObject {
            put("callbackUrl", JsonPrimitive(callback.callbackUrl.orEmpty()))
            put("callbackBody", JsonPrimitive(callback.callbackBody.orEmpty()))
        }.toString()
        val callbackBase64 = base64(callbackJson.toByteArray(Charsets.UTF_8))

        val xml = QuarkOss.commitXml(etags)
        val contentMd5 = base64(MessageDigest.getInstance("MD5").digest(xml.toByteArray(Charsets.UTF_8)))

        val timeStr = httpDate()
        val authMeta = QuarkOss.commitAuthMeta(contentMd5, timeStr, callbackBase64, bucket, objKey, uploadId)
        val authKey = when (val result = auth(ticket, taskId, authMeta, cookieProvider, onRenewed)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        val url = QuarkOss.url(host, bucket, objKey, partNumber = null, uploadId = uploadId)
            ?: return PanResult.Failure(PanError.unknown("上传地址不合法"))
        val response = when (
            val result = api.executeAbs(
                method = "POST",
                url = url,
                headers = mapOf(
                    "Authorization" to authKey,
                    "Content-MD5" to contentMd5,
                    "Content-Type" to "application/xml",
                    "Referer" to api.brand.referer + "/",
                    "x-oss-callback" to callbackBase64,
                    "x-oss-date" to timeStr,
                    "x-oss-user-agent" to QuarkOss.USER_AGENT,
                ),
                body = xml.toByteArray(Charsets.UTF_8).toRequestBody(),
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        if (!response.isSuccessful) {
            return PanResult.Failure(
                PanError(
                    kind = if (response.code >= 500) ErrorKind.SERVER else ErrorKind.UNKNOWN,
                    message = "合并分片失败（" + response.code + "）",
                    detail = response.text.take(300),
                    retryable = response.code >= 500,
                ),
            )
        }
        return PanResult.Success(Unit)
    }

    /** 收尾第二步：告诉夸克「OSS 那边传完了」，服务端才开始索引这个文件。 */
    private suspend fun finish(
        ticket: QuarkUploadPre,
        taskId: String,
        cookieProvider: () -> String?,
        onRenewed: (Map<String, String>) -> Unit,
    ): PanResult<Unit> {
        val cookie = cookieProvider()
            ?: return PanResult.Failure(PanError.auth("会话缺少 Cookie"))
        val body = buildJsonObject {
            put("obj_key", JsonPrimitive(ticket.objKey.orEmpty()))
            put("task_id", JsonPrimitive(taskId))
        }.toString()

        val raw = when (val result = api.postJson(QuarkApi.PATH_UPLOAD_FINISH, cookie, body)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        onRenewed(raw.renewed)
        return PanResult.Success(Unit)
    }

    private fun nodeOf(fid: String?, parentId: NodeId?, source: UploadSource): RemoteNode =
        RemoteNode(
            id = NodeId(fid ?: source.name),
            parentId = parentId,
            name = source.name,
            kind = NodeKind.FILE,
            size = source.size,
            mimeType = source.mimeType,
        )

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** OSS 的 Date 头必须是 RFC 1123 的 GMT，和待签串里用的字符串完全一致。 */
    internal fun httpDate(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.format(Date())

    /**
     * 服务端按 format_type 决定文件的展示方式；没给就按扩展名猜一个，
     * 猜不出时用八位字节流（比乱标成 text/plain 安全）。
     */
    internal fun mimeOf(source: UploadSource): String {
        val given = source.mimeType?.takeIf { it.isNotBlank() }
        if (given != null) return given
        val ext = source.name.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "txt", "md" -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        /** 服务端没给分片大小时退回 5 MiB：OSS 对非末片的最小要求就是它。 */
        const val DEFAULT_PART_SIZE = 5L * 1024 * 1024
    }
}
