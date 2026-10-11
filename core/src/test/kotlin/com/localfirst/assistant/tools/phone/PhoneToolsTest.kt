package com.localfirst.assistant.tools.phone

import com.localfirst.assistant.conversation.ConversationSession
import com.localfirst.assistant.conversation.Message
import com.localfirst.assistant.model.ModelProvider
import com.localfirst.assistant.model.ModelResponse
import com.localfirst.assistant.presentation.ToolLabels
import com.localfirst.assistant.presentation.ToolStepState
import com.localfirst.assistant.tools.ConfirmationRequest
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolConfirmer
import com.localfirst.assistant.tools.ToolDefinition
import com.localfirst.assistant.tools.ToolRegistry
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolsTest {
    private val phone = FakePhone()
    private val now = LocalDateTime.of(2026, 10, 7, 9, 30)
    private val registry = ToolRegistry().apply { phoneTools(phone) { now }.forEach(::register) }

    private suspend fun run(name: String, args: String, approve: Boolean? = null) =
        registry.execute(
            ToolCall("c", name, args),
            confirmer = approve?.let { answer ->
                ToolConfirmer { request ->
                    phone.prompts += request.prompt
                    answer
                }
            },
        )

    @Test
    fun everyToolHasAUniqueNameAndAnObjectSchema() {
        val defs = registry.definitions()
        assertEquals(15, defs.size)
        assertEquals(defs.size, defs.map { it.name }.toSet().size)
        assertTrue(defs.all { it.inputSchema["type"].toString() == "\"object\"" && it.description.isNotBlank() })
    }

    @Test
    fun appsUrlsAndMapsPassThroughAndUrlsMustBeWeb() = runBlocking {
        assertEquals("Opened Spotify.", run("open_app", """{"name":"Spotify"}""").content)
        assertTrue(run("open_url", """{"url":"https://example.com"}""").success)
        val bad = run("open_url", """{"url":"intent://evil"}""")
        assertFalse(bad.success)
        assertTrue(bad.content.contains("http"))
        run("open_maps", """{"query":"Golden Gate Bridge","navigate":true}""")
        assertEquals("maps Golden Gate Bridge navigate=true", phone.calls.last())
    }

    @Test
    fun mediaAndMusicArgumentsAreValidated() = runBlocking {
        run("media_control", """{"action":"next"}""")
        assertEquals("media NEXT", phone.calls.last())
        assertFalse(run("media_control", """{"action":"rewind"}""").success)
        assertFalse(run("media_control", "{}").success)

        run("play_music", """{"query":"Abbey Road","kind":"album"}""")
        assertEquals("music Abbey Road ALBUM", phone.calls.last())
        run("play_music", """{"query":"something chill"}""")
        assertEquals("music something chill ANY", phone.calls.last())

        phone.playing = NowPlaying("Spotify", "Yesterday", "The Beatles", "Help!", playing = true)
        assertEquals("Playing in Spotify: Yesterday by The Beatles from Help!.", run("now_playing", "{}").content)
        phone.playing = null
        assertEquals("Nothing is playing on the phone right now.", run("now_playing", "{}").content)
    }

    @Test
    fun alarmsTimersFlashlightAndBattery() = runBlocking {
        run("set_alarm", """{"hour":7,"minute":5,"label":"Gym","days":["mon","Wednesday"]}""")
        assertEquals(AlarmRequest(7, 5, "Gym", listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)), phone.alarm)
        assertFalse(run("set_alarm", """{"hour":24,"minute":0}""").success)
        assertFalse(run("set_alarm", """{"hour":7,"minute":0,"days":["someday"]}""").success)

        run("set_timer", """{"minutes":5,"seconds":30,"label":"Tea"}""")
        assertEquals("timer 330 Tea", phone.calls.last())
        assertFalse(run("set_timer", "{}").success)

        run("flashlight", """{"on":"off"}""")
        assertEquals("torch false", phone.calls.last())

        phone.battery = BatteryStatus(54, charging = true, source = "USB", minutesToFull = 95)
        assertEquals("Battery is at 54%, charging (USB), about 1 h 35 min to full.", run("battery_status", "{}").content)
    }

    @Test
    fun callsResolveContactsAndNeedApproval() = runBlocking {
        phone.contacts += listOf(
            ContactMatch("Mom", "+1 555 0100", "mobile"),
            ContactMatch("Mom", "+1 555 0199", "home"),
            ContactMatch("Momo Sushi", "+1 555 0123", "work"),
        )

        val unapproved = registry.execute(ToolCall("c", "place_call", """{"to":"Mom"}"""))
        assertTrue(unapproved.content.contains("requires confirmation"))

        val declined = run("place_call", """{"to":"Mom"}""", approve = false)
        assertFalse(declined.success)
        assertTrue(declined.content.startsWith("The user declined"))
        assertTrue(phone.calls.none { it.startsWith("call") })

        val approved = run("place_call", """{"to":"mom"}""", approve = true)
        assertTrue(approved.success)
        assertEquals("Call Mom (mobile, +1 555 0100)", phone.prompts.last())
        assertEquals("call +1 555 0100", phone.calls.last())

        run("place_call", """{"to":"+44 20 7946 0000"}""", approve = true)
        assertEquals("Call +44 20 7946 0000", phone.prompts.last())
    }

    @Test
    fun ambiguousOrMissingContactsExplainThemselves() = runBlocking {
        phone.contacts += listOf(
            ContactMatch("Alex Kim", "+1 555 0001", "mobile"),
            ContactMatch("Alex Diaz", "+1 555 0002", "mobile"),
            ContactMatch("Sam", "+1 555 0003", "home"),
            ContactMatch("Sam", "+1 555 0004", "work"),
        )
        val several = run("send_text", """{"to":"Alex","message":"hi"}""", approve = true)
        assertFalse(several.success)
        assertTrue(several.content.contains("Alex Kim") && several.content.contains("Alex Diaz"))

        val numbers = run("send_text", """{"to":"Sam","message":"hi"}""", approve = true)
        assertTrue(numbers.content.contains("several numbers"))

        val none = run("place_call", """{"to":"Nobody"}""", approve = true)
        assertTrue(none.content.contains("No contact matches"))
        assertTrue(phone.prompts.isEmpty())
    }

    @Test
    fun textsShowTheMessageInTheApproval() = runBlocking {
        phone.contacts += ContactMatch("Jordan", "+1 555 0142", "mobile")
        val result = run("send_text", """{"to":"Jordan","message":"Running 10 min late"}""", approve = true)
        assertTrue(result.success)
        assertEquals("Text Jordan (mobile, +1 555 0142): “Running 10 min late”", phone.prompts.single())
        assertEquals("text +1 555 0142 Running 10 min late", phone.calls.last())
    }

    @Test
    fun calendarEventsParseTimesAndDefaults() = runBlocking {
        run("add_calendar_event", """{"title":"Dentist","start":"2026-10-08T15:00","location":"Main St"}""")
        assertEquals(LocalDateTime.of(2026, 10, 8, 16, 0), phone.event!!.end)
        run("add_calendar_event", """{"title":"Trip","start":"2026-10-10"}""")
        assertEquals(LocalDate.of(2026, 10, 10), phone.event!!.allDayDate)
        assertFalse(run("add_calendar_event", """{"title":"x","start":"tomorrow at 3"}""").success)
        assertFalse(run("add_calendar_event", """{"title":"x","start":"2026-10-08T15:00","end":"2026-10-08T14:00"}""").success)

        phone.events += CalendarEntry("Standup", LocalDateTime.of(2026, 10, 7, 10, 0), LocalDateTime.of(2026, 10, 7, 10, 15), false, null)
        val listed = run("upcoming_events", """{"days":2}""")
        assertEquals("Events from Wed Oct 7 for 2 days:\n- Wed Oct 7, 10:00 AM–10:15 AM: Standup", listed.content)
        assertEquals(now to LocalDateTime.of(2026, 10, 9, 0, 0), phone.eventRange)

        // "What's on tomorrow?" covers all of tomorrow, not the rest of today.
        phone.events.clear()
        assertEquals("No events on Thu Oct 8.", run("upcoming_events", """{"start_date":"2026-10-08"}""").content)
        assertEquals(LocalDateTime.of(2026, 10, 8, 0, 0) to LocalDateTime.of(2026, 10, 9, 0, 0), phone.eventRange)
        assertFalse(run("upcoming_events", """{"start_date":"tomorrow"}""").success)
    }

    @Test
    fun phoneFailuresBecomeReadableToolResults() = runBlocking {
        phone.failWith = "Calendar access was not granted."
        val result = run("upcoming_events", "{}")
        assertFalse(result.success)
        assertEquals("Calendar access was not granted.", result.content)
    }

    @Test
    fun aSessionPausesForApprovalAndCarriesOn() = runBlocking {
        phone.contacts += ContactMatch("Mom", "+1 555 0100", "mobile")
        val model = object : ModelProvider {
            var turn = 0
            override suspend fun sendConversation(messages: List<Message>, tools: List<ToolDefinition>): ModelResponse =
                if (turn++ == 0) {
                    ModelResponse.ToolCallResponse(listOf(ToolCall("k", "place_call", """{"to":"Mom"}""")))
                } else {
                    ModelResponse.TextResponse("Calling Mom now.")
                }
        }
        val asked = mutableListOf<ConfirmationRequest>()
        val session = ConversationSession(model, registry, systemPrompt = "", confirmer = { asked += it; true })
        session.submitUserMessage("Call mom")

        assertEquals("place_call", asked.single().toolName)
        assertEquals("k", asked.single().callId)
        assertEquals("call +1 555 0100", phone.calls.last())
        assertEquals(Message.Assistant("Calling Mom now."), session.snapshot().last())
    }

    @Test
    fun labelsDescribeEachTool() {
        fun label(name: String, args: String, state: ToolStepState = ToolStepState.DONE) =
            ToolLabels.label(name, Json.parseToJsonElement(args) as JsonObject, state)
        assertEquals("Set an alarm for 7:05 AM", label("set_alarm", """{"hour":7,"minute":5}"""))
        assertEquals("Set an alarm for 12:00 PM", label("set_alarm", """{"hour":12,"minute":0}"""))
        assertEquals("Started a 5 min timer", label("set_timer", """{"minutes":5}"""))
        assertEquals("Started a 1 hr 30 min timer", label("set_timer", """{"hours":1,"minutes":30}"""))
        assertEquals("Asking Spotify to play “Abbey Road”…", label("play_music", """{"query":"Abbey Road"}""", ToolStepState.RUNNING))
        assertEquals("Didn't text Mom", label("send_text", """{"to":"Mom"}""", ToolStepState.FAILED))
        assertEquals("Started navigation to Home", label("open_maps", """{"query":"Home","navigate":true}"""))
        assertEquals("Opened example.com", label("open_url", """{"url":"https://www.example.com/a"}"""))
        assertEquals("Turned the flashlight off", label("flashlight", """{"on":false}"""))
    }

    private class FakePhone : PhoneActions {
        val calls = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        val contacts = mutableListOf<ContactMatch>()
        val events = mutableListOf<CalendarEntry>()
        var playing: NowPlaying? = null
        var battery = BatteryStatus(100, charging = false, source = null)
        var alarm: AlarmRequest? = null
        var event: CalendarEventRequest? = null
        var eventRange: Pair<LocalDateTime, LocalDateTime>? = null
        var failWith: String? = null

        private fun act(entry: String, reply: String): String {
            failWith?.let { throw PhoneActionException(it) }
            calls += entry
            return reply
        }

        override suspend fun openApp(name: String) = act("app $name", "Opened $name.")
        override suspend fun openUrl(url: String) = act("url $url", "Opened $url.")
        override suspend fun openMaps(query: String, navigate: Boolean) = act("maps $query navigate=$navigate", "Done.")
        override suspend fun mediaControl(action: MediaAction) = act("media $action", "Done.")
        override suspend fun playMusic(request: MusicRequest) = act("music ${request.query} ${request.kind}", "Done.")
        override suspend fun nowPlaying() = playing
        override suspend fun setAlarm(alarm: AlarmRequest): String {
            this.alarm = alarm
            return act("alarm", "Done.")
        }
        override suspend fun setTimer(seconds: Int, label: String?) = act("timer $seconds $label", "Done.")
        override suspend fun setFlashlight(on: Boolean) = act("torch $on", "Done.")
        override suspend fun batteryStatus() = battery
        override suspend fun searchContacts(query: String, limit: Int) =
            contacts.filter { it.name.contains(query, ignoreCase = true) }.take(limit)
        override suspend fun placeCall(number: String) = act("call $number", "Calling $number.")
        override suspend fun sendText(number: String, message: String) = act("text $number $message", "Sent.")
        override suspend fun addCalendarEvent(event: CalendarEventRequest): String {
            this.event = event
            return act("event ${event.title}", "Added.")
        }
        override suspend fun upcomingEvents(from: LocalDateTime, to: LocalDateTime): List<CalendarEntry> {
            failWith?.let { throw PhoneActionException(it) }
            eventRange = from to to
            return events
        }
    }
}
