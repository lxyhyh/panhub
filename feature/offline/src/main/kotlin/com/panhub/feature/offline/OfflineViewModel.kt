package com.panhub.feature.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.OfflineLinks
import com.panhub.core.model.OfflineResource
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ScopedPanProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 离线下载页的状态。
 *
 * [parentId] 是提交时的目标目录，null 表示根目录。
 * [resource] 为 null 表示还没解析出东西，界面此时只显示贴链接的输入框。
 */
data class OfflineUiState(
    val accountName: String = "",
    val link: String = "",
    val resolving: Boolean = false,
    val submitting: Boolean = false,
    val resource: OfflineResource? = null,
    val selected: Set<String> = emptySet(),
    val parentId: NodeId? = null,
    val parentName: String? = null,
    val error: PanError? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = resolving || submitting

    val canSubmit: Boolean get() = OfflineLogic.canSubmit(submitting, resource, selected)

    val summary: String? get() = resource?.let { OfflineLogic.describeResource(it) }

    val parentLabel: String get() = parentName?.takeIf { it.isNotBlank() } ?: "我的网盘"
}

/**
 * 离线下载的 ViewModel。
 *
 * 一个账号一个实例：离线接口是账号维度的，跨账号没有意义。
 * [parentId] / [parentName] 是用户点进来时所在的目录，提交时原样带上——
 * 「我在哪里点的离线下载，东西就应该出现在哪里」。
 */
class OfflineViewModel(
    private val session: SessionManager,
    private val accountId: AccountId,
    private val parentId: NodeId? = null,
    private val parentName: String? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        OfflineUiState(parentId = parentId, parentName = parentName),
    )
    val state: StateFlow<OfflineUiState> = _state.asStateFlow()

    private var scoped: ScopedPanProvider? = null
    private var started = false
    private var resolveJob: Job? = null

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val account = session.accounts.first().firstOrNull { it.id == accountId }
            if (account == null) {
                _state.update { it.copy(error = PanError.notFound("账号不存在或已被移除")) }
                return@launch
            }
            _state.update { it.copy(accountName = account.label) }
            when (val result = session.connect(accountId)) {
                is PanResult.Failure -> _state.update { it.copy(error = result.error) }
                is PanResult.Success -> scoped = result.value
            }
        }
    }

    fun setLink(value: String) {
        _state.update { it.copy(link = value, error = null) }
    }

    /** 解析链接；重复点击只保留最后一次，避免两份结果互相覆盖。 */
    fun resolve() {
        val current = _state.value
        if (current.resolving || current.submitting) return
        val parsed = OfflineLinks.parse(current.link)
        if (parsed == null) {
            _state.update { it.copy(error = PanError.unknown(NOT_A_LINK)) }
            return
        }
        val target = scoped
        if (target == null) {
            _state.update { it.copy(error = PanError.auth("还没连上账号，请返回重进")) }
            return
        }
        resolveJob?.cancel()
        _state.update {
            it.copy(resolving = true, error = null, resource = null, selected = emptySet())
        }
        resolveJob = viewModelScope.launch {
            when (val result = target.resolveOffline(parsed)) {
                is PanResult.Failure -> _state.update { it.copy(resolving = false, error = result.error) }
                is PanResult.Success -> {
                    val resource = result.value
                    _state.update {
                        it.copy(
                            resolving = false,
                            resource = resource,
                            // 默认全选：绝大多数人是要整个资源，逐个点是负担。
                            selected = resource.defaultSelection,
                        )
                    }
                }
            }
        }
    }

    fun toggle(id: String) {
        _state.update { it.copy(selected = OfflineLogic.toggle(it.selected, id)) }
    }

    fun toggleAll() {
        val resource = _state.value.resource ?: return
        _state.update { it.copy(selected = OfflineLogic.toggleAll(it.selected, resource.files)) }
    }

    /** 换一条链接重新解析，免得用户为了改错字从头再来一遍。 */
    fun resetLink() {
        resolveJob?.cancel()
        _state.update {
            it.copy(resource = null, selected = emptySet(), resolving = false, error = null)
        }
    }

    fun submit() {
        val current = _state.value
        val target = scoped ?: return
        val resource = current.resource ?: return
        if (!current.canSubmit) return
        val chosen = current.selected.toList()
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            when (val result = target.createOffline(resource, chosen, current.parentId)) {
                is PanResult.Failure -> _state.update {
                    it.copy(submitting = false, error = result.error)
                }
                is PanResult.Success -> _state.update {
                    it.copy(
                        submitting = false,
                        link = "",
                        resource = null,
                        selected = emptySet(),
                        message = OfflineLogic.submittedMessage(resource, chosen.toSet()),
                    )
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val NOT_A_LINK = "这不像一个下载链接，只认 http(s) 网址和磁力链接"
    }
}
