package com.localfirst.assistant.tools.phoneuse

import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.conversation.AttachmentKind
import com.localfirst.assistant.tools.ToolCall
import com.localfirst.assistant.tools.ToolRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUseToolsTest {
    private val gmail = PhoneApp("Gmail", "com.google.android.gm", recent = true)
    private val chrome = PhoneApp("Chrome", "com.android.chrome")
    private val bank = PhoneApp("Bank", "com.example.bank")

    private fun gmailScreen(screenshot: Attachment? = null) = AppSnapshot(
        app = gmail,
        windowTitle = "Inbox",
        width = 576, height = 1280, displayWidth = 1080, displayHeight = 2400,
        screenshot = screenshot,
        elements = listOf(
            AppElement(0, "window", "Inbox", bounds = Bounds(0, 0, 576, 1280), children = listOf(
                AppElement(1, "text field", "Search in mail", states = listOf("settable"), bounds = Bounds(40, 60, 536, 110)),
                AppElement(2, "button", "Compose", bounds = Bounds(400, 1150, 560, 1230)),
                AppElement(3, "list", null, states = listOf("scrollable"), bounds = Bounds(0, 120, 576, 1140), children = listOf(
                    AppElement(4, "group", "Alice, Lunch tomorrow?", secondaryActions = listOf("Archive", "Long press"), bounds = Bounds(0, 120, 576, 220)),
                )),
                AppElement(5, "button", "Send", bounds = Bounds(500, 40, 560, 90)),
            )),
        ),
    )

    private inner class FakeDriver : PhoneUseDriver {
        val calls = mutableListOf<String>()
        var foreground: String? = "com.android.launcher"
        var next: () -> AppSnapshot = { gmailScreen() }
        var shown: PhoneApp? = null
        override suspend fun listApps() = listOf(gmail, chrome, bank, PhoneApp("FRIDAY", "com.localfirst.assistant"))
        override suspend fun foregroundPackage() = foreground
        override suspend fun open(app: PhoneApp) { calls += "open ${app.packageName}"; foreground = app.packageName }
        override suspend fun snapshot(screenshot: Boolean): AppSnapshot = next().also { calls += "snapshot $screenshot"; foreground = it.app.packageName }
        override suspend fun click(target: Target, clickCount: Int, button: MouseButton) = "Clicked $target x$clickCount ${button.wireName}.".also { calls += it }
        override suspend fun secondaryAction(element: Int, action: String) = "Did $action on $element.".also { calls += it }
        override suspend fun scroll(element: Int?, direction: ScrollDirection, pages: Double) = "Scrolled $element ${direction.wireName} $pages.".also { calls += it }
        override suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int) = "Dragged.".also { calls += "drag $fromX,$fromY $toX,$toY" }
        override suspend fun typeText(text: String) = "Typed.".also { calls += "type [$text]" }
        override suspend fun pressKey(key: PhoneKey) = "Pressed ${key.wireName}.".also { calls += "key ${key.name}" }
        override suspend fun setValue(element: Int, value: String) = "Set.".also { calls += "set $element=$value" }
        override fun showActivity(app: PhoneApp?) { shown = app }
    }

    private val driver = FakeDriver()
    private val store = InMemoryAppAccessStore()
    private var answer = AccessDecision.ALLOW_FOR_CONVERSATION
    private val asked = mutableListOf<String>()
    private var reviewAnswer = true
    private val reviewed = mutableListOf<String>()
    private val session = PhoneUseSession(
        access = store,
        askAccess = { app -> asked += app.packageName; answer },
        ownPackage = "com.localfirst.assistant",
        reviewAction = { _, text -> reviewed += text; reviewAnswer },
    ).also { it.beginTurn() }
    private var screenshots = true
    private val registry = ToolRegistry().apply { phoneUseTools(driver, { session }, { screenshots }).forEach(::register) }

    private fun run(name: String, json: String) = runBlocking { registry.execute(ToolCall("c", name, json)) }

    @Test
    fun theToolSetMatchesCodexComputerUse() {
        assertEquals(
            listOf("list_apps", "get_app_state", "click", "perform_secondary_action", "scroll", "drag", "type_text", "press_key", "set_value", "phone_script"),
            registry.definitions().map { it.name },
        )
        assertTrue(registry.definitions().filter { it.name !in setOf("list_apps", "phone_script") }.all { it.inputSchema["required"].toString().contains("\"app\"") })
    }

    @Test
    fun getAppStateAsksOnceOpensTheAppAndReturnsTheIndexedTree() {
        val result = run("get_app_state", """{"app":"gmail"}""")
        assertTrue(result.content, result.success)
        assertEquals(listOf("com.google.android.gm"), asked)
        assertEquals(listOf("open com.google.android.gm", "snapshot true"), driver.calls)
        assertTrue(result.content.startsWith("App: Gmail (com.google.android.gm)\nWindow: Inbox\nCoordinates: 576x1280 screenshot pixels"))
        assertTrue(result.content.contains("\t1 text field Search in mail (settable) [40,60,536,110]"))
        assertTrue(result.content.contains("\t\t4 group Alice, Lunch tomorrow?, Secondary Actions: Archive, Long press"))
        assertEquals(gmail, driver.shown)

        // Allowed for this conversation: no second question.
        run("get_app_state", """{"app":"com.google.android.gm"}""")
        assertEquals(1, asked.size)
    }

    @Test
    fun actionsNeedAStateFromThisTurnAndReturnTheFreshState() {
        val early = run("click", """{"app":"Gmail","element_index":2}""")
        assertFalse(early.success)
        assertTrue(early.content.contains("Call get_app_state for Gmail first"))

        run("get_app_state", """{"app":"Gmail"}""")
        val click = run("click", """{"app":"Gmail","element_index":2}""")
        assertTrue(click.content, click.success)
        assertTrue(click.content.startsWith("Clicked Element(index=2) x1 left.\n\nApp: Gmail"))

        // A new turn needs a new look, like Codex's once-per-turn rule.
        session.endTurn()
        session.beginTurn()
        assertFalse(run("click", """{"app":"Gmail","element_index":2}""").success)
    }

    @Test
    fun clicksByPointLongPressAndDoubleTap() {
        run("get_app_state", """{"app":"Gmail"}""")
        assertTrue(run("click", """{"app":"Gmail","x":100,"y":80,"click_count":2}""").success)
        assertTrue(driver.calls.contains("Clicked Point(x=100, y=80) x2 left."))
        assertTrue(run("click", """{"app":"Gmail","element_index":4,"mouse_button":"right"}""").success)
        assertTrue(driver.calls.contains("Clicked Element(index=4) x1 right."))
        val outside = run("click", """{"app":"Gmail","x":600,"y":80}""")
        assertFalse(outside.success)
        assertTrue(outside.content.contains("outside the 576x1280 screenshot"))
        val unknown = run("click", """{"app":"Gmail","element_index":99}""")
        assertFalse(unknown.success)
        assertTrue(unknown.content.contains("isn't in the latest state"))
    }

    @Test
    fun consequentialTapsAreReviewedAndDeclinesDoNothing() {
        run("get_app_state", """{"app":"Gmail"}""")
        reviewAnswer = false
        val declined = run("click", """{"app":"Gmail","element_index":5}""")
        assertFalse(declined.success)
        assertEquals("FRIDAY wants to tap “Send” in Gmail.", reviewed.single())
        assertTrue(driver.calls.none { it.startsWith("Clicked") })

        // Ordinary taps are not reviewed, and a point on the Send button is reviewed like its index.
        assertTrue(run("click", """{"app":"Gmail","element_index":2}""").success)
        reviewAnswer = true
        assertTrue(run("click", """{"app":"Gmail","x":530,"y":60}""").success)
        assertEquals(2, reviewed.size)
    }

    @Test
    fun secondaryActionsMustBeOneTheElementOffers() {
        run("get_app_state", """{"app":"Gmail"}""")
        val wrong = run("perform_secondary_action", """{"app":"Gmail","element_index":4,"action":"Delete"}""")
        assertFalse(wrong.success)
        assertTrue(wrong.content.contains("Archive, Long press"))
        assertTrue(run("perform_secondary_action", """{"app":"Gmail","element_index":4,"action":"archive"}""").success)
        assertTrue(driver.calls.contains("Did Archive on 4."))
    }

    @Test
    fun typingScrollingKeysDraggingAndSettingValues() {
        run("get_app_state", """{"app":"Gmail"}""")
        assertTrue(run("type_text", """{"app":"Gmail","text":"  hello world\n"}""").success)
        assertTrue(driver.calls.contains("type [  hello world\n]"))
        assertTrue(run("scroll", """{"app":"Gmail","element_index":3,"direction":"down","pages":0.5}""").success)
        assertTrue(driver.calls.contains("Scrolled 3 down 0.5."))
        assertTrue(run("scroll", """{"app":"Gmail","direction":"up"}""").success)
        assertTrue(driver.calls.contains("Scrolled null up 1.0."))
        assertTrue(run("press_key", """{"app":"Gmail","key":"Return"}""").success)
        assertTrue(run("press_key", """{"app":"Gmail","key":"ctrl+a"}""").success)
        assertTrue(run("press_key", """{"app":"Gmail","key":"back"}""").success)
        assertEquals(listOf("key ENTER", "key SELECT_ALL", "key BACK"), driver.calls.filter { it.startsWith("key") })
        assertFalse(run("press_key", """{"app":"Gmail","key":"F13"}""").success)
        assertTrue(run("drag", """{"app":"Gmail","from_x":10,"from_y":20,"to_x":30,"to_y":40}""").success)
        assertTrue(run("set_value", """{"app":"Gmail","element_index":1,"value":"invoices"}""").success)
        assertTrue(driver.calls.contains("set 1=invoices"))
    }

    @Test
    fun accessRulesDecideWhichAppsFridayMayUse() {
        answer = AccessDecision.DENY
        val declined = run("get_app_state", """{"app":"Bank"}""")
        assertFalse(declined.success)
        assertTrue(declined.content.contains("declined"))
        assertTrue(driver.calls.isEmpty())

        answer = AccessDecision.ALWAYS_ALLOW
        driver.next = { gmailScreen().copy(app = chrome) }
        assertTrue(run("get_app_state", """{"app":"Chrome"}""").success)
        assertEquals(AppAccess.ALLOW, store.rule(chrome.packageName))

        store.setRule(bank.packageName, AppAccess.DENY)
        val denied = run("get_app_state", """{"app":"Bank"}""")
        assertTrue(denied.content.contains("doesn't allow"))

        val own = run("get_app_state", """{"app":"com.localfirst.assistant"}""")
        assertFalse(own.success)
        assertTrue(own.content.contains("its own app"))
    }

    @Test
    fun anActionThatOpensAnUnallowedAppHidesItsScreen() {
        val shot = File.createTempFile("shot", ".jpg")
        run("get_app_state", """{"app":"Gmail"}""")
        driver.next = { gmailScreen(Attachment("s", AttachmentKind.IMAGE, "s", "image/jpeg", path = shot.path)).copy(app = bank, windowTitle = "Balance") }
        val result = run("click", """{"app":"Gmail","element_index":2}""")
        assertTrue(result.success)
        assertTrue(result.content.contains("Bank (com.example.bank), which FRIDAY hasn't been allowed to use"))
        assertFalse(result.content.contains("Balance"))
        assertTrue(result.images.isEmpty())
        assertFalse(shot.exists())
        assertNull(driver.shown)
    }

    @Test
    fun theKeyboardOrNotificationShadeKeepsTheSession() {
        run("get_app_state", """{"app":"Gmail"}""")
        driver.next = { gmailScreen().copy(app = PhoneApp("System UI", "com.android.systemui")) }
        val result = run("press_key", """{"app":"Gmail","key":"Notifications"}""")
        assertTrue(result.content, result.success)
        assertTrue(result.content.contains("App: Gmail"))
        assertTrue(result.content.contains("Also on screen: System UI is in front"))
        // Still acting in Gmail while the shade is open.
        assertTrue(run("click", """{"app":"Gmail","element_index":2}""").success)
    }

    @Test
    fun anAppThatLeftTheForegroundIsReported() {
        run("get_app_state", """{"app":"Gmail"}""")
        driver.foreground = "com.android.chrome"
        val result = run("click", """{"app":"Gmail","element_index":2}""")
        assertFalse(result.success)
        assertTrue(result.content.contains("no longer in front"))
    }

    @Test
    fun screenshotsTravelWithResultsAndCanBeTurnedOff() {
        val image = Attachment("s", AttachmentKind.IMAGE, "s", "image/jpeg", path = "/tmp/s.jpg")
        driver.next = { gmailScreen(image) }
        assertEquals(listOf(image), run("get_app_state", """{"app":"Gmail"}""").images)
        screenshots = false
        run("get_app_state", """{"app":"Gmail"}""")
        assertEquals("snapshot false", driver.calls.last())
    }

    @Test
    fun listAppsPutsRecentAppsFirst() {
        val text = run("list_apps", "{}").content
        assertTrue(text.startsWith("Recently used:\n- Gmail (com.google.android.gm)\n\nInstalled:\n- Bank (com.example.bank)\n- Chrome"))
    }

    @Test
    fun unavailablePhoneUseExplainsWhatToTurnOn() {
        val off = ToolRegistry().apply { phoneUseTools(driver, { null }).forEach(::register) }
        val result = runBlocking { off.execute(ToolCall("c", "get_app_state", """{"app":"Gmail"}""")) }
        assertFalse(result.success)
        assertTrue(result.content.contains("accessibility service"))
    }

    @Test
    fun keyNamesFollowXdotoolAndAndroid() {
        assertEquals(PhoneKey.ENTER, PhoneKey.parse("Return"))
        assertEquals(PhoneKey.ENTER, PhoneKey.parse("enter"))
        assertEquals(PhoneKey.BACKSPACE, PhoneKey.parse("BackSpace"))
        assertEquals(PhoneKey.PASTE, PhoneKey.parse("CTRL+V"))
        assertEquals(PhoneKey.HOME, PhoneKey.parse("Home"))
        assertEquals(PhoneKey.PAGE_DOWN, PhoneKey.parse("Page_Down"))
        assertNull(PhoneKey.parse("Hyper"))
    }

    @Test
    fun longTreesAreCutWithACount() {
        val many = (1..2000).map { AppElement(it, "text", "Row $it with some words") }
        val text = AppStateText.render(gmailScreen().copy(elements = many), maxChars = 2000)
        assertTrue(text.length <= 2100)
        assertTrue(text.contains("more elements not shown"))
    }

    @Test
    fun consequentialWordsOnlyCountAtTheStartOfAButtonLabel() {
        assertEquals("Send", ConsequentialActions.check(AppElement(1, "button", "Send")))
        assertEquals("Place order", ConsequentialActions.check(AppElement(1, "button", "Place order · $42")))
        assertNull(ConsequentialActions.check(AppElement(1, "button", "Sender settings")))
        assertNull(ConsequentialActions.check(AppElement(1, "text field", "Send a message")))
        assertNull(ConsequentialActions.check(AppElement(1, "button", "Compose")))
    }

    private fun script(code: String) = run("phone_script", kotlinx.serialization.json.buildJsonObject {
        put("title", kotlinx.serialization.json.JsonPrimitive("Test script"))
        put("code", kotlinx.serialization.json.JsonPrimitive(code))
    }.toString())

    @Test
    fun codeModeDrivesThePhoneThroughTheSameTools() {
        val result = script("""
            const s = await cua.getState('Gmail')
            console.log(s.app, s.window, s.elements.length)
            const compose = s.find('Compose')
            await phone.click('Gmail', compose.index)
            await phone.click('Gmail', 'Search in mail')
            await phone.type('Gmail', 'invoices')
            await phone.key('Gmail', 'Return')
            for (const i of [1, 2]) await phone.scroll('Gmail', 'down', {pages: 0.5})
            'done ' + s.elements.filter(e => e.role === 'button').map(e => e.label).join('|')
        """.trimIndent())
        assertTrue(result.content, result.success)
        assertTrue(result.content, result.content.contains("Console:\nGmail Inbox 6"))
        assertTrue(result.content, result.content.contains("Result: done Compose|Send"))
        assertTrue(result.content.contains("App: Gmail"))
        assertEquals(
            listOf("Clicked Element(index=2) x1 left.", "Clicked Element(index=1) x1 left.", "type [invoices]", "key ENTER", "Scrolled null down 0.5.", "Scrolled null down 0.5."),
            driver.calls.filter { !it.startsWith("snapshot") && !it.startsWith("open") },
        )
    }

    @Test
    fun codeModeKeepsAccessRulesAndReportsFailures() {
        answer = AccessDecision.DENY
        val denied = script("await phone.getState('Bank')")
        assertFalse(denied.success)
        assertTrue(denied.content, denied.content.contains("declined"))

        answer = AccessDecision.ALLOW_FOR_CONVERSATION
        val caught = script("""
            await phone.getState('Gmail')
            try { await phone.click('Gmail', 'Nonexistent') } catch (e) { console.log('caught: ' + e.message) }
            reviewed()
        """.trimIndent())
        assertFalse(caught.success)
        assertTrue(caught.content, caught.content.contains("caught: No element labeled"))
        assertTrue(caught.content, caught.content.contains("reviewed is not defined"))

        // Consequential taps still get reviewed inside scripts.
        reviewAnswer = false
        val send = script("await phone.getState('Gmail'); await phone.click('Gmail', 'Send')")
        assertFalse(send.success)
        assertTrue(reviewed.isNotEmpty())

        val syntax = script("await phone.getState('Gmail'")
        assertFalse(syntax.success)
        assertTrue(syntax.content.contains("ends early"))
    }
}
