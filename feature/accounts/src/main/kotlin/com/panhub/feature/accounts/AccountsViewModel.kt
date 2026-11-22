package com.panhub.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanResult
import com.panhub.core.common.getOrNull
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.session.AccountSummary
import com.panhub.core.session.SessionManager
import com.panhub.provider.api.ProviderCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账号页的状态。
 *
 * 全放在一个不可变 data class 里，而不是散成多个 StateFlow：
 * 界面只有一个，状态就该是一个；多流会在组合时产生中间态不一致。
 */
data class AccountsUiState(
    val accounts: List<AccountSummary> = emptyList(),
    val providers: List<ProviderDescriptor> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
    val busy: Boolean = false,
) {
    fun providerOf(providerId: ProviderId): ProviderDescriptor? =
        providers.firstOrNull { it.id == providerId }
}

/**
 * 添加账号表单的状态。
 *
 * [method] 由所选网盘的 [ProviderDescriptor.authMethod] 决定初始值，
 * 但用户可以覆盖（BOTH 的情况下）。
 */
data class LoginFormState(
    val providerId: ProviderId? = null,
    val method: AuthMethod = AuthMethod.PASSWORD,
    val username: String = "",
    val password: String = "",
    val token: String = "",
) {
    val canSubmit: Boolean
        get() = when (method) {
            AuthMethod.PASSWORD -> username.isNotBlank() && password.isNotBlank()
            AuthMethod.TOKEN -> token.isNotBlank()
            AuthMethod.BOTH -> username.isNotBlank() && password.isNotBlank()
        }
}

class AccountsViewModel(
    private val session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    private val _form = MutableStateFlow(LoginFormState())
    val form: StateFlow<LoginFormState> = _form.asStateFlow()

    init {
        _state.update { it.copy(providers = session.descriptors()) }
        viewModelScope.launch {
            session.accounts.collect { accounts ->
                _state.update { it.copy(accounts = accounts, loading = false) }
            }
        }
    }

    /** 打开某个网盘的登录表单。 */
    fun startLogin(providerId: ProviderId) {
        val descriptor = _state.value.providerOf(providerId) ?: return
        _form.value = LoginFormState(
            providerId = providerId,
            method = when (descriptor.authMethod) {
                AuthMethod.PASSWORD -> AuthMethod.PASSWORD
                AuthMethod.TOKEN -> AuthMethod.TOKEN
                AuthMethod.BOTH -> AuthMethod.PASSWORD
            },
        )
    }

    fun cancelLogin() {
        _form.value = LoginFormState()
    }

    fun setMethod(method: AuthMethod) {
        _form.update { it.copy(method = method) }
    }

    fun setUsername(value: String) = _form.update { it.copy(username = value) }

    fun setPassword(value: String) = _form.update { it.copy(password = value) }

    fun setToken(value: String) = _form.update { it.copy(token = value) }

    /** 提交登录。成功后自动关闭表单。 */
    fun submit() {
        val form = _form.value
        val providerId = form.providerId ?: return
        if (!form.canSubmit || _state.value.busy) return

        val credentials = if (form.method == AuthMethod.TOKEN) {
            ProviderCredentials.Token(accessToken = form.token.trim())
        } else {
            ProviderCredentials.Password(
                username = form.username.trim(),
                password = form.password,
            )
        }

        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            when (val result = session.login(providerId, credentials)) {
                is PanResult.Success -> {
                    _form.value = LoginFormState()
                    _state.update { it.copy(busy = false, message = "已添加 ${result.value.displayName}") }
                }
                is PanResult.Failure -> {
                    _state.update { it.copy(busy = false, message = result.error.message) }
                }
            }
        }
    }

    fun logout(accountId: AccountId) {
        viewModelScope.launch {
            session.logout(accountId)
            _state.update { it.copy(message = "已移除账号") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /**
     * 重新校验一个账号。
     *
     * 单独暴露是因为"token 过期"不是错误而是常态：给用户一个显式的
     * 检查入口，比让他在打开文件时才撞上失败要好。
     */
    fun verify(accountId: AccountId) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = session.verify(accountId)
            val message = when (result) {
                is PanResult.Success -> "登录状态有效"
                is PanResult.Failure -> result.error.message
            }
            _state.update { it.copy(busy = false, message = message) }
        }
    }

    /** 给 UI 用的"能不能进文件页"判断。 */
    fun capabilityOf(accountId: AccountId) =
        _state.value.accounts.firstOrNull { it.id == accountId }?.capabilities

    companion object {
        /** 允许从任意位置构造（app 层组装时用）。 */
        fun factory(session: SessionManager): (ViewModel) -> AccountsViewModel = { AccountsViewModel(session) }

        /** 便捷空判断，供 UI 做一次性读取。 */
        fun errorOf(result: PanResult<*>): String? = (result as? PanResult.Failure)?.error?.message
    }
}

/** 把 [PanResult] 里的值捞出来，失败返回 null（UI 用）。 */
internal fun <T> PanResult<T>.valueOrNull(): T? = getOrNull()
