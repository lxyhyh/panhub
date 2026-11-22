package com.panhub.provider.quark

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * OSS 侧的签名串与地址拼装。
 *
 * 单独拿出来是因为这里全是「少一个字符就签名不匹配」的字符串拼接：
 * 混在传文件的流程里既没法单测，也容易被下一次改动顺手改坏。
 * 格式依据 OpenList drivers/quark_uc/util.go 的 upPart / upCommit。
 */
internal object QuarkOss {

    const val USER_AGENT = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"

    /**
     * 从 upload/pre 返回的 upload_url 里取出 endpoint 主机。
     *
     * 参考实现写死 [7:] 砍掉「http://」，这里改成按 scheme 前缀剥，
     * 免得上游哪天换成 https 就拼出一个错地址。
     */
    fun host(uploadUrl: String?): String? {
        val raw = uploadUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val host = when {
            raw.startsWith("https://") -> raw.removePrefix("https://")
            raw.startsWith("http://") -> raw.removePrefix("http://")
            else -> raw
        }
        return host.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    /** 预签名的实际地址：[partNumber] 为空时是合并分片的入口。 */
    fun url(host: String, bucket: String, objKey: String, partNumber: Int?, uploadId: String): String? =
        try {
            val base: HttpUrl = ("https://" + bucket + "." + host).toHttpUrl()
            val builder = base.newBuilder().addPathSegments(objKey)
            if (partNumber != null) {
                builder.addQueryParameter("partNumber", partNumber.toString())
            }
            builder.addQueryParameter("uploadId", uploadId)
            builder.build().toString()
        } catch (e: IllegalArgumentException) {
            null
        }

    /**
     * 分片 PUT 的待签串。
     *
     * 结构是 OSS V1 规范：方法、Content-MD5（这里恒为空）、Content-Type、
     * Date、按字典序排的 x-oss- 头、资源路径。换行一个都不能少。
     */
    fun partAuthMeta(
        mime: String,
        timeStr: String,
        bucket: String,
        objKey: String,
        partNumber: Int,
        uploadId: String,
    ): String = "PUT\n\n" + mime + "\n" + timeStr +
        "\nx-oss-date:" + timeStr +
        "\nx-oss-user-agent:" + USER_AGENT +
        "\n/" + bucket + "/" + objKey +
        "?partNumber=" + partNumber + "&uploadId=" + uploadId

    /**
     * 合并分片的待签串。
     *
     * 比 PUT 多两处：Content-MD5 是 body 的 base64 MD5，
     * 还多一个 x-oss-callback（回调描述本身的 base64）。
     */
    fun commitAuthMeta(
        contentMd5: String,
        timeStr: String,
        callbackBase64: String,
        bucket: String,
        objKey: String,
        uploadId: String,
    ): String = "POST\n" + contentMd5 + "\napplication/xml\n" + timeStr +
        "\nx-oss-callback:" + callbackBase64 +
        "\nx-oss-date:" + timeStr +
        "\nx-oss-user-agent:" + USER_AGENT +
        "\n/" + bucket + "/" + objKey + "?uploadId=" + uploadId

    /** CompleteMultipartUpload 的请求体；每个 Part 之间都要换行。 */
    fun commitXml(etags: List<String>): String {
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<CompleteMultipartUpload>\n")
        etags.forEachIndexed { index, etag ->
            builder.append("<Part>\n<PartNumber>").append(index + 1).append("</PartNumber>\n")
            builder.append("<ETag>").append(etag).append("</ETag>\n</Part>\n")
        }
        builder.append("</CompleteMultipartUpload>")
        return builder.toString()
    }
}
