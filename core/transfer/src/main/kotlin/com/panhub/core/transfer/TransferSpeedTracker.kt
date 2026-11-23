package com.panhub.core.transfer

/**
 * 按任务统计速率。
 *
 * 每个正在跑的任务一个 [SpeedMeter]；任务结束或被清出列表时把它的表丢掉，
 * 否则一张长列表跑一下午会攒下几百个再也不会被读的计时器。
 *
 * 只在这里做「谁在跑」的判断，界面不需要自己维护一份 id 到计速器的映射。
 */
class TransferSpeedTracker(
    private val windowMillis: Long = SpeedMeter.DEFAULT_WINDOW_MILLIS,
) {

    private val meters = HashMap<String, SpeedMeter>()

    /**
     * 喂一次队列快照，返回每个进行中任务的速率（字节/秒）。
     *
     * 速率还是 null 的任务不会出现在返回值里——「还测不出来」和「0 B/s」
     * 是两件事，界面看到没有这一项就该什么都不显示。
     */
    fun sample(tasks: List<TransferTask>, nowMillis: Long): Map<String, Long> {
        val alive = HashSet<String>()
        val speeds = HashMap<String, Long>()
        for (task in tasks) {
            // 只统计真正在跑的那个：排队中的任务字节数不会动，
            // 给它记采样只会在轮到它时立刻报出一个 0 B/s 的假速率。
            if (task.state != TransferState.RUNNING) continue
            alive += task.id
            val meter = meters.getOrPut(task.id) { SpeedMeter(windowMillis) }
            meter.record(nowMillis, task.bytes)
            meter.bytesPerSecond()?.let { speeds[task.id] = it }
        }
        meters.keys.retainAll(alive)
        return speeds
    }

    /** 队列被清空或换了账号时整体重来。 */
    fun reset() = meters.clear()
}
