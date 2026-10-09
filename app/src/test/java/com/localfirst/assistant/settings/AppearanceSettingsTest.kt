package com.localfirst.assistant.settings

import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.Color
import com.localfirst.assistant.ui.theme.contrast
import com.localfirst.assistant.ui.theme.fridayColors

class AppearanceSettingsTest {
    @Test fun existingInstallsGetDarkCoralAndThoughtfulWithoutMigration() {
        val settings=AppearanceSettings.from(null,null,null,false)
        assertEquals(ThemeMode.DARK,settings.theme)
        assertEquals("E89980",settings.accentHex)
        assertEquals(ProactivityLevel.THOUGHTFUL,settings.proactivity)
        assertFalse(settings.reducedMotion)
    }
    @Test fun invalidSavedValuesFallBackAndValidColorsNormalize() {
        assertEquals(AppearanceSettings(),AppearanceSettings.from("bad","transparent","bad",false))
        assertEquals("00AABB",AppearanceSettings.accent(" #00aabb "))
        assertNull(AppearanceSettings.accent("00AABB00"))
        assertNull(AppearanceSettings.accent("nothex"))
    }
    @Test fun customAccentsRemainReadableInBothThemes() {
        for(dark in listOf(false,true))for(accent in listOf(Color(0xFFE89980),Color.White,Color.Black,Color(0xFF88ADD0),Color.Yellow)) {
            val colors=fridayColors(dark,accent)
            assertTrue(contrast(colors.primary,colors.background)>=4.5f)
            assertTrue(contrast(colors.onPrimary,colors.primary)>=4.5f)
            assertTrue(contrast(colors.onSurface,colors.surface)>=4.5f)
            assertTrue(contrast(colors.onSurfaceVariant,colors.background)>=4.5f)
        }
    }
}
