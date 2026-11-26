package com.panhub.app.settings

import com.panhub.core.platform.SearchHistory
import com.panhub.feature.search.SearchHistorySource
import kotlinx.coroutines.flow.StateFlow

/**
 * 把 core:platform 的搜索历史接到搜索页上。
 *
 * 这层适配器只能放在装配处：搜索页用的是一个只有四件事的窄接口，
 * 不该认识「存在哪个文件、怎么序列化」。
 */
class SettingsSearchHistory(
    private val history: SearchHistory,
) : SearchHistorySource {

    override val entries: StateFlow<List<String>> = history.entries

    override suspend fun remember(query: String) {
        history.remember(query)
    }

    override suspend fun forget(query: String) {
        history.forget(query)
    }

    override suspend fun clear() {
        history.clear()
    }
}
