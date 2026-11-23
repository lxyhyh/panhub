package com.panhub.app

import com.panhub.provider.api.PanProvider
import com.panhub.provider.api.ProviderRegistry
import com.panhub.provider.baidu.BaiduProvider
import com.panhub.provider.cloud189.Cloud189Provider
import com.panhub.provider.lanzou.LanzouProvider
import com.panhub.provider.pan123.Pan123Provider
import com.panhub.provider.quark.CloudDriveBrand
import com.panhub.provider.quark.QuarkProvider
import com.panhub.provider.webdav.NutstoreProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配的完整性检查。
 *
 * 为什么放在 app 模块：只有这里同时认识所有 provider 实现，
 * 也正因为如此，这里是唯一可能漏注册新服务商的地方。
 * 这个测试把「写了 provider 却没接进容器」挡住，代价只有几毫秒。
 */
class PanHubContainerTest {

    private val providers: List<PanProvider> = listOf(
        NutstoreProvider(),
        Pan123Provider(),
        LanzouProvider(),
        Cloud189Provider(),
        QuarkProvider(),
        QuarkProvider(brand = CloudDriveBrand.Uc),
        BaiduProvider(),
    )

    @Test
    fun `registry accepts every provider`() {
        val registry = ProviderRegistry(providers)
        assertEquals(providers.size, registry.descriptors().size)
    }

    @Test
    fun `every provider declares a usable descriptor`() {
        for (provider in providers) {
            val descriptor = provider.descriptor
            assertTrue("id 不能为空白", descriptor.id.value.isNotBlank())
            assertTrue("显示名不能为空白", descriptor.displayName.isNotBlank())
            assertTrue("至少要能列目录", descriptor.capabilities.list)
            assertTrue("至少要能下载，否则账号接进来也没用", descriptor.capabilities.download)
        }
    }

    @Test
    fun `provider ids are unique`() {
        val ids = providers.map { it.descriptor.id.value }
        assertEquals(ids.size, ids.toSet().size)
    }
}