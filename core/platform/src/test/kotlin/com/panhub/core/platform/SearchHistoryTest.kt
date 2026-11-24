package com.panhub.core.platform

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 搜索历史的几条规则：去重、排序、上限、坏存档。
 *
 * 这些规则一旦放松，界面上就会出现「同一个词记了两遍」或者
 * 「明明是刚搜的却排在最后」这类说不清的小毛病。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun aBlankQueryIsNotWorthRemembering() = runTest {
        assertNull(SearchHistoryRules.normalize("   "))
        assertNull(SearchHistoryRules.normalize(""))
        assertEquals(listOf("报告"), SearchHistoryRules.remember(listOf("报告"), "  "))
    }

    @Test
    fun theNewestQueryComesFirst() {
        val after = SearchHistoryRules.remember(listOf("报告"), "照片")
        assertEquals(listOf("照片", "报告"), after)
    }

    @Test
    fun theSameQueryIsNotRecordedTwice() {
        val after = SearchHistoryRules.remember(listOf("照片", "报告"), "报告")
        assertEquals(listOf("报告", "照片"), after)
    }

    @Test
    fun deduplicationIgnoresCaseButKeepsWhatWasTyped() {
        val after = SearchHistoryRules.remember(listOf("Report"), "report")
        assertEquals(listOf("report"), after)
    }

    @Test
    fun theListNeverGrowsPastTheLimit() {
        val existing = (1..SearchHistoryRules.MAX_ENTRIES).map { "q" + it }
        val after = SearchHistoryRules.remember(existing, "新的")
        assertEquals(SearchHistoryRules.MAX_ENTRIES, after.size)
        assertEquals("新的", after.first())
        assertFalse("最旧的那条应被挤掉", ("q" + SearchHistoryRules.MAX_ENTRIES) in after)
    }

    @Test
    fun aZeroLimitKeepsNothing() {
        assertEquals(emptyList<String>(), SearchHistoryRules.remember(listOf("a"), "b", limit = 0))
    }

    @Test
    fun historySurvivesARestart() = runTest {
        val file = File(temp.newFolder("history"), "search.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        JsonSearchHistoryStore(file, dispatcher).save(listOf("报告", "照片"))

        assertEquals(listOf("报告", "照片"), JsonSearchHistoryStore(file, dispatcher).load())
    }

    @Test
    fun aMissingFileIsJustAnEmptyHistory() = runTest {
        val file = File(temp.newFolder("empty"), "search.json")

        val loaded = JsonSearchHistoryStore(file, StandardTestDispatcher(testScheduler)).load()

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun aBrokenFileIsNotFatal() = runTest {
        val dir = temp.newFolder("broken")
        val file = File(dir, "search.json")
        file.writeText("{ not an array")

        val loaded = JsonSearchHistoryStore(file, StandardTestDispatcher(testScheduler)).load()

        assertTrue(loaded.isEmpty())
    }

    @Test
    fun savingLeavesNoTemporaryFileBehind() = runTest {
        val dir = temp.newFolder("clean")
        val file = File(dir, "search.json")

        JsonSearchHistoryStore(file, StandardTestDispatcher(testScheduler)).save(listOf("a"))

        assertTrue(file.isFile)
        assertFalse(File(dir, "search.json.tmp").exists())
    }

    @Test
    fun theRepositoryKeepsTheSameListItWrote() = runTest {
        val file = File(temp.newFolder("repo"), "search.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val history = SearchHistory(JsonSearchHistoryStore(file, dispatcher))

        history.remember("报告")
        history.remember("报告")
        history.remember("照片")

        assertEquals(listOf("照片", "报告"), history.entries.value)
        assertEquals(listOf("照片", "报告"), JsonSearchHistoryStore(file, dispatcher).load())
    }

    @Test
    fun clearingWipesBothMemoryAndDisk() = runTest {
        val file = File(temp.newFolder("clear"), "search.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val history = SearchHistory(JsonSearchHistoryStore(file, dispatcher))
        history.remember("报告")

        history.clear()

        assertTrue(history.entries.value.isEmpty())
        assertTrue(JsonSearchHistoryStore(file, dispatcher).load().isEmpty())
    }
}
