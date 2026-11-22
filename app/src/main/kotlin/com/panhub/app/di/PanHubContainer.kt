package com.panhub.app.di

import android.content.Context
import com.panhub.core.common.Clock
import com.panhub.core.network.HttpEngine
import com.panhub.core.platform.KeystoreCipher
import com.panhub.core.session.DefaultSessionManager
import com.panhub.core.session.SessionManager
import com.panhub.core.storage.JsonAccountStore
import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderRegistry
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

    /** 进程级单例。第一次调用时装配。 */
    fun sessionManager(context: Context): SessionManager =
        instance ?: synchronized(this) {
            instance ?: build(applicationContext(context)).also { instance = it }
        }

    private fun applicationContext(context: Context): Context =
        context.applicationContext ?: context

    private fun build(app: Context): SessionManager {
        val engine = HttpEngine()

        val providers: List<PanProvider> = listOf(
            NutstoreProvider(engine),
            Pan123Provider(engine),
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
