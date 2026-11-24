package com.panhub.core.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * 搜索历史的整理规则。
 *
 * 抽成纯函数是因为这几条规则最容易被「顺手优化」改坏：去重是不是忽略大小写、
 * 新的排前面还是后面、超过上限砍哪一条，都得有地方钉住。
 */
object SearchHistoryRules {

    /** 最多记几条。太多了下拉一屏都是旧词，真正想找的那条反而要翻。 */
    const val MAX_ENTRIES: Int = 10

    /** 空白关键字不算一次搜索：[query] 去掉首尾空格后为空时返回 null。 */
    fun normalize(query: String): String? {
        val trimmed = query.trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * 记一次搜索：新的放最前，同一个词（不分大小写）只留一条。
     *
     * 关键字为空时原样返回 [existing]，调用方据此判断「这次不用写盘」。
     */
    fun remember(
        existing: List<String>,
        query: String,
        limit: Int = MAX_ENTRIES,
    ): List<String> {
        val clean = normalize(query) ?: return existing
        if (limit <= 0) return emptyList()
        val kept = existing.filterNot { it.equals(clean, ignoreCase = true) }
        return (listOf(clean) + kept).take(limit)
    }
}

/** 搜索历史的持久化。和设置一样抽成接口，换存储实现时只改注入处。 */
interface SearchHistoryStore {
    suspend fun load(): List<String>

    suspend fun save(queries: List<String>)
}

/**
 * 把搜索历史存成一个 JSON 数组。
 *
 * 读坏了当空历史：历史和设置一样是「方便」，不是真相源；
 * 解析异常抛到启动路径上只会让 App 打不开。
 */
class JsonSearchHistoryStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SearchHistoryStore {

    private val mutex = Mutex()

    override suspend fun load(): List<String> = mutex.withLock {
        withContext(ioDispatcher) {
            if (!file.isFile) return@withContext emptyList()
            runCatching {
                json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull
                }.filter { it.isNotBlank() }
            }.getOrElse { emptyList() }
        }
    }

    override suspend fun save(queries: List<String>) = mutex.withLock {
        withContext(ioDispatcher) {
            val text = buildJsonArray { queries.forEach { add(it) } }.toString()
            file.parentFile?.mkdirs()
            // 先写临时文件再改名：闪存写到一半掉电不能留下半个数组。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 搜索历史的内存视图。
 *
 * 界面读 [entries]，写入走 [remember] / [clear]：不把「内存里的值」和
 * 「文件里的值」谁先谁后交给每个调用点各自决定，否则界面上会出现
 * 先加一条又忽然变回去的闪动。
 */
class SearchHistory(
    private val store: SearchHistoryStore,
) {
    private val state = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = state.asStateFlow()

    /** 从磁盘读一次。启动时调一次就够，之后的写入都由本类维护。 */
    suspend fun load() {
        state.value = store.load()
    }

    suspend fun remember(query: String) {
        val next = SearchHistoryRules.remember(state.value, query)
        if (next == state.value) return
        state.value = next
        store.save(next)
    }

    suspend fun clear() {
        if (state.value.isEmpty()) return
        state.value = emptyList()
        store.save(emptyList())
    }
}
