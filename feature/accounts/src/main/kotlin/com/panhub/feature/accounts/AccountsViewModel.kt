package com.panhub.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.common.PanResult
import com.panhub.core.common.getOrNull
import com.panhub.core.model.AccountId
import com.panhub.core.model.AuthMethod
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
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
    /** 各账号的容量，按账号 id 索引。查不到（或不支持）的账号不会出现在这里。 */
    val quotas: Map<String, Quota> = emptyMap(),
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
            // WebView 登录：抓到 Cookie 后回调 onTokenChange 填进来，再由 UI 提交。
            AuthMethod.WEBVIEW -> token.isNotBlank()
        }
}

class AccountsViewModel(
    private val session: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    /** 已经查过容量的账号 id。查失败也算查过，理由见 [loadQuotas]。 */
    private val attemptedQuotas = mutableSetOf<String>()

    private val _form = MutableStateFlow(LoginFormState())
    val form: StateFlow<LoginFormState> = _form.asStateFlow()

    /** 「更新登录」对话框的目标账号与输入；null 表示对话框关着。 */
    private val _reauth = MutableStateFlow<ReauthTarget?>(null)
    val reauth: StateFlow<ReauthTarget?> = _reauth.asStateFlow()

    init {
        _state.update { it.copy(providers = session.descriptors()) }
        viewModelScope.launch {
            session.accounts.collect { accounts ->
                _state.update { it.copy(accounts = accounts, loading = false) }
                loadQuotas(accounts)
            }
        }
    }

    /**
     * 顺手把各账号的容量查出来。
     *
     * 容量是装饰性的：查不到就什么都不显示，不弹错误也不挡住账号列表。
     * 每个账号只试一次——失败的多半是不支持或凭据过期，
     * 每次刷新都重试只会在弱网下攒一串无用请求。
     */
    private fun loadQuotas(accounts: List<AccountSummary>) {
        val targets = AccountsLogic.quotaTargets(accounts, attemptedQuotas)
        for (account in targets) {
            attemptedQuotas += account.id.value
            viewModelScope.launch {
                val result = session.connect(account.id)
                if (result !is PanResult.Success) return@launch
                val quota = result.value.quota()
                if (quota !is PanResult.Success) return@launch
                _state.update { it.copy(quotas = it.quotas + (account.id.value to quota.value)) }
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
                AuthMethod.WEBVIEW -> AuthMethod.WEBVIEW
            },
        )
    }


    /** 当前正在给哪个账号换凭据；null 表示没有在进行。 */
    data class ReauthTarget(
        val account: AccountSummary,
        /** 复用登录表单的输入与校验，只是提交去向不同。 */
        val form: LoginFormState,
    )

    /** 打开「更新登录」：表单与添加账号共用一套状态。 */
    fun startReauth(account: AccountSummary) {
        if (_state.value.busy) return
        // Cookie 型账号换 Cookie 走令牌输入；密码型只有一个办法。
        val descriptor = _state.value.providerOf(account.providerId)
        val method = when (descriptor?.authMethod) {
            AuthMethod.TOKEN -> AuthMethod.TOKEN
            else -> AuthMethod.PASSWORD
        }
        _reauth.value = ReauthTarget(account, LoginFormState(method = method))
    }

    fun cancelReauth() {
        _reauth.value = null
    }

    fun setReauthMethod(method: AuthMethod) =
        _reauth.update { it?.copy(form = it.form.copy(method = method)) }

    fun setReauthUsername(value: String) =
        _reauth.update { it?.copy(form = it.form.copy(username = value)) }

    fun setReauthPassword(value: String) =
        _reauth.update { it?.copy(form = it.form.copy(password = value)) }

    fun setReauthToken(value: String) =
        _reauth.update { it?.copy(form = it.form.copy(token = value)) }

    /**
     * 提交新凭据。成功后表单关闭并提示；失败留在表单里，
     * 用户改一改还能再提交——和登录失败的处理一致。
     */
    fun submitReauth() {
        val target = _reauth.value ?: return
        val form = target.form
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
            when (val result = session.reauthenticate(target.account.id, credentials)) {
                is PanResult.Success -> {
                    _reauth.value = null
                    _state.update { it.copy(busy = false, message = "已更新 ${target.account.label} 的登录凭据") }
                }
                is PanResult.Failure -> {
                    _state.update { it.copy(busy = false, message = result.error.message) }
                }
            }
        }
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
                    _state.update { it.copy(busy = false, message = "已添加 ${result.value.label}") }
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

    /**
     * 改备注名。传空串表示清掉备注。
     *
     * 不做失败提示：备注名只存在本地，写失败只可能是磁盘问题，
     * 那种时候弹一句话也帮不上忙，账号列表刷新出来自然就知道没改成。
     */
    fun rename(accountId: AccountId, alias: String) {
        viewModelScope.launch {
            session.rename(accountId, alias)
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
