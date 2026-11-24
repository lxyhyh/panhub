package com.panhub.app.di

import android.content.Context
import com.panhub.core.common.Clock
import com.panhub.core.kernel.KernelUpload
import com.panhub.core.network.HttpEngine
import com.panhub.core.platform.JsonSettingsStore
import com.panhub.core.platform.CacheCleaner
import com.panhub.core.platform.KeystoreCipher
import com.panhub.core.platform.LocalCacheCleaner
import com.panhub.core.platform.SettingsRepository
import com.panhub.core.preview.HttpPreviewLoader
import com.panhub.core.preview.PreviewLoader
import com.panhub.core.session.DefaultSessionManager
import com.panhub.core.session.SessionManager
import com.panhub.core.storage.JsonAccountStore
import com.panhub.core.transfer.DirectorySink
import com.panhub.core.transfer.HttpFileDownloader
import com.panhub.core.transfer.JsonTransferStore
import com.panhub.app.transfer.NetworkTransferGate
import com.panhub.app.transfer.isOnWifi
import com.panhub.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderRegistry
import com.panhub.provider.baidu.BaiduProvider
import com.panhub.provider.cloud189.Cloud189Provider
import com.panhub.provider.quark.CloudDriveBrand
import com.panhub.provider.quark.QuarkProvider
import com.panhub.provider.lanzou.LanzouProvider
import com.panhub.provider.pan123.Pan123Provider
import com.panhub.provider.webdav.NutstoreProvider
import java.io.File

/**
 * 全 App 唯一的依赖装配点。
 *
 * 为什么放在 app 模块而不是某个 core 模块：装配需要同时认识所有
 * provider 与所有 core 服务，只有 app 天然处在这个位置。放进 core
 * 会让下层模块反向依赖上层，架构就塌了。
 *
 * 为什么手写而不是上 Hilt/Koin：依赖图是一条直线
 * （engine → providers → registry → session），没有作用域、没有懒加载
 * 需求，DI 框架只会增加构建时间与理解成本。依赖图变复杂时再换，
 * 换的时候只改这一个文件。
 */
object PanHubContainer {

    @Volatile
    private var instance: SessionManager? = null

    @Volatile
    private var engine: TransferEngine? = null

    @Volatile
    private var previews: PreviewLoader? = null

    @Volatile
    private var settings: SettingsRepository? = null

    /** 进程级单例。第一次调用时装配。 */
    fun sessionManager(context: Context): SessionManager =
        instance ?: synchronized(this) {
            instance ?: build(applicationContext(context)).also { instance = it }
        }

    /**
     * 下载队列。
     *
     * 用 application 级作用域，所以退出某个页面不会打断正在进行的下载；
     * 用 SupervisorJob，单个任务失败不会带走整个队列。
     */
    fun transferEngine(context: Context): TransferEngine =
        engine ?: synchronized(this) {
            engine ?: buildEngine(applicationContext(context)).also { engine = it }
        }

    /**
     * 预览加载器。
     *
     * 和下载队列分开：预览是一次性的读取，不进队列、不占执行位，
     * 也不会因为一次失败在传输页留下一条记录。
     */
    fun previewLoader(context: Context): PreviewLoader =
        previews ?: synchronized(this) {
            previews ?: run {
                val app = applicationContext(context)
                val session = sessionManager(app)
                HttpPreviewLoader(
                    engine = HttpEngine(),
                    sessionProvider = { accountId -> session.connect(accountId) },
                ).also { previews = it }
            }
        }

    /**
     * 本地设置。
     *
     * 与账号文件同目录：两者都是「用户配置」，清缓存不该把它们一起清掉。
     * 读取是挂起的，所以 MainActivity 在 setContent 之前先 load 一次，
     * 这样第一帧就能用上正确的主题，不会先闪一下浅色再变深色。
     */
    fun settingsRepository(context: Context): SettingsRepository =
        settings ?: synchronized(this) {
            settings ?: SettingsRepository(
                JsonSettingsStore(File(applicationContext(context).filesDir, SETTINGS_FILE)),
            ).also { settings = it }
        }

    /**
     * 缓存清理。
     *
     * 只认我们自己造的两处临时目录：上传前复制的副本（cacheDir/upload）
     * 与跨网盘中转文件（cacheDir/cloud-copy）。filesDir 里的下载、账号、
     * 传输队列、设置都不在里面——「清理缓存」不该删掉用户的下载。
     */
    fun cacheCleaner(context: Context): CacheCleaner {
        val app = applicationContext(context)
        return LocalCacheCleaner(
            uploadDir = File(app.cacheDir, UPLOAD_CACHE_DIR),
            scratchDir = File(app.cacheDir, CLOUD_COPY_DIR),
        )
    }

    private fun applicationContext(context: Context): Context =
        context.applicationContext ?: context

    private fun buildEngine(app: Context): TransferEngine {
        val session = sessionManager(app)
        // 目录固定为应用私有 downloads：无需任何存储权限，卸载即回收。
        val root = File(app.filesDir, "downloads")
        return TransferEngine(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            sessionProvider = { accountId -> session.connect(accountId) },
            downloader = HttpFileDownloader(),
            sink = DirectorySink(root),
            // 中转临时文件放 cacheDir：系统空间吃紧时可以直接回收，
            // 也不会被当成用户的下载留下一堆半成品。
            scratchDir = File(app.cacheDir, "cloud-copy"),
            // 队列存档放 filesDir：清缓存不该让「昨天没下完的东西」消失。
            store = JsonTransferStore(File(app.filesDir, TRANSFERS_FILE)),
            // 「仅 Wi-Fi 传输」：开关在设置里，当前网络每次现查，
            // 这样切到 Wi-Fi 的那一刻（不用重开 App）就该能继续。
            gate = NetworkTransferGate(
                wifiOnly = { settingsRepository(app).settings.value.wifiOnly },
                onWifi = { isOnWifi(app) },
            ),
        )
    }

    private fun build(app: Context): SessionManager {
        val engine = HttpEngine()
        // 上传前的摘要计算是纯 IO 活，几 GB 的文件差一个量级。
        // 内核编出来了就走 Rust，没编出来 KernelUpload 返回空实现，
        // 各 provider 会自动退回自己的 Kotlin 实现。
        val scanner = KernelUpload.scanner()

        val providers: List<PanProvider> = listOf(
            NutstoreProvider(engine),
            Pan123Provider(engine, scanner),
            LanzouProvider(engine),
            Cloud189Provider(engine, scanner),
            // 夸克与 UC 是同一套 clouddrive 后端，差别只有基址与风控参数，
            // 所以两家共用一个实现、两份品牌配置（见 CloudDriveBrand）。
            QuarkProvider(engine, scanner),
            QuarkProvider(engine, scanner, CloudDriveBrand.Uc),
            BaiduProvider(engine, scanner),
        )

        val registry = ProviderRegistry(providers)
        val store = JsonAccountStore(
            file = File(app.filesDir, ACCOUNTS_FILE),
            cipher = KeystoreCipher(),
        )
        return DefaultSessionManager(registry = registry, store = store, clock = Clock.Default)
    }

    /**
     * 账号文件。
     *
     * 放在 filesDir 下与业务数据隔离：清缓存不丢账号。
     * 文件里的密钥字段由 Keystore 加密，拷贝出去也解不开。
     */
    private const val ACCOUNTS_FILE = "accounts.json"

    /** 传输队列存档，与账号文件同目录。 */
    private const val TRANSFERS_FILE = "transfers.json"

    /** 本地设置（目前只有外观），同上。 */
    private const val SETTINGS_FILE = "settings.json"

    /** 上传前复制文件用的缓存目录名（cacheDir 下）。 */
    private const val UPLOAD_CACHE_DIR = "upload"

    /** 跨网盘复制的中转目录名（cacheDir 下）。 */
    private const val CLOUD_COPY_DIR = "cloud-copy"
}