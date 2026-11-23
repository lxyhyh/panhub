package com.panhub.core.transfer

/**
 * 传输速率估算。
 *
 * 只保留最近一小段窗口里的采样，而不是拿「总字节 / 总耗时」：
 * 后者会被启动时的握手、中途的重试摊平，传了十分钟之后显示的数字
 * 和此刻的真实速度能差好几倍。窗口内用首尾两个采样算平均，
 * 既能反映「现在多快」，又不会被单个抖动的采样带偏。
 *
 * 刻意不做指数平滑：平滑系数是个没有正确答案的魔法数，
 * 而首尾差值在同一窗口内已经足够稳。
 */
class SpeedMeter(
    /** 参与计算的采样时间窗。太短会跳，太长会迟钝。 */
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {

    private val times = ArrayDeque<Long>()
    private val sizes = ArrayDeque<Long>()

    /** 记一次进度。同一时刻重复记相同字节数无害：跨度没变，速率就不变。 */
    fun record(nowMillis: Long, bytes: Long) {
        val last = times.lastOrNull()
        // 系统时间被改到过去时，旧采样全部作废重来，
        // 否则会算出一个负数或荒谬的速率。
        if (last != null && nowMillis < last) reset()
        times.addLast(nowMillis)
        sizes.addLast(bytes)
        // 丢掉窗口外的采样，但至少留两个，否则刚过窗口就永远算不出速率。
        while (times.size > MIN_SAMPLES && nowMillis - times.first() > windowMillis) {
            times.removeFirst()
            sizes.removeFirst()
        }
    }

    /**
     * 当前速率（字节/秒）。
     *
     * 返回 null 表示「还测不出来」——只有一个采样或跨度太短，
     * 此时界面该显示不确定进度而不是 0 B/s。返回 0 则是「确实卡住了」。
     */
    fun bytesPerSecond(): Long? {
        if (times.size < MIN_SAMPLES) return null
        val span = times.last() - times.first()
        if (span < MIN_SPAN_MILLIS) return null
        val delta = sizes.last() - sizes.first()
        if (delta <= 0L) return 0L
        return delta * 1000L / span
    }

    /** 重新开始计数：换了传输阶段或重试时，字节数会归零，旧采样不能再参与。 */
    fun reset() {
        times.clear()
        sizes.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 5_000L

        private const val MIN_SAMPLES = 2

        /** 跨度太短时除以一个几毫秒的分母，速率会飙到天上。 */
        private const val MIN_SPAN_MILLIS = 500L
    }
}
