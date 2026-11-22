package com.panhub.app

import com.panhub.core.model.ProviderCapabilities
import com.panhub.provider.api.PanProvider
import com.panhub.provider.baidu.BaiduProvider
import com.panhub.provider.cloud189.Cloud189Provider
import com.panhub.provider.lanzou.LanzouProvider
import com.panhub.provider.pan123.Pan123Provider
import com.panhub.provider.quark.QuarkProvider
import com.panhub.provider.webdav.NutstoreProvider
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力声明必须与契约一致。
 *
 * [ProviderCapabilities] 是界面是否显示某个按钮的唯一依据，但它只是一组布尔值：
 * 声明了 copy 却没有 copy 方法，编译器不会报错，用户却会看到一个点了必然失败的按钮。
 * 这个测试遍历真实的服务商实现，把「声明多于契约」挡住。
 */
class CapabilityContractTest {

    private val providers: List<PanProvider> = listOf(
        NutstoreProvider(),
        Pan123Provider(),
        LanzouProvider(),
        Cloud189Provider(),
        QuarkProvider(),
        BaiduProvider(),
    )

    @Test
    fun `no provider declares a capability the contract cannot serve`() {
        val offenders = mutableListOf<String>()
        for (provider in providers) {
            val declared = declaredNames(provider.descriptor.capabilities)
            val extra = declared - CONTRACT_NAMES
            if (extra.isNotEmpty()) {
                offenders += provider.descriptor.id.value + ": " + extra
            }
        }
        assertTrue(
            "这些开关在 PanProvider 里没有对应方法，不应声明为 true: " + offenders,
            offenders.isEmpty(),
        )
    }

    /** 反向检查：契约里有方法的开关，至少有一个服务商真的声明了。 */
    @Test
    fun `every contract capability is claimed by someone`() {
        val claimed = providers.flatMap { declaredNames(it.descriptor.capabilities) }.toSet()
        val missing = CONTRACT_NAMES - claimed
        assertTrue("这些开关有对应方法却无人声明，可能是漏改: " + missing, missing.isEmpty())
    }

    private fun declaredNames(capabilities: ProviderCapabilities): Set<String> =
        ALL_CAPABILITIES.filterValues { it(capabilities) }.keys

    private companion object {
        /** 契约（PanProvider 的方法 + 上传/分享参数）真正支撑的开关。 */
        val CONTRACT_NAMES = setOf(
            "list", "quota", "upload", "uploadChunked", "download",
            "rename", "move", "delete", "createFolder",
            "share", "shareList", "sharePassword", "shareExpiry", "directLink",
        )

        /** 能力名 → 取值函数。新增能力时必须在这里补一行，否则测不到。 */
        val ALL_CAPABILITIES: Map<String, (ProviderCapabilities) -> Boolean> = mapOf(
            "list" to { c: ProviderCapabilities -> c.list },
            "quota" to { c: ProviderCapabilities -> c.quota },
            "upload" to { c: ProviderCapabilities -> c.upload },
            "uploadChunked" to { c: ProviderCapabilities -> c.uploadChunked },
            "download" to { c: ProviderCapabilities -> c.download },
            "rename" to { c: ProviderCapabilities -> c.rename },
            "move" to { c: ProviderCapabilities -> c.move },
            "copy" to { c: ProviderCapabilities -> c.copy },
            "trash" to { c: ProviderCapabilities -> c.trash },
            "delete" to { c: ProviderCapabilities -> c.delete },
            "createFolder" to { c: ProviderCapabilities -> c.createFolder },
            "search" to { c: ProviderCapabilities -> c.search },
            "share" to { c: ProviderCapabilities -> c.share },
            "shareList" to { c: ProviderCapabilities -> c.shareList },
            "sharePassword" to { c: ProviderCapabilities -> c.sharePassword },
            "shareExpiry" to { c: ProviderCapabilities -> c.shareExpiry },
            "directLink" to { c: ProviderCapabilities -> c.directLink },
            "offlineDownload" to { c: ProviderCapabilities -> c.offlineDownload },
        )
    }
}