package com.panhub.provider.cloud189

import com.panhub.core.common.ErrorKind
import com.panhub.core.common.PanError
import com.panhub.core.common.KotlinUploadScanner
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import kotlin.random.Random

/**
 * 天翼云盘的上传流程。
 *
 * 走的是和列目录完全不同的加密通道（upload.cloud.189.cn）：
 * 表单先 AES-ECB 加密成十六进制 params，再用 HMAC-SHA1 签名，
 * 随机密钥本身用服务端下发的 RSA 公钥加密后放进 EncryptionText 头。
 * 密码学细节见 [Cloud189UploadCrypto]，摘要规则见 [Cloud189UploadDigest]。
 *
 * 四步（依据 OpenList drivers/189 的 newUpload / uploadRequest）：
 * 1. initMultiUpload 报文件名、大小与两组摘要，服务端据此判断能否秒传；
 * 2. fileDataExists == 1 时直接 commit，一个字节都不用传；
 * 3. 否则逐片 getMultiUploadUrls 换预签名地址，PUT 上去；
 * 4. commitMultiUploadFile 通知服务端合并。
 */
internal class Cloud189Uploader(
    private val engine: HttpEngine,
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scanner: UploadScanner = UploadScanner.None,
) {

    /** sessionKey 与 RSA 公钥都随会话有效，缓存起来省掉每片一次往返。 */
    private var sessionKey: String? = null
    private var rsaKey: C189RsaKeyDto? = null

    suspend fun upload(
        cookie: String,
        parentId: NodeId?,
        source: UploadSource,
        listener: PanProvider.UploadProgress,
    ): PanResult<RemoteNode> {
        if (source.size <= 0L) {
            return PanResult.Failure(PanError.notSupported("暂不支持上传空文件"))
        }
        val parentFolderId = parentId?.let { Cloud189Parsing.rawId(it.value) }
            ?: Cloud189Api.ROOT_FOLDER_ID

        val key = when (val result = ensureSessionKey(cookie)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }

        // 摘要只算一遍：秒传判断和分片都要用，重算等于把文件读两遍。
        // 进度回调是挂起函数，不能从非挂起的 compute 里直接调，所以先记账再补报一次。
        // 有内核时这一遍走 Rust，分片摘要也一起带回来，下面换上传地址时直接用，
        // 不必再为每个分片重开一次流。
        val scanned = UploadDigestScan.scan(
            scanner = scanner,
            source = source,
            sliceSize = Cloud189UploadDigest.SLICE_SIZE,
            prefixLimit = 0L,
        )
        val digests = if (scanned != null) {
            Cloud189UploadDigests(
                fileMd5 = scanned.md5,
                sliceMd5 = Cloud189UploadDigest.sliceMd5Of(
                    sliceMd5s = scanned.sliceMd5s.map { it.uppercase() },
                    fileMd5 = scanned.md5,
                    size = source.size,
                ),
                sliceMd5s = scanned.sliceMd5s.map { it.uppercase() },
            )
        } else {
            try {
                source.openStream().use { input ->
                    Cloud189UploadDigest.compute(input, source.size)
                }
            } catch (io: IOException) {
                return PanResult.Failure(PanError.network(io.message ?: "读取本地文件失败", io))
            }
        }
        // 摘要阶段占进度条前 10%，剩下的留给真正的传输。
        listener.onProgress(source.size / 10L, source.size)

        val init = when (
            val result = call(
                cookie = cookie,
                sessionKey = key,
                uri = PATH_INIT,
                form = listOf(
                    "parentFolderId" to parentFolderId,
                    "fileName" to Cloud189UploadCrypto.queryEscape(source.name),
                    "fileSize" to source.size.toString(),
                    "sliceSize" to Cloud189UploadDigest.SLICE_SIZE.toString(),
                    "fileMd5" to digests.fileMd5,
                    "sliceMd5" to digests.sliceMd5,
                ),
            )
        ) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        val uploadFileId = init.data?.uploadFileId
            ?: return PanResult.Failure(PanError.unknown("服务端没有下发上传任务号"))

        // 秒传：服务端已经有同内容文件，直接提交索引。
        if (init.data?.fileDataExists == 1) {
            listener.onProgress(source.size, source.size)
            return commit(cookie, key, uploadFileId, digests, parentId, source)
        }

        val total = source.size
        val sliceSize = Cloud189UploadDigest.SLICE_SIZE
        val count = ((total + sliceSize - 1) / sliceSize).toInt()
        var sent = 0L

        for (index in 1..count) {
            currentCoroutineContext().ensureActive()
            val offset = (index - 1).toLong() * sliceSize
            val length = minOf(sliceSize, total - offset)
            // 扫描时已经算过分片摘要就换算成 Base64，省掉每片一次重开流。
            val sliceMd5 = digests.sliceMd5s.getOrNull(index - 1)?.let { hex ->
                Base64.getEncoder().encodeToString(KotlinUploadScanner.hexToBytes(hex.lowercase()))
            } ?: sliceDigest(source, offset, length)
                ?: return PanResult.Failure(PanError.network("读取第 " + index + " 个分片失败"))

            val urls = when (
                val result = call(
                    cookie = cookie,
                    sessionKey = key,
                    uri = PATH_URLS,
                    form = listOf(
                        "partInfo" to index.toString() + "-" + sliceMd5,
                        "uploadFileId" to uploadFileId,
                    ),
                )
            ) {
                is PanResult.Failure -> return result
                is PanResult.Success -> result.value
            }
            val target = urls.uploadUrls?.get("partNumber_" + index)
                ?: return PanResult.Failure(PanError.unknown("服务端没有下发第 " + index + " 个分片的上传地址"))
            val requestUrl = target.requestUrl
                ?: return PanResult.Failure(PanError.unknown("第 " + index + " 个分片的上传地址为空"))

            val failure = putSlice(
                requestUrl = requestUrl,
                requestHeader = target.requestHeader,
                source = source,
                offset = offset,
                length = length,
                index = index,
            )
            if (failure != null) return PanResult.Failure(failure)

            sent += length
            listener.onProgress(sent.coerceAtMost(total), total)
        }

        return commit(cookie, key, uploadFileId, digests, parentId, source)
    }

    /** 合并分片。秒传与真传走的是同一个端点。 */
    private suspend fun commit(
        cookie: String,
        key: String,
        uploadFileId: String,
        digests: Cloud189UploadDigests,
        parentId: NodeId?,
        source: UploadSource,
    ): PanResult<RemoteNode> = when (
        val result = call(
            cookie = cookie,
            sessionKey = key,
            uri = PATH_COMMIT,
            form = listOf(
                "uploadFileId" to uploadFileId,
                "fileMd5" to digests.fileMd5,
                "sliceMd5" to digests.sliceMd5,
                "lazyCheck" to "1",
                "opertype" to "3",
            ),
        )
    ) {
        is PanResult.Failure -> result
        is PanResult.Success -> PanResult.Success(
            RemoteNode(
                // 合并接口不回 id，只能拿文件名占位；列表刷新后会被真实 id 覆盖。
                id = NodeId(Cloud189Parsing.fileNodeId(source.name)),
                parentId = parentId,
                name = source.name,
                kind = NodeKind.FILE,
                size = source.size,
                mimeType = source.mimeType,
            ),
        )
    }

    /**
     * 拿 sessionKey，顺带确认 Cookie 还能用。
     *
     * 注意这个接口未登录时返回 200 + 空对象，所以「拿不到 sessionKey」
     * 才是判断登录失效的依据，不能看状态码。
     */
    private suspend fun ensureSessionKey(cookie: String): PanResult<String> {
        sessionKey?.let { return PanResult.Success(it) }
        val raw = Cloud189Api(engine).get(Cloud189Api.PATH_USER_BRIEF, cookie)
        val body = when (raw) {
            is PanResult.Failure -> return raw
            is PanResult.Success -> raw.value
        }
        val key = runCatching {
            val element = Cloud189Api(engine).json.parseToJsonElement(body)
            Cloud189Api.textOf((element as? kotlinx.serialization.json.JsonObject)?.get("sessionKey"))
        }.getOrNull()
        if (key.isNullOrBlank()) {
            return PanResult.Failure(PanError.auth("登录态已失效，请重新粘贴 Cookie"))
        }
        sessionKey = key
        return PanResult.Success(key)
    }

    /** 公钥有 expire（毫秒时间戳），没过期就复用。 */
    private suspend fun ensureRsaKey(cookie: String): PanResult<C189RsaKeyDto> {
        val cached = rsaKey
        if (cached != null && (cached.expire ?: 0L) > clock()) {
            return PanResult.Success(cached)
        }
        val api = Cloud189Api(engine)
        return when (val raw = api.get(Cloud189Api.PATH_RSA_KEY, cookie)) {
            is PanResult.Failure -> raw
            is PanResult.Success -> {
                val dto = runCatching {
                    api.json.decodeFromString(C189RsaKeyDto.serializer(), raw.value)
                }.getOrNull()
                if (dto?.pubKey.isNullOrBlank() || dto?.pkId.isNullOrBlank()) {
                    PanResult.Failure(PanError.auth("登录态已失效，请重新粘贴 Cookie"))
                } else {
                    rsaKey = dto
                    PanResult.Success(dto)
                }
            }
        }
    }

    /**
     * 发一个上传侧请求。
     *
     * 顺序不能动：先加密表单拿到 params，再对「五段式」签名串做 HMAC，
     * 最后把随机密钥 RSA 加密后塞进头里；服务端靠这三样还原并验签。
     */
    private suspend fun call(
        cookie: String,
        sessionKey: String,
        uri: String,
        form: List<Pair<String, String>>,
    ): PanResult<C189UploadEnvelope> {
        val rsa = when (val result = ensureRsaKey(cookie)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value
        }
        val secret = Cloud189UploadCrypto.randomToken(
            random,
            Cloud189UploadCrypto.SECRET_MIN_LENGTH +
                random.nextInt(Cloud189UploadCrypto.SECRET_SPAN),
        )
        val paramsHex = Cloud189UploadCrypto.aesEcbHex(
            Cloud189UploadCrypto.paramString(form),
            secret.substring(0, Cloud189UploadCrypto.SECRET_MIN_LENGTH),
        )
        val date = clock().toString()
        val signature = Cloud189UploadCrypto.signature(sessionKey, uri, date, paramsHex, secret)
        val encryptionText = Cloud189UploadCrypto.rsaEncryptBase64(
            secret.toByteArray(Charsets.UTF_8),
            rsa.pubKey.orEmpty(),
        ) ?: return PanResult.Failure(PanError.unknown("无法加密上传凭证"))

        val url = try {
            (UPLOAD_BASE + uri).toHttpUrl().newBuilder()
                .addQueryParameter("params", paramsHex)
                .build()
        } catch (e: IllegalArgumentException) {
            return PanResult.Failure(PanError.unknown("请求地址不合法", uri, e))
        }

        val request = Request.Builder()
            .url(url)
            .header("accept", "application/json;charset=UTF-8")
            .header("SessionKey", sessionKey)
            .header("Signature", signature)
            .header("X-Request-Date", date)
            .header("X-Request-ID", Cloud189UploadCrypto.randomRequestId(random))
            .header("EncryptionText", encryptionText)
            .header("PkId", rsa.pkId.orEmpty())
            .header("Cookie", cookie)
            .get()
            .build()

        val body = when (val result = engine.fetchText(request)) {
            is PanResult.Failure -> return result
            is PanResult.Success -> result.value.body
        }
        return Cloud189UploadParsing.decode(Cloud189Api(engine).json, body)
    }

    /** 单片 MD5 的 Base64，getMultiUploadUrls 用它校验分片内容。 */
    private fun sliceDigest(source: UploadSource, offset: Long, length: Long): String? = try {
        source.openStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            val stream = input.bounded(offset, length)
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
            Base64.getEncoder().encodeToString(digest.digest())
        }
    } catch (io: IOException) {
        null
    }

    /** PUT 一个分片。返回 null 表示成功。 */
    private suspend fun putSlice(
        requestUrl: String,
        requestHeader: String?,
        source: UploadSource,
        offset: Long,
        length: Long,
        index: Int,
    ): PanError? {
        val url = try {
            requestUrl.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            return PanError.unknown("第 " + index + " 个分片的上传地址不合法", requestUrl, e)
        }
        val builder = Request.Builder().url(url)
        // 服务端把要带的头整体做过一次 URL 编码，用 & 分隔、第一个 = 分隔名字与值。
        Cloud189UploadParsing.parseHeaders(requestHeader).forEach { (name, value) ->
            builder.header(name, value)
        }
        val body = StreamRequestBody(
            openStream = { source.openStream().bounded(offset, length) },
            length = length,
        )
        builder.put(body)

        val text = when (val result = engine.fetchText(builder.build())) {
            is PanResult.Failure -> return result.error
            is PanResult.Success -> result.value
        }
        if (text.code in 200..299) return null
        return PanError(
            kind = if (text.code >= 500) ErrorKind.SERVER else ErrorKind.UNKNOWN,
            message = "第 " + index + " 个分片上传失败（" + text.code + "）",
            detail = text.body.take(300),
            retryable = text.code >= 500,
        )
    }

    companion object {
        const val UPLOAD_BASE = "https://upload.cloud.189.cn"

        const val PATH_INIT = "/person/initMultiUpload"
        const val PATH_URLS = "/person/getMultiUploadUrls"
        const val PATH_COMMIT = "/person/commitMultiUploadFile"
    }
}
