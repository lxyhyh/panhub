package com.panhub.core.transfer

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 把队列存成一个 JSON 文件。
 *
 * 为什么不做增量：队列规模是几十条量级，整体重写比维护追加日志简单得多，
 * 也不会出现「日志里有但文件里没有」的分裂状态。
 *
 * 为什么读坏了要当空：存档是给人方便的，不是真相源。
 * 一个解析不了的旧文件如果让 App 起不来，比丢掉历史任务严重得多。
 */
class JsonTransferStore(
    private val file: File,
    /**
     * 落盘用的调度器。
     *
     * 默认 IO 线程池；测试里换成单线程/不受限调度器，
     * 否则 `advanceUntilIdle` 等不到真正的文件读写，用例会随机看不到结果。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TransferStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    override suspend fun load(): List<TransferTask> = mutex.withLock {
        withContext(ioDispatcher) {
            if (!file.isFile) return@withContext emptyList()
            runCatching {
                val stored = json.decodeFromString(
                    StoredTransfers.serializer(),
                    file.readText(),
                )
                stored.tasks.mapNotNull(TransferMapper::toTask)
            }.getOrElse { emptyList() }
        }
    }

    override suspend fun save(tasks: List<TransferTask>) = mutex.withLock {
        withContext(ioDispatcher) {
            val stored = StoredTransfers(tasks = tasks.map(TransferMapper::toRecord))
            val text = json.encodeToString(StoredTransfers.serializer(), stored)
            file.parentFile?.mkdirs()
            // 先写临时文件再改名：写到一半掉电不能留下半个 JSON，
            // 否则下次启动整份存档都会被当成损坏而丢弃。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}
