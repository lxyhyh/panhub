package com.panhub.feature.settings

import com.panhub.core.platform.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    @Test
    fun optionsCoverEveryThemeMode() {
        val modes = SettingsLogic.themeOptions().map { it.mode }
        assertEquals(ThemeMode.entries.toList(), modes)
    }

    @Test
    fun followSystemComesFirst() {
        // 默认值是跟随系统，选项列表里它就该排第一，否则用户要找一下才知道当前是什么。
        assertEquals(ThemeMode.SYSTEM, SettingsLogic.themeOptions().first().mode)
    }

    @Test
    fun everyOptionHasALabelAndAHint() {
        SettingsLogic.themeOptions().forEach { option ->
            assertTrue(option.label.isNotBlank())
            assertTrue(option.hint.isNotBlank())
        }
    }

    @Test
    fun systemModeFollowsTheSystemValue() {
        assertTrue(SettingsLogic.darkTheme(ThemeMode.SYSTEM, systemInDark = true))
        assertFalse(SettingsLogic.darkTheme(ThemeMode.SYSTEM, systemInDark = false))
    }

    @Test
    fun explicitModesIgnoreTheSystem() {
        // 用户明确选了浅色，系统切到深色也不该跟着变。
        assertFalse(SettingsLogic.darkTheme(ThemeMode.LIGHT, systemInDark = true))
        assertTrue(SettingsLogic.darkTheme(ThemeMode.DARK, systemInDark = false))
    }

    @Test
    fun labelOfReturnsTheMatchingTitle() {
        assertEquals("深色", SettingsLogic.labelOf(ThemeMode.DARK))
        assertEquals("浅色", SettingsLogic.labelOf(ThemeMode.LIGHT))
        assertEquals("跟随系统", SettingsLogic.labelOf(ThemeMode.SYSTEM))
    }
}
