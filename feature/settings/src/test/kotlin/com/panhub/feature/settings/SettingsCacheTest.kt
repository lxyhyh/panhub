package com.panhub.feature.settings

import com.panhub.core.platform.AppSettings
import com.panhub.core.platform.CacheCleaner
import com.panhub.core.platform.CacheUsage
import com.panhub.core.platform.SettingsRepository
import com.panhub.core.platform.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「清理缓存」这一行的行为。
 *
 * 这里守两件事：没扫完时不冒充「0 B」，以及清理之后数字要重新数一遍——
 * 只清不刷新，用户会以为按钮没生效。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class MemoryStore : SettingsStore {
        override suspend fun load(): AppSettings = AppSettings()
        override suspend fun save(settings: AppSettings) = Unit
    }

    /** 清完之后就报 0，模拟「删干净了」。 */
    private class FakeCleaner(
        private val initial: Long,
        private val removed: Int = 0,
    ) : CacheCleaner {
        private var clearCalls = 0

        val clearCount: Int get() = clearCalls

        override suspend fun usage(): CacheUsage =
            if (clearCalls > 0) CacheUsage(uploadBytes = 0L, scratchBytes = 0L)
            else CacheUsage(uploadBytes = initial, scratchBytes = 0L)

        override suspend fun clear(): Int {
            clearCalls++
            return removed
        }
    }

    private fun viewModel(cleaner: CacheCleaner) = SettingsViewModel(
        repository = SettingsRepository(MemoryStore()),
        info = AppInfo("1.0", "-", "-"),
        cleaner = cleaner,
    )

    @Test
    fun cacheUsageIsMeasuredOnEntry() = runTest(dispatcher) {
        val vm = viewModel(FakeCleaner(2048L));
        advanceUntilIdle()

        assertEquals(2048L, vm.ui.value.cacheBytes)
        assertTrue(vm.ui.value.canClearCache)
    }

    @Test
    fun clearSaysHowManyWentAway() = runTest(dispatcher) {
        val vm = viewModel(FakeCleaner(2048L, removed = 3));
        advanceUntilIdle()

        vm.clearCache()
        advanceUntilIdle()

        assertEquals("已清理 3 个临时文件", vm.ui.value.message)
        assertFalse(vm.ui.value.clearing)
    }

    @Test
    fun clearingRecountsWhatIsLeft() = runTest(dispatcher) {
        val vm = viewModel(FakeCleaner(2048L, removed = 1));
        advanceUntilIdle()

        vm.clearCache()
        advanceUntilIdle()

        // 清完必须重新数：只清不刷新，用户会以为按钮没生效。
        assertEquals(0L, vm.ui.value.cacheBytes)
        assertFalse(vm.ui.value.canClearCache)
    }

    @Test
    fun messageCanBeDismissed() = runTest(dispatcher) {
        val vm = viewModel(FakeCleaner(10L, removed = 1));
        advanceUntilIdle()

        vm.clearCache()
        advanceUntilIdle()
        vm.clearMessage()
        advanceUntilIdle()

        assertNull(vm.ui.value.message)
    }

    @Test
    fun anEmptyCacheSaysNothingToClean() = runTest(dispatcher) {
        val vm = viewModel(FakeCleaner(0L));
        advanceUntilIdle()

        assertEquals("没有可清理的临时文件", vm.ui.value.cacheLine)
        assertFalse(vm.ui.value.canClearCache)
    }

    @Test
    fun clearingTwiceInARowOnlyRunsOnce() = runTest(dispatcher) {
        val cleaner = FakeCleaner(2048L, removed = 2);
        val vm = viewModel(cleaner);
        advanceUntilIdle()

        vm.clearCache()
        vm.clearCache()
        advanceUntilIdle()

        // 连点两下不该删两轮：第二下发生在第一轮还没跑完时，直接忽略。
        assertEquals(1, cleaner.clearCount)
    }
}
