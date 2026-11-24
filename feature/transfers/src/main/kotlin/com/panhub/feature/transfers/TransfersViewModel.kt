package com.panhub.feature.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.Clock
import com.panhub.core.model.NodeFilter
import com.panhub.core.session.SessionManager
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
    /**
     * 按名字筛选的关键字。纯本地行为，不发请求也不改队列。
     *
     * 和标签筛选是两件事：标签管「要看哪一类」，关键字管「找那一条」。
     * 队列跑上一整天会有几十条任务，删不掉也翻不完，靠滚动找名字最费劲。
     */
    val query: String = "",
    val tasks: List<TransferTask> = emptyList(),
    /** 每个正在跑的任务的速率（字节/秒）。测不出来的任务不会出现在这里。 */
    val speeds: Map<String, Long> = emptyMap(),
    /** 队列此刻被挡住的原因（例如开着「仅 Wi-Fi」却不在 Wi-Fi 上），null 表示没挡。 */
    val gateMessage: String? = null,
    /**
     * 账号 id → 账号名（优先备注名）。
     *
     * 传输页是唯一一个把不同账号的任务混在一起的地方，
     * 而任务本身只记账号 id——不把名字查过来，用户看到的就是一串随机串。
     */
    val accountLabels: Map<String, String> = emptyMap(),
) {
    /** 标题栏要显示的汇总；每帧重算一次，任务数是几十条量级，不值得缓存。 */
    val digest: TransferDigest get() = TransferDigest.of(tasks)

    /** 只按标签筛过的那一批。空状态提示要区分「标签下没有」和「关键字下没有」。 */
    val byChip: List<TransferTask>
        get() = when (filter) {
            TransferFilter.ALL -> tasks
            TransferFilter.ACTIVE -> tasks.filter { it.state.isActive || it.state.isPaused }
            TransferFilter.FINISHED -> tasks.filter { it.state.isFinished }
        }

    /** 标签筛完再按名字筛。列表画的就是这一份。 */
    val visible: List<TransferTask>
        get() = NodeFilter.applyBy(byChip, query) { it.name }

    val filtering: Boolean get() = NodeFilter.active(query)

    /** 关键字筛掉了东西才显示「筛选中 N/M 项」，否则那行小字只是噪音。 */
    val filterSummary: String? get() = NodeFilter.summary(visible.size, byChip.size, query)

    /**
     * 列表空着时的标题。
     *
     * 三种空是不同的事：队列本来就空、这个标签下没有、关键字筛没了。
     * 一律说「还没有传输任务」会让明明有五个失败任务的用户以为记录丢了。
     */
    val emptyTitle: String
        get() = when {
            tasks.isEmpty() -> "还没有传输任务"
            byChip.isEmpty() -> when (filter) {
                TransferFilter.ACTIVE -> "进行中没有任务"
                TransferFilter.FINISHED -> "还没有结束的任务"
                TransferFilter.ALL -> "还没有传输任务"
            }
            else -> NodeFilter.emptyMessage(query)
        }

    /** 空状态的补充说明；没什么可说的时候给 null，界面不画第二行。 */
    val emptyHint: String?
        get() = when {
            tasks.isEmpty() -> "在文件列表里长按文件即可开始下载"
            byChip.isEmpty() -> "切到别的标签看看"
            else -> "换个关键字，或者点上面的清空"
        }

    val activeCount: Int get() = tasks.count { it.state.isActive }
    val hasFinished: Boolean get() = tasks.any { it.state.isFinished }

    /** 失败的任务数。网络恢复后用户想一次把它们都排回去。 */
    val failedCount: Int get() = tasks.count { it.state == TransferState.FAILED }

    /** 暂停中的任务数。「全部暂停 / 全部继续」按钮按它出现或消失。 */
    val pausedCount: Int get() = tasks.count { it.state == TransferState.PAUSED }

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
    /**
     * 账号表。可以为空——纯逻辑单测不需要它，
     * 此时界面就不显示账号归属，而不是显示一串 id。
     */
    private val session: SessionManager? = null,
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
        if (session != null) {
            viewModelScope.launch {
                session.accounts.collect { accounts ->
                    val labels = accounts.associate { it.id.value to it.label }
                    _state.update { it.copy(accountLabels = labels) }
                }
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

    /**
     * 按名字找任务。
     *
     * 不防抖：每条任务的名字早就装在内存里了，比一次界面重组便宜得多。
     */
    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun clearQuery() = _state.update { it.copy(query = "") }

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

    /**
     * 把排队中的任务挪到队首。
     *
     * 队列串行、按加入顺序跑，排了十个文件之后想先拿某一个，
     * 以前只能全部取消重排；这里只改顺序。
     */
    fun moveToFront(id: String) {
        engine.moveToFront(id)
    }

    /** 网络恢复后一次把失败的任务都排回队列。 */

    /** 暂停所有排队与进行中的任务；返回按下去的个数。 */
    fun pauseAll() {
        val n = engine.pauseAll()
        // 界面靠 pausedCount/activeCount 反映变化，不需要额外提示。
    }

    /** 继续所有暂停的任务。 */
    fun resumeAll() {
        val n = engine.resumeAll()
        // 界面靠 pausedCount/activeCount 反映变化，不需要额外提示。
    }
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
