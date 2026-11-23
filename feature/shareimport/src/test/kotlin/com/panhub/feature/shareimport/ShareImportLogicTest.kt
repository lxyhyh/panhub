package com.panhub.feature.shareimport

import com.panhub.core.model.AccountId
import com.panhub.core.model.NodeId
import com.panhub.core.model.NodeKind
import com.panhub.core.model.ProviderCapabilities
import com.panhub.core.model.ProviderId
import com.panhub.core.model.ShareEntry
import com.panhub.core.session.AccountSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享转存页的纯逻辑测试。
 *
 * 这些规则看着琐碎，但每一条对应一个用户能看见的行为：
 * 账号列表里出现不该出现的账号、返回键把人踢回根目录、
 * 部分转存被说成全部成功 —— 都是能避免的。
 */
class ShareImportLogicTest {

    private fun account(
        id: String,
        lastUsed: Long,
        shareImport: Boolean = true,
    ) = AccountSummary(
        id = AccountId(id),
        providerId = ProviderId("quark"),
        providerName = "夸克网盘",
        displayName = id,
        lastUsedAt = lastUsed,
        capabilities = ProviderCapabilities(share = true, shareImport = shareImport),
    )

    private fun entry(id: String, name: String = id, dir: Boolean = false) = ShareEntry(
        id = NodeId(id),
        name = name,
        kind = if (dir) NodeKind.DIR else NodeKind.FILE,
    )

    @Test
    fun eligibleAccountsDropsProvidersWithoutImport() {
        val result = ShareImportLogic.eligibleAccounts(
            listOf(
                account("a", 1),
                account("b", 2, shareImport = false),
                account("c", 3),
            ),
        )
        assertEquals(listOf("c", "a"), result.map { it.id.value })
    }

    @Test
    fun eligibleAccountsAreOrderedByRecentUse() {
        val result = ShareImportLogic.eligibleAccounts(
            listOf(account("old", 10), account("new", 99)),
        )
        assertEquals("new", result.first().id.value)
    }

    @Test
    fun parentStackAtRootStaysEmpty() {
        assertTrue(ShareImportLogic.parentStack(emptyList()).isEmpty())
    }

    @Test
    fun parentStackDropsLastFolder() {
        val stack = listOf(entry("d1", dir = true), entry("d2", dir = true))
        assertEquals(listOf("d1"), ShareImportLogic.parentStack(stack).map { it.id.value })
    }

    @Test
    fun currentFolderIsNullAtRoot() {
        assertNull(ShareImportLogic.currentFolder(emptyList()))
        assertEquals("d1", ShareImportLogic.currentFolder(listOf(entry("d1", dir = true)))?.value)
    }

    @Test
    fun selectedEntriesKeepListOrder() {
        val entries = listOf(entry("a"), entry("b"), entry("c"))
        val picked = ShareImportLogic.selectedEntries(entries, setOf(NodeId("c"), NodeId("a")))
        assertEquals(listOf("a", "c"), picked.map { it.id.value })
    }

    @Test
    fun subjectShowsNameForSingleAndCountForMany() {
        assertEquals("", ShareImportLogic.subjectOf(emptyList()))
        assertEquals("a", ShareImportLogic.subjectOf(listOf(entry("a", "a"))))
        assertEquals("2 个项目", ShareImportLogic.subjectOf(listOf(entry("a"), entry("b"))))
    }

    @Test
    fun savedMessageDistinguishesPartialSuccess() {
        assertEquals(
            "已转存 3 项到「我的网盘」",
            ShareImportLogic.savedMessage(3, 3, "我的网盘"),
        )
        assertEquals(
            "已转存 1/3 项到「我的网盘」",
            ShareImportLogic.savedMessage(1, 3, "我的网盘"),
        )
        assertEquals(
            "转存失败，服务端没有保存任何一项",
            ShareImportLogic.savedMessage(0, 3, "我的网盘"),
        )
    }

    @Test
    fun toggleAllSelectsThenClears() {
        val entries = listOf(entry("a"), entry("b"))
        val all = ShareImportLogic.toggleAll(entries, emptySet())
        assertEquals(setOf(NodeId("a"), NodeId("b")), all)
        assertTrue(ShareImportLogic.allSelected(entries, all))
        assertTrue(ShareImportLogic.toggleAll(entries, all).isEmpty())
    }

    @Test
    fun allSelectedIsFalseForEmptyList() {
        assertFalse(ShareImportLogic.allSelected(emptyList(), emptySet()))
    }
}
