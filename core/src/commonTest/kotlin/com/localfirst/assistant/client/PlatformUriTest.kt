package com.localfirst.assistant.client
import kotlin.test.*
/** URLs parse the same on both platforms, so evidence links and citation checks work in the browser too. */
class PlatformUriTest {
    @Test fun publicEvidenceLinksParseAndCount() {
        val u = com.localfirst.assistant.platform.PlatformUri("https://example.org/rates")
        assertEquals("https|example.org|-1|null", "${u.scheme}|${u.host}|${u.port}|${u.rawUserInfo}")
        assertTrue(com.localfirst.assistant.grounding.GroundedEvidence.safePublicUrl("https://example.org/rates"))
        val r = com.localfirst.assistant.grounding.GroundedEvidence.search("""{"evidence":[{"title":"Rates","url":"https://example.org/rates","status":"retrieved","passages":["5%"]}]}""")
        assertEquals(1, r.sources.size)
        assertFalse(com.localfirst.assistant.grounding.GroundedEvidence.safePublicUrl("https://user@example.org/"))
        assertEquals(1, com.localfirst.assistant.grounding.GroundingPrecheck.audit("See https://example.org/rates.", r.sources).matchedLinks)
    }
}
