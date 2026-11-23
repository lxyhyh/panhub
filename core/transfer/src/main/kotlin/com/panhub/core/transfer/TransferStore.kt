package com.panhub.core.transfer

/**
 * 传输队列的持久化出口。
 *
 * 抽成接口而不是直接写文件：核心层是纯 JVM 逻辑，不该知道 Android 的目录布局，
 * 也不该决定用什么格式落盘。测试里换成内存实现就能验证「恢复」这条路径。
 *
 * 只存任务、不存协程句柄——[TransferTask] 本身就是纯数据，
 * 这也是它一开始就不带 Job 的原因。
 */
interface TransferStore {

    /** 读回上次的队列。文件不存在、内容损坏都返回空列表，不能因此让 App 起不来。 */
    suspend fun load(): List<TransferTask>

    /** 覆盖写入当前队列。实现要保证原子性，写一半掉电不能留下半个 JSON。 */
    suspend fun save(tasks: List<TransferTask>)

    /** 不持久化，供测试与不需要存档的场景使用。 */
    object None : TransferStore {
        override suspend fun load(): List<TransferTask> = emptyList()

        override suspend fun save(tasks: List<TransferTask>) = Unit
    }
}
