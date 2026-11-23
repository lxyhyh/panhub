package com.panhub.provider.api

import com.panhub.core.common.UploadScan
import com.panhub.core.common.UploadScanner

/**
 * 上传摘要的取数规则：能用内核就用内核，用不了就让调用方自己读流。
 *
 * 为什么单独抽出来：四个网盘的上传器都要做同一件事——判断这个来源有没有
 * 本地文件、能不能交给扫描器、拿到结果后字段怎么对齐。这套判断写四遍迟早
 * 会走样（比如某一家忘了校验大小），集中一处只要读一遍。
 *
 * 所有函数在「拿不到内核结果」时返回 null，调用方必须保留原有的纯 Kotlin
 * 路径。宁可慢一点，也不能因为内核没编出来就传不上去。
 */
object UploadDigestScan {

    /**
     * 扫描 [source]。
     *
     * [sliceSize] 传 0 表示不切分片，[prefixLimit] 传 0 表示不算前缀摘要。
     * 来源不是本地文件、扫描器不支持、扫描结果大小与声明不符时返回 null。
     */
    fun scan(
        scanner: UploadScanner,
        source: UploadSource,
        sliceSize: Long,
        prefixLimit: Long,
    ): UploadScan? {
        val file = source.localFile ?: return null
        val scan = scanner.scan(file, sliceSize, prefixLimit) ?: return null
        // 扫描的是磁盘上的当前内容，声明的大小是上传时要发的字节数。
        // 两者不一致说明文件在排队期间被改过，这份摘要不能用。
        if (scan.size != source.size) return null
        return scan
    }
}
