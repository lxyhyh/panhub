package com.panhub.core.transfer

import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载队列。
 *
 * 设计取舍：
 * - 串行执行。手机上并排下三个大文件只会互相抢带宽、让每个都变慢，
 *   还容易触发服务商限流；串行反而更快也更好推理。
 * - 任务状态收敛在 [tasks] 一个 StateFlow 里，界面只订阅它，
 *   不需要知道有几个协程在跑。
 * - 取消是「标记 + 中断协程」两步：先改状态让界面立刻有反馈，
 *   再 cancel job 真正停止 IO。
 */
class TransferEngine(
    private val scope: CoroutineScope,
    private val sessionProvider: suspend (AccountId) -> PanResult<ScopedPanProvider>,
    private val downloader: FileDownloader,
    private val sink: DownloadSink,
    private val clock: Clock = Clock.Default,
) : DownloadLauncher {

    private val counter = AtomicLong(0L)
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private var running = false

    override fun download(accountId: AccountId, nodeId: NodeId, name: String, size: Long?) {
        enqueue(accountId, nodeId, name, size)
    }

    /** 把一个节点加入下载队列，返回任务 id。 */
    fun enqueue(accountId: AccountId, nodeId: NodeId, name: String, size: Long?): String {
        val now = clock.nowMillis()
        val id = "dl-" + counter.incrementAndGet()
        val task = TransferTask(
            id = id,
            accountId = accountId,
            nodeId = nodeId,
            name = name,
            totalBytes = size,
            createdAt = now,
            updatedAt = now,
        )
        _tasks.update { it + task }
        pump()
        return id
    }

    /** 取消。已完成或已取消的返回 false。 */
    fun cancel(id: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (task.isFinished) return false
        jobs.remove(id)?.cancel()
        update(id) { it.copy(state = TransferState.CANCELED, updatedAt = clock.nowMillis()) }
        pump()
        return true
    }

    /** 失败/取消的任务重新排队。完成的任务不能重试。 */
    fun retry(id: String): Boolean {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return false
        if (task.state != TransferState.FAILED && task.state != TransferState.CANCELED) {
            return false
        }
        update(id) {
            it.copy(
                state = TransferState.QUEUED,
                bytes = 0L,
                error = null,
                updatedAt = clock.nowMillis(),
            )
        }
        pump()
        return true
    }

    /** 从列表里移除已结束的任务（不删文件）。 */
    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.isFinished } }
    }

    /** 串行泵：没有在跑的任务时，取队列里第一个 QUEUED 的启动。 */
    private fun pump() {
        if (running) return
        val next = _tasks.value.firstOrNull { it.state == TransferState.QUEUED } ?: return
        running = true
        val job = scope.launch { runTask(next.id) }
        jobs[next.id] = job
        job.invokeOnCompletion {
            jobs.remove(next.id)
            running = false
            pump()
        }
    }

    private suspend fun runTask(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        update(id) { it.copy(state = TransferState.RUNNING, updatedAt = clock.nowMillis()) }

        val scoped = when (val result = sessionProvider(task.accountId)) {
            is PanResult.Failure -> return fail(id, result.error)
            is PanResult.Success -> result.value
        }

        val target = when (val result = scoped.downloadUrl(task.nodeId)) {
            is PanResult.Failure -> return fail(id, result.error)
            is PanResult.Success -> result.value
        }

        val destination = sink.destinationFor(task)
        val size = target.size ?: task.totalBytes
        update(id) { it.copy(filePath = destination.absolutePath, totalBytes = size) }

        val outcome = downloader.download(
            target = target,
            destination = destination,
            listener = { bytes, total ->
                update(id) {
                    it.copy(bytes = bytes, totalBytes = total ?: it.totalBytes)
                }
            },
        )
        when (outcome) {
            is PanResult.Failure -> fail(id, outcome.error)
            is PanResult.Success -> update(id) {
                it.copy(
                    state = TransferState.COMPLETED,
                    bytes = it.totalBytes ?: it.bytes,
                    updatedAt = clock.nowMillis(),
                )
            }
        }
    }

    private fun fail(id: String, error: PanError) {
        update(id) {
            it.copy(
                state = TransferState.FAILED,
                error = error.message,
                updatedAt = clock.nowMillis(),
            )
        }
    }

    private inline fun update(id: String, transform: (TransferTask) -> TransferTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
