package com.panhub.core.transfer

import com.panhub.core.common.Clock
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.provider.api.FileUploadSource
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
    /**
     * 中转用的临时目录。
     *
     * 与 [sink] 分开：中转文件是过程产物，用户从没要求把它留在手机上，
     * 所以放临时目录并在传完后删掉，不混进下载目录。
     */
    private val scratchDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", "panhub-scratch"),
) : DownloadLauncher, UploadLauncher, CloudCopyLauncher {

    private val counter = AtomicLong(0L)
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private var running = false

    override fun download(accountId: AccountId, nodeId: NodeId, name: String, size: Long?) {
        enqueue(accountId, nodeId, name, size)
    }

    /**
     * 把本地文件加入上传队列。
     *
     * [parentId] 是目标目录，本地路径存在 [TransferTask.filePath] 里——
     * 上传任务与下载任务共用同一张表，界面不用分两套列表。
     */
    override fun upload(
        accountId: AccountId,
        parentId: NodeId?,
        localPath: String,
        name: String,
        size: Long,
    ): String {
        val now = clock.nowMillis()
        val id = "ul-" + counter.incrementAndGet()
        val task = TransferTask(
            id = id,
            accountId = accountId,
            nodeId = parentId,
            name = name,
            direction = TransferDirection.UPLOAD,
            totalBytes = size,
            filePath = localPath,
            createdAt = now,
            updatedAt = now,
        )
        _tasks.update { it + task }
        pump()
        return id
    }

    /**
     * 跨网盘复制：先下到临时目录，再传到目标账号，最后删掉临时文件。
     *
     * 两步各自的进度都会写回同一个任务，[TransferTask.phase] 告诉用户现在在哪一段，
     * 否则第二段一开始进度条归零，看起来像是重新传了一遍。
     */
    override fun copyBetween(
        fromAccountId: AccountId,
        nodeId: NodeId,
        name: String,
        size: Long?,
        toAccountId: AccountId,
        toParentId: NodeId?,
    ): String {
        val now = clock.nowMillis()
        val id = "cp-" + counter.incrementAndGet()
        val task = TransferTask(
            id = id,
            accountId = fromAccountId,
            nodeId = nodeId,
            name = name,
            direction = TransferDirection.CLOUD_COPY,
            totalBytes = size,
            targetAccountId = toAccountId,
            targetParentId = toParentId,
            createdAt = now,
            updatedAt = now,
        )
        _tasks.update { it + task }
        pump()
        return id
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

        when (task.direction) {
            TransferDirection.DOWNLOAD -> runDownload(id, task, scoped)
            TransferDirection.UPLOAD -> runUpload(id, task, scoped)
            TransferDirection.CLOUD_COPY -> runCloudCopy(id, task, scoped)
        }
    }

    /**
     * 上传。本地文件已不存在时直接失败，不要拿着一个空流去连服务器。
     */
    private suspend fun runUpload(id: String, task: TransferTask, scoped: ScopedPanProvider) {
        val path = task.filePath ?: return fail(id, PanError.unknown("缺少本地文件路径"))
        val file = File(path)
        if (!file.isFile) return fail(id, PanError.notFound("本地文件已不存在"))

        val outcome = scoped.upload(
            parentId = task.nodeId,
            source = FileUploadSource(file, name = task.name),
            listener = { sent, total ->
                update(id) {
                    it.copy(bytes = sent, totalBytes = total.takeIf { t -> t > 0L } ?: it.totalBytes)
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

    private suspend fun runDownload(id: String, task: TransferTask, scoped: ScopedPanProvider) {
        val nodeId = task.nodeId ?: return fail(id, PanError.unknown("缺少远端节点 id"))
        val target = when (val result = scoped.downloadUrl(nodeId)) {
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

    /**
     * 跨网盘中转。
     *
     * 两段各自失败都要如实报告在哪一段失败：用户在传输页看到的只是一行错误，
     * 分不清「源网盘拒绝了下载链接」和「目标网盘拒收」，就没法判断该换哪一个账号。
     */
    private suspend fun runCloudCopy(id: String, task: TransferTask, source: ScopedPanProvider) {
        val nodeId = task.nodeId ?: return fail(id, PanError.unknown("缺少源文件 id"))
        val targetAccount = task.targetAccountId
            ?: return fail(id, PanError.unknown("缺少目标账号"))

        val destination = when (val resolved = source.downloadUrl(nodeId)) {
            is PanResult.Failure -> return fail(id, PanError(
                kind = resolved.error.kind,
                message = "源网盘下载失败：" + resolved.error.message,
                detail = resolved.error.detail,
                retryable = resolved.error.retryable,
                cause = resolved.error.cause,
            ))
            is PanResult.Success -> resolved.value
        }

        val targetProvider = when (val connected = sessionProvider(targetAccount)) {
            is PanResult.Failure -> return fail(id, connected.error)
            is PanResult.Success -> connected.value
        }

        scratchDir.mkdirs()
        val scratch = File(scratchDir, id + "-" + DirectorySink.sanitize(task.name))
        try {
            update(id) { it.copy(phase = "下载中", totalBytes = destination.size ?: it.totalBytes) }
            val downloaded = downloader.download(
                target = destination,
                destination = scratch,
                listener = { bytes, total ->
                    update(id) { it.copy(bytes = bytes, totalBytes = total ?: it.totalBytes) }
                },
            )
            if (downloaded is PanResult.Failure) {
                return fail(id, PanError(
                    kind = downloaded.error.kind,
                    message = "源网盘下载失败：" + downloaded.error.message,
                    detail = downloaded.error.detail,
                    retryable = downloaded.error.retryable,
                    cause = downloaded.error.cause,
                ))
            }

            // 第二段的进度从 0 重新算：它是「上传了多少」，和第一段的字节数不是同一件事。
            update(id) { it.copy(phase = "上传中", bytes = 0L) }
            val uploaded = targetProvider.upload(
                parentId = task.targetParentId,
                source = FileUploadSource(scratch, name = task.name),
                listener = { sent, total ->
                    update(id) { it.copy(bytes = sent, totalBytes = total.takeIf { t -> t > 0L } ?: it.totalBytes) }
                },
            )
            when (uploaded) {
                is PanResult.Failure -> fail(id, PanError(
                    kind = uploaded.error.kind,
                    message = "目标网盘上传失败：" + uploaded.error.message,
                    detail = uploaded.error.detail,
                    retryable = uploaded.error.retryable,
                    cause = uploaded.error.cause,
                ))
                is PanResult.Success -> {
                    update(id) { current ->
                        current.copy(
                            state = TransferState.COMPLETED,
                            bytes = current.totalBytes ?: current.bytes,
                            phase = null,
                            updatedAt = clock.nowMillis(),
                        )
                    }
                }
            }
        } finally {
            // 无论成功还是失败都删：这个文件本来就是过程产物，留着只是占手机空间。
            runCatching { scratch.delete() }
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
