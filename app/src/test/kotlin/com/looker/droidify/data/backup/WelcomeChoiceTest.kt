package com.looker.droidify.data.backup

import com.looker.droidify.datastore.Settings
import com.looker.droidify.datastore.model.BackgroundStyle
import com.looker.droidify.datastore.model.InstallerType
import com.looker.droidify.datastore.model.Theme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A restore made from the welcome pages keeps what the person chose on them, and brings the rest. */
class WelcomeChoiceTest {

    private val chosen = Settings(
        installerType = InstallerType.ROOT,
        theme = Theme.AMOLED,
        themeColor = 0x123456,
        dynamicTheme = false,
        backgroundStyle = BackgroundStyle.NONE,
    )

    private val backup = Settings(
        installerType = InstallerType.SESSION,
        theme = Theme.LIGHT,
        themeColor = 0x654321,
        dynamicTheme = true,
        backgroundStyle = BackgroundStyle.HALO,
        deleteApkOnInstall = true,
        autoTranslate = true,
    )

    @Test
    fun `the installer chosen on the welcome page survives a backup made with another`() {
        val restored = backup.keeping(setOf(WelcomeChoice.INSTALLER), chosen)
        assertEquals(InstallerType.ROOT, restored.installerType)
        assertEquals(Theme.LIGHT, restored.theme)
        assertTrue(restored.autoTranslate)
    }

    @Test
    fun `every choice is kept, and nothing else`() {
        val restored = backup.keeping(WelcomeChoice.entries.toSet(), chosen)
        assertEquals(InstallerType.ROOT, restored.installerType)
        assertEquals(Theme.AMOLED, restored.theme)
        assertEquals(0x123456, restored.themeColor)
        assertFalse(restored.dynamicTheme)
        assertEquals(BackgroundStyle.NONE, restored.backgroundStyle)
        assertTrue(restored.deleteApkOnInstall)
        assertTrue(restored.autoTranslate)
    }

    @Test
    fun `nothing chosen brings the backup whole`() {
        assertEquals(backup, backup.keeping(emptySet(), chosen))
    }

    @Test
    fun `the legacy installer kept turns deleting the APK off`() {
        val legacy = chosen.copy(installerType = InstallerType.LEGACY)
        assertFalse(backup.keeping(setOf(WelcomeChoice.INSTALLER), legacy).deleteApkOnInstall)
    }
}
