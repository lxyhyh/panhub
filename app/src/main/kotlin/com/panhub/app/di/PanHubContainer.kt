package com.panhub.app.di

import android.content.Context
import com.panhub.core.common.Clock
import com.panhub.core.network.HttpEngine
import com.panhub.core.platform.KeystoreCipher
import com.panhub.core.session.DefaultSessionManager
import com.panhub.core.session.SessionManager
import com.panhub.core.storage.JsonAccountStore
import com.panhub.core.transfer.DirectorySink
import com.panhub.core.transfer.HttpFileDownloader
import com.panhub.core.transfer.TransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderRegistry
import com.panhub.provider.baidu.BaiduProvider
import com.panhub.provider.cloud189.Cloud189Provider
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
        )
    }

    private fun build(app: Context): SessionManager {
        val engine = HttpEngine()

        val providers: List<PanProvider> = listOf(
            NutstoreProvider(engine),
            Pan123Provider(engine),
            LanzouProvider(engine),
            Cloud189Provider(engine),
                        QuarkProvider(engine),
                        BaiduProvider(engine),
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
}