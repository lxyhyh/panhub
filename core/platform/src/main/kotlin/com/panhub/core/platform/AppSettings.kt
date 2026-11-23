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
)

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
                AppSettings(theme = ThemeMode.parse(obj["theme"]?.jsonPrimitive?.contentOrNull))
            }.getOrElse { AppSettings() }
        }
    }

    override suspend fun save(settings: AppSettings) = mutex.withLock {
        withContext(ioDispatcher) {
            val text = buildJsonObject {
                put("theme", settings.theme.name)
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
}
