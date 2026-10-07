package com.localfirst.assistant.tools.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {
    private val apps = listOf(
        InstalledApp("Spotify", "com.spotify.music"),
        InstalledApp("Camera", "com.oneplus.camera"),
        InstalledApp("Google Maps", "com.google.android.apps.maps"),
        InstalledApp("Maps Go", "com.google.android.apps.mapslite"),
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
    )

    @Test
    fun prefersExactThenPrefixThenContains() {
        assertEquals("com.spotify.music", AppMatcher.best("spotify", apps).packageName)
        assertEquals("com.oneplus.camera", AppMatcher.best("the Camera app".removePrefix("the "), apps).packageName)
        assertEquals("com.google.android.youtube", AppMatcher.best("YouTube", apps).packageName)
        assertEquals("com.google.android.apps.youtube.music", AppMatcher.best("youtube mus", apps).packageName)
        assertEquals("com.google.android.apps.maps", AppMatcher.best("google", apps).packageName)
    }

    @Test
    fun ambiguousAndMissingNamesExplainThemselves() {
        val ambiguous = runCatching { AppMatcher.best("maps", apps) }.exceptionOrNull()
        assertTrue(ambiguous is PhoneActionException)
        assertTrue(ambiguous!!.message!!.contains("Google Maps") && ambiguous.message!!.contains("Maps Go"))

        val missing = runCatching { AppMatcher.best("Netflix", apps) }.exceptionOrNull()
        assertTrue(missing!!.message!!.contains("No installed app"))
    }
}
