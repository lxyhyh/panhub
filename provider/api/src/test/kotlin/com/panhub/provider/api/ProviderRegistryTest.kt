package com.panhub.provider.api

import com.panhub.core.common.PanResult
import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodePage
import com.panhub.core.model.PageRequest
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderDescriptor
import com.panhub.core.model.ProviderId
import com.panhub.core.model.Quota
import com.panhub.core.model.RemoteNode
import com.panhub.core.model.ShareLink
import com.panhub.core.model.ShareOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRegistryTest {

    @Test
    fun `register and lookup`() {
        val registry = ProviderRegistry(listOf(FakeProvider("a"), FakeProvider("b")))
        assertEquals(2, registry.descriptors().size)
        assertEquals("a", registry.get(ProviderId("a"))?.descriptor?.id?.value)
        assertNull(registry.get(ProviderId("zzz")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate id is rejected`() {
        ProviderRegistry(listOf(FakeProvider("a"), FakeProvider("a")))
    }
}

private class FakeProvider(id: String) : PanProvider {
    override val descriptor = ProviderDescriptor(
        id = ProviderId(id),
        displayName = id,
        capabilities = ProviderCapabilities(),
    )

    override suspend fun login(credentials: ProviderCredentials): PanResult<ProviderSession> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun verify(session: ProviderSession): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun list(session: ProviderSession, parentId: NodeId?, page: PageRequest): PanResult<NodePage> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun stat(session: ProviderSession, nodeId: NodeId): PanResult<RemoteNode> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun quota(session: ProviderSession): PanResult<Quota> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun createFolder(session: ProviderSession, parentId: NodeId?, name: String): PanResult<RemoteNode> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun rename(session: ProviderSession, nodeId: NodeId, newName: String): PanResult<RemoteNode> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun move(session: ProviderSession, nodeId: NodeId, newParentId: NodeId?): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun delete(session: ProviderSession, nodeId: NodeId): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun downloadUrl(session: ProviderSession, nodeId: NodeId): PanResult<DownloadTarget> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun createShare(session: ProviderSession, nodeIds: List<NodeId>, options: ShareOptions): PanResult<ShareLink> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())

    override suspend fun cancelShare(session: ProviderSession, shareId: String): PanResult<Unit> =
        PanResult.Failure(com.panhub.core.common.PanError.notSupported())
}
