package com.panhub.core.transfer

import kotlinx.coroutines.CancellationException

/**
 * 「这次中断是用户按了暂停，不是不要了」。
 *
 * 暂停与取消都靠取消协程来实现——协程是唯一能立刻停住 IO 的手段。
 * 但两者在磁盘上必须留下不同的结果：取消要删掉没下完的 .part，
 * 暂停要留着，否则「先停一下，等 Wi-Fi 再传」就等于从头再来。
 *
 * 所以暂停时用这个 [CancellationException] 的子类取消协程：
 * 它依然是标准的取消异常（协程机制照常收尾），只是带了「别删残片」的记号。
 */
internal class TransferPause : CancellationException("transfer paused") {
    override fun fillInStackTrace(): Throwable = this
}
