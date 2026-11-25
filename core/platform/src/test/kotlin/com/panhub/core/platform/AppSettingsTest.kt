package com.panhub.core.platform

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppSettingsTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- ThemeMode ----

    @Test
    fun parseAcceptsEveryStoredName() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.parse("DARK"))
    }

    @Test
    fun parseIgnoresCaseSoHandEditedFilesStillWork() {
        assertEquals(ThemeMode.DARK, ThemeMode.parse("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("Light"))
    }

    @Test
    fun unknownOrMissingValueFallsBackToFollowingTheSystem() {
        // 存档是给人方便的，不是真相源：认不出就跟随系统，不能让 App 起不来。
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("PURPLE"))
    }

    // ---- JsonSettingsStore ----

    @Test
    fun savedThemeComesBackAfterRestart() = runTest {
        val file = File(temp.newFolder("settings"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        JsonSettingsStore(file, dispatcher).save(AppSettings(theme = ThemeMode.DARK))
        val loaded = JsonSettingsStore(file, dispatcher).load()

        assertEquals(ThemeMode.DARK, loaded.theme)
    }

    @Test
    fun missingFileLoadsDefaultsInsteadOfFailing() = runTest {
        val file = File(temp.newFolder("empty"), "settings.json")
        val loaded = JsonSettingsStore(file, StandardTestDispatcher(testScheduler)).load()

        assertEquals(AppSettings(), loaded)
    }

    @Test
    fun brokenFileLoadsDefaultsInsteadOfFailing() = runTest {
        val dir = temp.newFolder("broken")
        val file = File(dir, "settings.json")
        file.writeText("{ this is not json")

        val loaded = JsonSettingsStore(file, StandardTestDispatcher(testScheduler)).load()

        assertEquals(ThemeMode.SYSTEM, loaded.theme)
    }

    @Test
    fun savingLeavesNoTemporaryFileBehind() = runTest {
        val dir = temp.newFolder("clean")
        val file = File(dir, "settings.json")

        JsonSettingsStore(file, StandardTestDispatcher(testScheduler))
            .save(AppSettings(theme = ThemeMode.LIGHT))

        assertTrue(file.isFile)
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    // ---- SettingsRepository ----

    @Test
    fun repositoryLoadsOnceAndThenServesFromMemory() = runTest {
        val dir = temp.newFolder("repo")
        val file = File(dir, "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        JsonSettingsStore(file, dispatcher).save(AppSettings(theme = ThemeMode.DARK))

        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))
        repository.load()

        assertEquals(ThemeMode.DARK, repository.settings.first().theme)
    }

    @Test
    fun settingTheThemeUpdatesTheFlowAndTheFile() = runTest {
        val dir = temp.newFolder("write")
        val file = File(dir, "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))

        repository.setTheme(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, repository.settings.first().theme)
        assertEquals(ThemeMode.LIGHT, JsonSettingsStore(file, dispatcher).load().theme)
    }

    // ---- 仅 Wi-Fi 传输 ----

    @Test
    fun wifiOnlyIsOffByDefault() = runTest {
        // 默认关：手机上下载几个 G 的东西，用户自己知道流量够不够；
        // 反过来「明明有网却不动」是更难理解的故障。
        val file = File(temp.newFolder("wifiDefault"), "settings.json")

        val loaded = JsonSettingsStore(file, StandardTestDispatcher(testScheduler)).load()

        assertFalse(loaded.wifiOnly)
    }

    @Test
    fun savedWifiOnlyComesBackAfterRestart() = runTest {
        val file = File(temp.newFolder("wifi"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        JsonSettingsStore(file, dispatcher).save(AppSettings(wifiOnly = true))

        assertTrue(JsonSettingsStore(file, dispatcher).load().wifiOnly)
    }

    @Test
    fun settingWifiOnlyLeavesTheThemeAlone() = runTest {
        val dir = temp.newFolder("wifiKeep")
        val file = File(dir, "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))
        repository.setTheme(ThemeMode.DARK)

        repository.setWifiOnly(true)

        val stored = JsonSettingsStore(file, dispatcher).load()
        assertEquals(ThemeMode.DARK, stored.theme)
        assertTrue(stored.wifiOnly)
    }

    @Test
    fun settingTheThemeLeavesWifiOnlyAlone() = runTest {
        val dir = temp.newFolder("themeKeep")
        val file = File(dir, "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))
        repository.setWifiOnly(true)

        repository.setTheme(ThemeMode.LIGHT)

        val stored = JsonSettingsStore(file, dispatcher).load()
        assertEquals(ThemeMode.LIGHT, stored.theme)
        assertTrue(stored.wifiOnly)
    }

    // ---- 排序偏好 ----

    @Test
    fun defaultSettingsHaveNoCustomSort() {
        assertFalse(AppSettings().hasCustomSort)
        assertEquals(SortOrderName.NAME, AppSettings().sortOrder)
    }

    @Test
    fun anyNonDefaultSortCountsAsCustom() {
        assertTrue(AppSettings(sortDescending = true).hasCustomSort)
        assertTrue(AppSettings(sortOrder = SortOrderName.TIME).hasCustomSort)
    }

    @Test
    fun parseAcceptsEveryStoredSortName() {
        assertEquals(SortOrderName.NAME, SortOrderName.parse("NAME"))
        assertEquals(SortOrderName.TIME, SortOrderName.parse("time"))
        assertEquals(SortOrderName.SIZE, SortOrderName.parse("Size"))
    }

    @Test
    fun anUnknownSortNameFallsBackToName() {
        // 旧版本写的值、或者手改坏的值不该让列表排不出来。
        assertEquals(SortOrderName.NAME, SortOrderName.parse(null))
        assertEquals(SortOrderName.NAME, SortOrderName.parse("RELEVANCE"))
    }

    @Test
    fun savedSortComesBackAfterRestart() = runTest {
        val file = File(temp.newFolder("sort"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        JsonSettingsStore(file, dispatcher).save(
            AppSettings(sortOrder = SortOrderName.SIZE, sortDescending = true),
        )

        val loaded = JsonSettingsStore(file, dispatcher).load()
        assertEquals(SortOrderName.SIZE, loaded.sortOrder)
        assertTrue(loaded.sortDescending)
    }

    @Test
    fun anOldFileWithoutSortKeysStillLoads() = runTest {
        // 上一个版本的存档里没有这两个键，读出来必须是默认排序而不是崩溃。
        val file = File(temp.newFolder("legacy"), "settings.json")
        file.writeText("{\"theme\":\"DARK\",\"wifiOnly\":\"true\"}")
        val loaded = JsonSettingsStore(file, StandardTestDispatcher(testScheduler)).load()
        assertEquals(ThemeMode.DARK, loaded.theme)
        assertEquals(SortOrderName.NAME, loaded.sortOrder)
        assertFalse(loaded.sortDescending)
    }

    @Test
    fun settingTheSortLeavesEverythingElseAlone() = runTest {
        val file = File(temp.newFolder("sortKeep"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))
        repository.setTheme(ThemeMode.DARK)
        repository.setWifiOnly(true)

        repository.setSort(SortOrderName.TIME, descending = true)

        val stored = JsonSettingsStore(file, dispatcher).load()
        assertEquals(ThemeMode.DARK, stored.theme)
        assertTrue(stored.wifiOnly)
        assertEquals(SortOrderName.TIME, stored.sortOrder)
        assertTrue(stored.sortDescending)
    }

    // ---- LayoutName ----

    @Test
    fun listIsTheDefaultLayout() {
        assertEquals(LayoutName.LIST, AppSettings().layout)
    }

    @Test
    fun parseAcceptsEveryStoredLayoutName() {
        for (name in LayoutName.entries) {
            assertEquals(name, LayoutName.parse(name.name));
        }
        assertEquals(LayoutName.GRID, LayoutName.parse("grid"))
    }

    @Test
    fun anUnknownLayoutFallsBackToList() {
        assertEquals(LayoutName.LIST, LayoutName.parse(null))
        assertEquals(LayoutName.LIST, LayoutName.parse("mosaic"))
    }

    @Test
    fun savedLayoutComesBackAfterRestart() = runTest {
        val file = File(temp.newFolder("layout"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)

        JsonSettingsStore(file, dispatcher).save(AppSettings(layout = LayoutName.GRID))

        assertEquals(LayoutName.GRID, JsonSettingsStore(file, dispatcher).load().layout)
    }

    @Test
    fun anOldFileWithoutALayoutKeyStillLoads() = runTest {
        // 加这个键之前的存档里没有它，读出来必须是列表而不是崩溃。
        val file = File(temp.newFolder("legacyLayout"), "settings.json")
        file.writeText("{\"theme\":\"DARK\",\"wifiOnly\":\"true\"}")
        val loaded = JsonSettingsStore(file, StandardTestDispatcher(testScheduler)).load()
        assertEquals(LayoutName.LIST, loaded.layout)
        assertEquals(ThemeMode.DARK, loaded.theme)
    }

    @Test
    fun settingTheLayoutLeavesTheSortAlone() = runTest {
        val file = File(temp.newFolder("layoutKeep"), "settings.json")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = SettingsRepository(JsonSettingsStore(file, dispatcher))
        repository.setSort(SortOrderName.SIZE, descending = true)

        repository.setLayout(LayoutName.GRID)

        val stored = JsonSettingsStore(file, dispatcher).load()
        assertEquals(SortOrderName.SIZE, stored.sortOrder)
        assertTrue(stored.sortDescending)
        assertEquals(LayoutName.GRID, stored.layout)
    }
}
