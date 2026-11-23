package com.panhub.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.panhub.core.platform.SettingsRepository
import com.panhub.core.platform.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
) {
    val options: List<ThemeOption> get() = SettingsLogic.themeOptions()
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
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(theme = it.theme, wifiOnly = it.wifiOnly, info = info) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsUiState(info = info),
        )

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { repository.setTheme(mode) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { repository.setWifiOnly(enabled) }
    }
}
