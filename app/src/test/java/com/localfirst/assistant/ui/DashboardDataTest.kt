package com.localfirst.assistant.ui
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.localfirst.assistant.settings.ProactivityLevel
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class DashboardDataTest {
    @Test fun sourceDateObjectsAndEpochFollowupsAreDisplayedAsLocalTimes() {
        val event=JSONObject("""{"start":{"dateTime":"2026-10-08T14:00:00Z","timeZone":"UTC"}}""")
        assertEquals("10:00 AM",eventTime(event,"America/New_York"))
        assertEquals("All day",eventTime(JSONObject("""{"start":{"date":"2026-10-08"}}"""),"UTC"))
        assertEquals("Time unavailable",eventTime(JSONObject("""{"start":{}}"""),"UTC"))
        assertNull(followupTime(JSONObject("""{"due":null}"""),"UTC"))
        assertTrue(followupTime(JSONObject().put("due",1791468000.0),"America/New_York")!!.contains("10:00 AM"))
    }
    @Test fun proactivityLevelsSelectOnlyEligibleExistingSuggestions() {
        assertFalse(dashboardProposalKinds(ProactivityLevel.CONSERVATIVE,"situation"))
        assertTrue(dashboardProposalKinds(ProactivityLevel.CONSERVATIVE,"followup"))
        assertTrue(dashboardProposalKinds(ProactivityLevel.THOUGHTFUL,"situation"))
        assertFalse(dashboardProposalKinds(ProactivityLevel.HIGH,"morning"))
        assertEquals(listOf(2,4,6),ProactivityLevel.entries.map { it.suggestionLimit })
    }
}
