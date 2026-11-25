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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * 界面主题的选择。
 *
 * 三个值而不是一个布尔：跟随系统是默认，也是大多数人想要的行为；
 * 但把「跟随系统」和「当前恰好是浅色」混成一个布尔，用户切了系统主题
 * 之后就无法回到跟随模式了。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /**
         * 从存档里读出来。
         *
         * 认不出就回退到跟随系统：存档是给人方便的，不是真相源，
         * 一个旧版本写下的、或者手改坏的值不该让 App 起不来。
         */
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

/** 本地设置。字段少，且都要能给出合理的默认值。 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /**
     * 只在 Wi-Fi 下传输。
     *
     * 默认关：手机上下载几个 G 的东西，用户自己知道流量够不够；
     * 反过来「明明有网却不动」是更难理解的故障。
     */
    val wifiOnly: Boolean = false,
    /**
     * 文件列表按什么排。
     *
     * 只存名字（NAME/TIME/SIZE），不存 `SortOrder` 本身：这个模块不认识
     * core:model，硬塞一个模型进来会把「设置」和「文件」两个概念绑在一起。
     * 认不出的值回退到名称，见 [SortOrderName.parse]。
     */
    val sortOrder: SortOrderName = SortOrderName.NAME,
    /** 排序方向：true = 从大到小 / 从新到旧 / 从 Z 到 A。 */
    val sortDescending: Boolean = false,
    /**
     * 文件列表怎么摆。
     *
     * 默认还是列表：它是能一眼看清名字、大小、修改时间的那一种，
     * 网格更适合翻照片，而多数时候用户在找的不是照片。
     */
    val layout: LayoutName = LayoutName.LIST,
) {
    /** 记住的这组排序是否还是默认值。界面据此决定要不要显示「用默认排序」。 */
    val hasCustomSort: Boolean
        get() = sortOrder != SortOrderName.NAME || sortDescending
}

/**
 * 排序依据在设置里的存法。
 *
 * 与 `com.panhub.core.model.SortOrder` 一一对应，但刻意不复用那个枚举：
 * core:platform 是设置与平台能力，core:model 是网盘数据，让前者依赖后者
 * 会把「换个默认排序」这种小事牵连到数据模型上。
 */
enum class SortOrderName {
    NAME,
    TIME,
    SIZE,
    ;

    companion object {
        /** 认不出就回退到名称：旧版本写的值或手改坏的值不该让列表排不出来。 */
        fun parse(raw: String?): SortOrderName =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NAME
    }
}

/**
 * 文件列表的摆放方式。
 *
 * 与界面层的 FileLayout 一一对应，但同样刻意不复用：设置层不认识界面模块，
 * 界面模块也不该为了存一个偏好去依赖整个设置层，中间那一步翻译放在装配处。
 */
enum class LayoutName {
    LIST,
    GRID,
    ;

    companion object {
        /** 认不出就回退到列表：旧存档或手改坏的值不该让文件打不开。 */
        fun parse(raw: String?): LayoutName =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: LIST
    }
}

/**
 * 设置的持久化。
 *
 * 抽成接口是为了让界面层与测试都不必碰文件：
 * 换存储实现（DataStore、SharedPreferences）时只改这一处的注入。
 */
interface SettingsStore {
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}

/**
 * 把设置存成一个极小的 JSON 文件。
 *
 * 手写解析而不挂序列化插件：这里只有几个字符串字段，
 * 为一个 data class 给整个 Android 模块加编译器插件不划算。
 *
 * 读坏了当默认值：与队列存档同一个理由——设置丢了最多是主题变回跟随系统，
 * 而解析异常抛到启动路径上会让 App 直接打不开。
 */
class JsonSettingsStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsStore {

    private val mutex = Mutex()

    override suspend fun load(): AppSettings = mutex.withLock {
        withContext(ioDispatcher) {
            if (!file.isFile) return@withContext AppSettings()
            runCatching {
                val obj = json.parseToJsonElement(file.readText()).jsonObject
                AppSettings(
                    theme = ThemeMode.parse(obj["theme"]?.jsonPrimitive?.contentOrNull),
                    wifiOnly = obj["wifiOnly"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    sortOrder = SortOrderName.parse(obj["sortOrder"]?.jsonPrimitive?.contentOrNull),
                    sortDescending = obj["sortDescending"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        ?: false,
                    layout = LayoutName.parse(obj["layout"]?.jsonPrimitive?.contentOrNull),
                )
            }.getOrElse { AppSettings() }
        }
    }

    override suspend fun save(settings: AppSettings) = mutex.withLock {
        withContext(ioDispatcher) {
            val text = buildJsonObject {
                put("theme", settings.theme.name)
                put("wifiOnly", settings.wifiOnly.toString())
                put("sortOrder", settings.sortOrder.name)
                put("sortDescending", settings.sortDescending.toString())
                put("layout", settings.layout.name)
            }.toString()
            file.parentFile?.mkdirs()
            // 先写临时文件再改名，闪存写到一半掉电不能留下半个 JSON。
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

/**
 * 设置的内存视图。
 *
 * 界面读 [settings] 这个 StateFlow，写入走 [setTheme] 这类具体方法——
 * 不让界面直接调 [SettingsStore.save]，否则「内存里的值」和「文件里的值」
 * 谁先谁后会变成每个调用点各自决定的事，界面上就会出现闪一下又变回去。
 */
class SettingsRepository(
    private val store: SettingsStore,
) {
    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    /** 从磁盘读一次。启动时调一次就够，之后的写入都由本类维护。 */
    suspend fun load() {
        state.value = store.load()
    }

    suspend fun setTheme(mode: ThemeMode) {
        val next = state.value.copy(theme = mode)
        if (next == state.value) return
        state.value = next
        store.save(next)
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        val next = state.value.copy(wifiOnly = enabled)
        if (next == state.value) return
        state.value = next
        store.save(next)
    }

    /**
     * 记住文件列表的排序。
     *
     * 每次改排序都落一次盘：用户挑了一个排序，下次进另一个网盘还是这个排序，
     * 才符合「我习惯了这么看」的预期。写一个几十字节的 JSON 不值得攒。
     */
    suspend fun setSort(order: SortOrderName, descending: Boolean) {
        val next = state.value.copy(sortOrder = order, sortDescending = descending)
        if (next == state.value) return
        state.value = next
        store.save(next)
    }

    /**
     * 记住文件列表是列表还是格子。
     *
     * 和排序一样立刻落盘：切一次视图是一件明确的选择，
     * 下次进另一个网盘又跳回列表会让人以为自己点错了。
     */
    suspend fun setLayout(layout: LayoutName) {
        val next = state.value.copy(layout = layout)
        if (next == state.value) return
        state.value = next
        store.save(next)
    }
}
