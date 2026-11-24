package com.panhub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.platform.CacheCleaner
import com.panhub.core.platform.CacheUsage
import com.panhub.core.platform.SettingsRepository
import com.panhub.core.platform.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「关于」里那几行固定信息。
 *
 * 由 app 模块装配时算好传进来：版本号在 BuildConfig 里、内核状态要问
 * core:kernel、下载目录要问容器，这些都不是本模块该知道的事。
 */
data class AppInfo(
    val versionName: String,
    val kernel: String,
    val downloadsPath: String,
)

data class SettingsUiState(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val wifiOnly: Boolean = false,
    val info: AppInfo = AppInfo("-", "-", "-"),
    /** 缓存占用的字节数；还没扫完时为 null。 */
    val cacheBytes: Long? = null,
    /** 正在清理。清完之前按钮不该被连点。 */
    val clearing: Boolean = false,
    /** 清理完成后的一句提示，看过就清掉。 */
    val message: String? = null,
) {
    val options: List<ThemeOption> get() = SettingsLogic.themeOptions()

    /** 缓存那一行的说明文字。 */
    val cacheLine: String get() = SettingsLogic.cacheLine(cacheBytes)

    /** 有没有东西可清：0 与「还没扫完」都不给点。 */
    val canClearCache: Boolean get() = !clearing && (cacheBytes ?: 0L) > 0L
}

/**
 * 设置页。
 *
 * 改动立即生效、立即落盘：设置项少且都无害，没有必要像表单那样攒到
 * 点「保存」才提交；反过来，用户选了深色却发现要再点一次才生效更奇怪。
 */
class SettingsViewModel(
    private val repository: SettingsRepository,
    info: AppInfo,
    private val cleaner: CacheCleaner = CacheCleaner.None,
) : ViewModel() {

    /** 缓存相关的状态单独放，免得通知设置变更时把整个界面重建一遍。 */
    private val cache = MutableStateFlow(CacheUi())

    private data class CacheUi(
        val bytes: Long? = null,
        val clearing: Boolean = false,
        val message: String? = null,
    )

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(theme = it.theme, wifiOnly = it.wifiOnly, info = info) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsUiState(info = info),
        )

    /** 界面真正读的那个状态：设置的订阅值 + 缓存那几项。 */
    val ui: StateFlow<SettingsUiState> = combine(state, cache) {
        base, extra ->
        base.copy(
            cacheBytes = extra.bytes,
            clearing = extra.clearing,
            message = extra.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(info = info),
    )

    init {
        refreshCache()
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { repository.setWifiOnly(enabled) }
    }

    /** 重新数一遍缓存占用。进页面时一次，清理后一次。 */
    fun refreshCache() {
        viewModelScope.launch {
            val usage = runCatching { cleaner.usage() }.getOrElse { CacheUsage() }
            cache.update { it.copy(bytes = usage.totalBytes) }
        }
    }

    /**
     * 清掉临时文件。
     *
     * 只删 upload 与 scratch 两处，不动下载、账号、传输队列：
     * 那些是用户的东西，「清理缓存」不该顺手把它们删了。
     */
    fun clearCache() {
        if (cache.value.clearing) return
        cache.update { it.copy(clearing = true) }
        viewModelScope.launch {
            val removed = runCatching { cleaner.clear() }.getOrDefault(0)
            val usage = runCatching { cleaner.usage() }.getOrElse { CacheUsage() }
            cache.update {
                it.copy(
                    bytes = usage.totalBytes,
                    clearing = false,
                    message = SettingsLogic.clearedMessage(removed),
                )
            }
        }
    }

    fun clearMessage() {
        cache.update { it.copy(message = null) }
    }
}
