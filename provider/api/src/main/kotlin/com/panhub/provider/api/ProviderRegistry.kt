package com.panhub.provider.api

import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId

/**
 * 所有已编译进 App 的 provider 的注册表。
 *
 * 新增一个网盘只需要在 :app 的组装代码里调用一次 [register]，
 * 其余模块（账号列表、文件浏览、分享）完全不用改。
 */
class ProviderRegistry(providers: List<PanProvider> = emptyList()) {

    private val byId = LinkedHashMap<ProviderId, PanProvider>()

    init {
        providers.forEach(::register)
    }

    fun register(provider: PanProvider) {
        val id = provider.descriptor.id
        require(id !in byId) { "provider ${id.value} already registered" }
        byId[id] = provider
    }

    fun get(id: ProviderId): PanProvider? = byId[id]

    fun require(id: ProviderId): PanProvider =
        byId[id] ?: error("provider ${id.value} not registered")

    fun descriptors(): List<ProviderDescriptor> = byId.values.map { it.descriptor }

    fun all(): List<PanProvider> = byId.values.toList()
}
