package com.localfirst.assistant.ui
import com.localfirst.assistant.ui.dashboard.dashboardProposalKinds
import org.junit.Assert.*
import org.junit.Test
import com.localfirst.assistant.settings.ProactivityLevel
class DashboardDataTest {
    @Test fun proactivityLevelsSelectOnlyEligibleExistingSuggestions() {
        assertFalse(dashboardProposalKinds(ProactivityLevel.CONSERVATIVE,"situation"))
        assertTrue(dashboardProposalKinds(ProactivityLevel.CONSERVATIVE,"followup"))
        assertTrue(dashboardProposalKinds(ProactivityLevel.THOUGHTFUL,"situation"))
        assertFalse(dashboardProposalKinds(ProactivityLevel.HIGH,"morning"))
        assertEquals(listOf(2,4,6),ProactivityLevel.entries.map { it.suggestionLimit })
    }
}
