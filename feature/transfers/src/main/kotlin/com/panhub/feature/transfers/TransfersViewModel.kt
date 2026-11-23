package com.panhub.feature.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.Clock
import com.panhub.core.transfer.TransferDigest
import com.panhub.core.transfer.TransferEngine
import com.panhub.core.transfer.TransferSpeedTracker
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 传输页的筛选标签。 */
enum class TransferFilter { ALL, ACTIVE, FINISHED }

data class TransfersUiState(
    val filter: TransferFilter = TransferFilter.ALL,
    val tasks: List<TransferTask> = emptyList(),
    /** 每个正在跑的任务的速率（字节/秒）。测不出来的任务不会出现在这里。 */
    val speeds: Map<String, Long> = emptyMap(),
    /** 队列此刻被挡住的原因（例如开着「仅 Wi-Fi」却不在 Wi-Fi 上），null 表示没挡。 */
    val gateMessage: String? = null,
) {
    /** 标题栏要显示的汇总；每帧重算一次，任务数是几十条量级，不值得缓存。 */
    val digest: TransferDigest get() = TransferDigest.of(tasks)

    val visible: List<TransferTask>
        get() = when (filter) {
            TransferFilter.ALL -> tasks
            TransferFilter.ACTIVE -> tasks.filter { it.state.isActive || it.state.isPaused }
            TransferFilter.FINISHED -> tasks.filter { it.state.isFinished }
        }

    val activeCount: Int get() = tasks.count { it.state.isActive }
    val hasFinished: Boolean get() = tasks.any { it.state.isFinished }

    /** 失败的任务数。网络恢复后用户想一次把它们都排回去。 */
    val failedCount: Int get() = tasks.count { it.state == TransferState.FAILED }

    /** 正在跑的那个任务的速率，标题栏用。 */
    val runningSpeed: Long? get() = digest.running?.let { speeds[it.id] }
}

/**
 * 传输列表。
 *
 * 这里刻意不自己保存进度：进度是 [TransferEngine] 的事，页面只做筛选与转发，
 * 否则会出现"两份进度各说各话"。唯一的真相源是引擎的 tasks 流。
 *
 * 速率是例外：它不能从队列里读出来，得按时间采样自己算。
 * 采样放在这个 ViewModel 里而不是引擎里，是因为它只服务界面——
 * 队列本身、通知、存档都不需要知道「现在每秒多少字节」。
 */
class TransfersViewModel(
    private val engine: TransferEngine,
    private val clock: Clock = Clock.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(TransfersUiState(tasks = engine.tasks.value))
    val state: StateFlow<TransfersUiState> = _state.asStateFlow()

    private val tracker = TransferSpeedTracker()

    init {
        viewModelScope.launch {
            engine.tasks.collect { tasks ->
                _state.update { it.copy(tasks = tasks) }
            }
        }
        viewModelScope.launch {
            engine.gateMessage.collect { reason ->
                _state.update { it.copy(gateMessage = reason) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MILLIS)
                val speeds = tracker.sample(_state.value.tasks, clock.nowMillis())
                _state.update { it.copy(speeds = speeds) }
            }
        }
    }

    fun setFilter(filter: TransferFilter) = _state.update { it.copy(filter = filter) }

    fun cancel(id: String) {
        engine.cancel(id)
    }

    /** 暂停：留着已经下好的部分，等用户回来接着传。 */
    fun pause(id: String) {
        engine.pause(id)
    }

    fun resume(id: String) {
        engine.resume(id)
    }

    fun retry(id: String) {
        engine.retry(id)
    }

    /** 网络恢复后一次把失败的任务都排回队列。 */
    fun retryFailed() {
        engine.retryAllFailed()
    }

    /**
     * 删掉已经下到本机的文件。
     *
     * 只对下载完成的任务有效：清空列表并不会让文件消失，
     * 那些文件会一直占着空间，而且清掉记录之后就再也没有入口能看见它们。
     */
    fun deleteLocalFile(id: String): Boolean = engine.deleteLocalFile(id)

    fun clearFinished() {
        engine.clearFinished()
        tracker.reset()
        _state.update { it.copy(speeds = emptyMap()) }
    }

    private companion object {
        /**
         * 采样间隔。
         *
         * 一秒一次：比这更密只会让数字乱跳，更疏则看起来像卡住了。
         * 与 [com.panhub.core.transfer.SpeedMeter] 的窗口配合，
         * 窗口里有 5 个左右的采样点。
         */
        const val SAMPLE_INTERVAL_MILLIS = 1_000L
    }
}

/** 只在 [TransferTask.state] 上做判断，避免界面各处重复写枚举比较。 */
private val TransferState.isActive: Boolean
    get() = this == TransferState.QUEUED || this == TransferState.RUNNING

private val TransferState.isFinished: Boolean
    get() = this == TransferState.COMPLETED ||
        this == TransferState.FAILED ||
        this == TransferState.CANCELED

/** 暂停中的任务既不算进行中也算不得结束，单独放在「进行中」筛选项里更好找。 */
private val TransferState.isPaused: Boolean
    get() = this == TransferState.PAUSED
