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
}
