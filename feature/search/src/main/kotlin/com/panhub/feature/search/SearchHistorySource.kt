package com.panhub.feature.search

import kotlinx.coroutines.flow.StateFlow

/**
 * 搜索历史。
 *
 * 只认四件事：读一份列表、记一笔、忘掉一条、清空。不认识文件、不认识设置层——
 * 搜索页为了记住几个关键字就把整个设置模块拉进来，换来的是以后
 * 换存储方式时要在两个模块之间来回改。
 *
 * 为 null 表示这个 App 不带历史（单测里通常这样），界面就当它不存在。
 */
interface SearchHistorySource {

    /** 最近搜过的关键字，新的在前。界面直接订阅它。 */
    val entries: StateFlow<List<String>>

    /** 记一次搜索。空白关键字由实现自己忽略。 */
    suspend fun remember(query: String)

    /** 忘掉一条。关键字的匹配规则由实现决定，调用方只交出屏幕上那一行。 */
    suspend fun forget(query: String)

    /** 全部清掉。 */
    suspend fun clear()
}
