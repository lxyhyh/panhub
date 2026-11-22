package com.panhub.feature.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.transfer.TransferEngine
import com.panhub.core.transfer.TransferState
import com.panhub.core.transfer.TransferTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 传输页的筛选标签。 */
enum class TransferFilter { ALL, ACTIVE, FINISHED }

data class TransfersUiState(
    val filter: TransferFilter = TransferFilter.ALL,
    val tasks: List<TransferTask> = emptyList(),
) {
    val visible: List<TransferTask>
        get() = when (filter) {
            TransferFilter.ALL -> tasks
            TransferFilter.ACTIVE -> tasks.filter { it.state.isActive }
            TransferFilter.FINISHED -> tasks.filter { it.state.isFinished }
        }

    val activeCount: Int get() = tasks.count { it.state.isActive }
    val hasFinished: Boolean get() = tasks.any { it.state.isFinished }
}

/**
 * 传输列表。
 *
 * 这里刻意不自己保存进度：进度是 [TransferEngine] 的事，页面只做筛选与转发，
 * 否则会出现"两份进度各说各话"。唯一的真相源是引擎的 tasks 流。
 */
class TransfersViewModel(
    private val engine: TransferEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(TransfersUiState(tasks = engine.tasks.value))
    val state: StateFlow<TransfersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            engine.tasks.collect { tasks ->
                _state.update { it.copy(tasks = tasks) }
            }
        }
    }

    fun setFilter(filter: TransferFilter) = _state.update { it.copy(filter = filter) }

    fun cancel(id: String) {
        engine.cancel(id)
    }

    fun retry(id: String) {
        engine.retry(id)
    }

    fun clearFinished() {
        engine.clearFinished()
    }
}

/** 只在 [TransferTask.state] 上做判断，避免界面各处重复写枚举比较。 */
private val TransferState.isActive: Boolean
    get() = this == TransferState.QUEUED || this == TransferState.RUNNING

private val TransferState.isFinished: Boolean
    get() = this == TransferState.COMPLETED ||
        this == TransferState.FAILED ||
        this == TransferState.CANCELED
