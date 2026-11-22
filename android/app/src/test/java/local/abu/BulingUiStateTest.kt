package local.abu

import local.abu.ui.model.sendableChatText
import local.abu.ui.theme.ThemeMode
import local.abu.ui.theme.ThemePreference
import kotlin.test.*

class BulingUiStateTest {
    @Test
    fun themeDefaultsToSystemAndPersistsEveryExplicitChoice() {
        var setting: String? = null
        fun reopen() = ThemePreference({ setting }, { setting = it })
        assertEquals(ThemeMode.SYSTEM, reopen().mode)
        assertTrue(reopen().mode.isDark(systemDark = true))
        reopen().choose(ThemeMode.LIGHT)
        assertFalse(reopen().mode.isDark(systemDark = true))
        reopen().choose(ThemeMode.DARK)
        assertTrue(reopen().mode.isDark(systemDark = false))
        reopen().choose(ThemeMode.SYSTEM)
        assertFalse(reopen().mode.isDark(systemDark = false))
        setting = "unrecognized"
        assertEquals(ThemeMode.SYSTEM, reopen().mode)
    }

    @Test
    fun systemChangesRespectSelectedModeWithoutChangingThePreference() {
        var setting: String? = null
        val preference = ThemePreference({ setting }, { setting = it })
        val systemInputs = listOf(false, true, false)
        val expected = mapOf(
            ThemeMode.SYSTEM to listOf(false, true, false),
            ThemeMode.LIGHT to listOf(false, false, false),
            ThemeMode.DARK to listOf(true, true, true),
        )
        for ((mode, colors) in expected) {
            preference.choose(mode)
            assertEquals(colors, systemInputs.map { preference.mode.isDark(systemDark = it) })
            assertEquals(mode, preference.mode)
        }
    }

    @Test
    fun composerKeepsOriginalUnicodeTextAndBlocksInvalidOrRepeatedSend() {
        val max = "🪻".repeat(4000)
        assertEquals(max, sendableChatText(max, busy = false))
        assertEquals("  一行\n第二行  ", sendableChatText("  一行\n第二行  ", busy = false))
        assertNull(sendableChatText(max + "a", busy = false))
        assertNull(sendableChatText("　", busy = false))
        assertNull(sendableChatText("不可重复提交", busy = true))
    }
}
