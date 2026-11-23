package com.panhub.core.preview

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId

/**
 * 取一份预览内容。
 *
 * 抽成接口的理由和下载器一样：预览的截断/类型判定/失败分支要能在普通单测里
 * 覆盖，不该每条用例都起一个 HTTP 服务。
 */
interface PreviewLoader {
    suspend fun load(
        accountId: AccountId,
        nodeId: NodeId,
        kind: PreviewKind,
        knownSize: Long? = null,
    ): PanResult<PreviewContent>
}

/**
 * 预览内容的装配。
 *
 * 与 [PreviewLoader] 分开是为了让"怎么读字节"和"字节怎么变成内容"各自可测：
 * 前者依赖网络，后者只依赖一段 ByteArray。
 */
object PreviewAssembler {

    /**
     * @param bytes 已经读到的字节（最多 [PreviewLimits.forKind] 那么多）
     * @param truncated 服务端还有更多内容没读
     */
    fun assemble(kind: PreviewKind, bytes: ByteArray, truncated: Boolean): PanResult<PreviewContent> =
        when (kind) {
            PreviewKind.IMAGE -> PanResult.Success(
                PreviewContent(kind = kind, bytes = bytes, truncated = truncated),
            )

            PreviewKind.TEXT -> {
                if (PreviewText.looksBinary(bytes)) {
                    // 标成文本但其实是二进制：与其在界面上画一屏方框，不如直接说清。
                    PanResult.Failure(PanError.notSupported("这个文件看起来不是文本，没法预览"))
                } else {
                    PanResult.Success(
                        PreviewContent(
                            kind = kind,
                            text = PreviewText.normalize(PreviewText.decode(bytes)),
                            truncated = truncated,
                        ),
                    )
                }
            }

            PreviewKind.NONE -> PanResult.Failure(PanError.notSupported("这个文件不支持预览"))
        }
}
