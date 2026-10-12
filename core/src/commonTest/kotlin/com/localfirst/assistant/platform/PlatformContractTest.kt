package com.localfirst.assistant.platform

import com.localfirst.assistant.genui.UiBindings
import kotlin.test.*

class PlatformContractTest {
    @Test fun citationIdsAndSha1MatchAcrossPlatforms() {
        val hex = PlatformMessageDigest.sha1("abc".encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex)
        assertEquals("web_327c3f", UiBindings.ref("https://example.com"))
    }
    @Test fun numberEpsilonMatchesTheSharedInterpreterContract() {
        assertEquals(2.220446049250313e-16, PlatformMath.ulp(1.0))
        assertEquals(Double.MIN_VALUE, PlatformMath.ulp(0.0))
    }
    @Test fun inlineFlagsAndAbsoluteEndKeepJvmSemantics() {
        assertTrue(platformRegex("(?ims)^first.*last\\z").matches("FIRST\nlast"))
        assertFalse(platformRegex("x\\z").containsMatchIn("x\n"))
        assertTrue(buildRegex("a.b", false, false, true).matches("a\nb"))
        assertTrue(platformRegex("\\.[a-z]\\z").matches(".a"))
    }
}
