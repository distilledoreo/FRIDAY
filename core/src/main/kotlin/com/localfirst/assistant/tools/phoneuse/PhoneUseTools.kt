package com.localfirst.assistant.tools.phoneuse

import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.phone.AppMatcher
import com.localfirst.assistant.tools.phone.Args
import com.localfirst.assistant.tools.phone.InstalledApp
import com.localfirst.assistant.tools.phone.PhoneActionException
import com.localfirst.assistant.tools.phone.objectSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Tool names, for the conversation engine's guards and the transcript. */
object PhoneUseToolNames {
    const val LIST_APPS = "list_apps"
    const val GET_APP_STATE = "get_app_state"
    const val CLICK = "click"
    const val SECONDARY_ACTION = "perform_secondary_action"
    const val SCROLL = "scroll"
    const val DRAG = "drag"
    const val TYPE_TEXT = "type_text"
    const val PRESS_KEY = "press_key"
    const val SET_VALUE = "set_value"

    /** Tools that change what is on screen. */
    val ACTIONS = setOf(CLICK, SECONDARY_ACTION, SCROLL, DRAG, TYPE_TEXT, PRESS_KEY, SET_VALUE)
    /** Every phone-use tool that touches or reads an app's screen. */
    val SCREEN = ACTIONS + GET_APP_STATE
    val ALL = SCREEN + LIST_APPS
}

/**
 * Codex-style phone use. [session] returns the current conversation's phone-use session, or null when
 * phone use is unavailable (for example, the accessibility service is off or the chat is incognito).
 * [screenshots] decides whether states include a screenshot.
 */
fun phoneUseTools(
    driver: PhoneUseDriver,
    session: () -> PhoneUseSession?,
    screenshots: () -> Boolean = { true },
): List<Tool> {
    val context = PhoneUseContext(driver, session, screenshots)
    return listOf(
        ListAppsTool(context),
        GetAppStateTool(context),
        ClickTool(context),
        SecondaryActionTool(context),
        ScrollTool(context),
        DragTool(context),
        TypeTextTool(context),
        PressKeyTool(context),
        SetValueTool(context),
    )
}

internal class PhoneUseContext(
    val driver: PhoneUseDriver,
    private val sessions: () -> PhoneUseSession?,
    val screenshots: () -> Boolean,
) {
    fun session(): PhoneUseSession = sessions()
        ?: throw PhoneActionException("Phone use is unavailable. Ask the user to turn on FRIDAY's accessibility service in Android Settings > Accessibility, then try again.")

    /** Matches a name or package id against the launchable apps (and the foreground app). */
    suspend fun resolve(query: String): PhoneApp {
        val apps = driver.listApps()
        apps.firstOrNull { it.packageName.equals(query, ignoreCase = true) }?.let { return it }
        val foreground = driver.foregroundPackage()
        if (foreground != null && foreground.equals(query, ignoreCase = true)) return PhoneApp(query, foreground, recent = true)
        val match = AppMatcher.best(query, apps.map { InstalledApp(it.label, it.packageName) })
        return apps.first { it.packageName == match.packageName }
    }

    /** Takes a snapshot and records it. Screenshots of apps FRIDAY may not use are never shown. */
    suspend fun state(session: PhoneUseSession, expected: PhoneApp?): Pair<String, ToolExecutionResult?> {
        val taken = driver.snapshot(screenshot = screenshots())
        // The keyboard, notification shade and system dialogs belong to the phone, not another app:
        // the session stays with the app FRIDAY is working in.
        val snapshot = if (expected != null && ProtectedApps.isTransient(taken.app.packageName)) {
            taken.copy(app = expected, otherWindows = listOf("${taken.app.label} is in front") + taken.otherWindows)
        } else taken
        val app = snapshot.app
        if (expected != null && app.packageName != expected.packageName && !ProtectedApps.isTransient(app.packageName)) {
            if (session.effectiveAccess(app) != AppAccess.ALLOW) {
                snapshot.screenshot?.path?.let { java.io.File(it).delete() }
                driver.showActivity(null)
                return "The foreground app is now ${app.label} (${app.packageName}), which FRIDAY hasn't been allowed to use, so its screen isn't shown. " +
                    "Call get_app_state for ${app.label} to ask the user, or go back." to null
            }
        }
        session.record(snapshot)
        driver.showActivity(app)
        val moved = if (expected != null && app.packageName != expected.packageName) "The foreground app is now ${app.label}.\n" else ""
        return moved + AppStateText.render(snapshot) to ToolExecutionResult(true, "", images = listOfNotNull(snapshot.screenshot))
    }
}

/** Shared failure handling: bad arguments and phone errors become results the model can read. */
internal abstract class PhoneUseTool(
    final override val name: String,
    final override val description: String,
    final override val inputSchema: JsonObject,
) : Tool {
    final override suspend fun execute(arguments: JsonObject): ToolExecutionResult = try {
        run(arguments)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        ToolExecutionResult(false, e.message ?: "Invalid arguments for $name.")
    } catch (e: PhoneActionException) {
        ToolExecutionResult(false, e.message ?: "The phone couldn't do that.")
    }

    protected abstract suspend fun run(args: JsonObject): ToolExecutionResult
}

private const val APP_ARG = "The app's name as listed by list_apps, or its package id."

private class ListAppsTool(private val context: PhoneUseContext) : PhoneUseTool(
    name = PhoneUseToolNames.LIST_APPS,
    description = "List the apps on this phone, recently used first. Use these names or package ids with the other phone tools.",
    inputSchema = objectSchema { },
) {
    override suspend fun run(args: JsonObject) = ToolExecutionResult(true, AppStateText.apps(context.driver.listApps()))
}

private class GetAppStateTool(private val context: PhoneUseContext) : PhoneUseTool(
    name = PhoneUseToolNames.GET_APP_STATE,
    description = "Start an app use session if needed, then get the state of the app's current screen: its accessibility tree with element indices, and a screenshot. Opens the app if it isn't in front. This must be called once per turn before interacting with the app.",
    inputSchema = objectSchema { string("app", APP_ARG, required = true) },
) {
    override suspend fun run(args: JsonObject): ToolExecutionResult {
        val session = context.session()
        val app = context.resolve(Args.string(args, "app", maxLength = 200))
        session.authorize(app)?.let { return ToolExecutionResult(false, it) }
        if (context.driver.foregroundPackage() != app.packageName) context.driver.open(app)
        val (text, result) = context.state(session, app)
        return result?.copy(content = text) ?: ToolExecutionResult(false, text)
    }
}

/** An action on the app FRIDAY is looking at; returns what happened followed by the fresh state. */
private abstract class ActionTool(
    name: String,
    description: String,
    inputSchema: JsonObject,
    protected val context: PhoneUseContext,
) : PhoneUseTool(name, description, inputSchema) {
    final override suspend fun run(args: JsonObject): ToolExecutionResult {
        val session = context.session()
        val app = context.resolve(Args.string(args, "app", maxLength = 200))
        session.requireFresh(app)?.let { return ToolExecutionResult(false, it) }
        val foreground = context.driver.foregroundPackage()
        if (foreground != null && foreground != app.packageName && !ProtectedApps.isTransient(foreground)) {
            return ToolExecutionResult(false, "${app.label} is no longer in front (the foreground package is $foreground). Call get_app_state for the app you want to continue in.")
        }
        val snapshot = session.snapshot!!
        val done = act(args, snapshot, session, app) ?: return ToolExecutionResult(false, "The user declined this action. Don't retry it unless they ask; tell them what still needs doing.")
        val (state, result) = context.state(session, app)
        return (result ?: ToolExecutionResult(true, "")).copy(success = true, content = "$done\n\n$state")
    }

    /** Performs the action; null when the user declined it at review. */
    protected abstract suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String?

    protected fun element(args: JsonObject, snapshot: AppSnapshot, name: String = "element_index"): AppElement {
        val index = Args.int(args, name, 0..100_000)
        return snapshot.element(index)
            ?: throw IllegalArgumentException("Element $index isn't in the latest state of ${snapshot.app.label} (it has ${snapshot.elementCount} elements). Use an index from the most recent result.")
    }

    protected fun point(args: JsonObject, snapshot: AppSnapshot, xName: String, yName: String): Pair<Int, Int> {
        val x = Args.int(args, xName, 0..100_000)
        val y = Args.int(args, yName, 0..100_000)
        if (x >= snapshot.width || y >= snapshot.height) {
            throw IllegalArgumentException("($x,$y) is outside the ${snapshot.width}x${snapshot.height} screenshot. Use coordinates from the latest state.")
        }
        return x to y
    }
}

private class ClickTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.CLICK,
    description = "Click an element by index, or pixel coordinates from the screenshot. On a phone a click is a tap; click_count 2 double-taps and mouse_button \"right\" long-presses.",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        integer("element_index", "Index of the element from the latest state. Preferred over coordinates.", min = 0)
        integer("x", "X in screenshot pixels, when there is no suitable element.", min = 0)
        integer("y", "Y in screenshot pixels.", min = 0)
        integer("click_count", "1 (default) or 2 for a double tap.", min = 1, max = 3)
        string("mouse_button", "left (default, a tap) or right (a long press).", enum = MouseButton.entries.map { it.wireName })
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String? {
        val count = Args.optionalInt(args, "click_count", 1..3) ?: 1
        val button = Args.choice(args, "mouse_button", MouseButton.entries.associateBy { it.wireName }, MouseButton.LEFT)
        val hasIndex = args["element_index"] != null && args["element_index"].toString() != "null"
        val (target, element) = if (hasIndex) {
            val element = element(args, snapshot)
            Target.Element(element.index) to element
        } else {
            if (args["x"] == null || args["y"] == null) throw IllegalArgumentException("Give element_index, or both x and y.")
            val (x, y) = point(args, snapshot, "x", "y")
            Target.Point(x, y) to snapshot.hit(x, y)
        }
        if (button == MouseButton.LEFT) {
            ConsequentialActions.check(element)?.let { words ->
                val what = element?.label?.let { "tap “${it.take(80)}”" } ?: "tap “$words”"
                if (!session.review(app, "FRIDAY wants to $what in ${app.label}.")) return null
            }
        }
        return context.driver.click(target, count, button)
    }
}

private class SecondaryActionTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.SECONDARY_ACTION,
    description = "Invoke a secondary accessibility action exposed by an element, by its name as listed under Secondary Actions (for example \"Long press\", \"Expand\", \"Dismiss\", or an app's own action such as \"Archive\").",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        integer("element_index", "Index of the element from the latest state.", min = 0, required = true)
        string("action", "The secondary action's name.", required = true)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String? {
        val element = element(args, snapshot)
        val action = Args.string(args, "action", maxLength = 100)
        val named = element.secondaryActions.firstOrNull { it.equals(action, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                if (element.secondaryActions.isEmpty()) "Element ${element.index} has no secondary actions."
                else "Element ${element.index} has these secondary actions: ${element.secondaryActions.joinToString()}.",
            )
        val consequential = ConsequentialActions.check(element.copy(label = named)) ?: ConsequentialActions.check(element)
        if (consequential != null && !session.review(app, "FRIDAY wants to use “$named” on “${element.label ?: element.role}” in ${app.label}.")) return null
        return context.driver.secondaryAction(element.index, named)
    }
}

private class ScrollTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.SCROLL,
    description = "Scroll an element in a direction by a number of pages. Direction is the way the content moves into view: \"down\" shows what is below. Without element_index the main scrollable area is used.",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        integer("element_index", "A scrollable element from the latest state.", min = 0)
        string("direction", "up, down, left or right.", required = true, enum = ScrollDirection.entries.map { it.wireName })
        number("pages", "How far, in screens. Default 1; fractions like 0.5 are allowed.", min = 0.1, max = 10.0)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String {
        val element = args["element_index"]?.takeIf { it.toString() != "null" }?.let { element(args, snapshot).index }
        Args.string(args, "direction", maxLength = 10)
        val direction = Args.choice(args, "direction", ScrollDirection.entries.associateBy { it.wireName }, ScrollDirection.DOWN)
        val pages = Args.optionalDouble(args, "pages", 0.1, 10.0) ?: 1.0
        return context.driver.scroll(element, direction, pages)
    }
}

private class DragTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.DRAG,
    description = "Drag from one point to another using pixel coordinates from the screenshot, for sliders, reordering and drawing.",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        integer("from_x", "Start x in screenshot pixels.", min = 0, required = true)
        integer("from_y", "Start y in screenshot pixels.", min = 0, required = true)
        integer("to_x", "End x in screenshot pixels.", min = 0, required = true)
        integer("to_y", "End y in screenshot pixels.", min = 0, required = true)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String {
        val (fromX, fromY) = point(args, snapshot, "from_x", "from_y")
        val (toX, toY) = point(args, snapshot, "to_x", "to_y")
        return context.driver.drag(fromX, fromY, toX, toY)
    }
}

private class TypeTextTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.TYPE_TEXT,
    description = "Type literal text into the focused element using keyboard input. Click a text field first to focus it. Passwords are never typed: ask the user to enter them.",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        string("text", "The text to type, exactly.", required = true)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String =
        context.driver.typeText(Args.rawString(args, "text", maxLength = 5_000))
}

private class PressKeyTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.PRESS_KEY,
    description = "Press a key or key combination, using xdotool key names. Examples: \"Return\", \"BackSpace\", \"Tab\", \"Escape\", \"ctrl+a\", \"ctrl+v\", \"Up\", \"Page_Down\". Phone keys: \"Back\", \"Home\", \"Recents\", \"Notifications\", \"Quick_Settings\".",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        string("key", "The key to press.", required = true)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String {
        val raw = Args.string(args, "key", maxLength = 40)
        val key = PhoneKey.parse(raw) ?: throw IllegalArgumentException("Unknown key \"$raw\". Supported: ${PhoneKey.names.joinToString()}.")
        return context.driver.pressKey(key)
    }
}

private class SetValueTool(context: PhoneUseContext) : ActionTool(
    name = PhoneUseToolNames.SET_VALUE,
    description = "Set the value of a settable element: replace a text field's text, move a slider to a number, or set a checkbox or switch to true/false.",
    inputSchema = objectSchema {
        string("app", APP_ARG, required = true)
        integer("element_index", "A settable element from the latest state.", min = 0, required = true)
        string("value", "The new value.", required = true)
    },
    context = context,
) {
    override suspend fun act(args: JsonObject, snapshot: AppSnapshot, session: PhoneUseSession, app: PhoneApp): String {
        val element = element(args, snapshot)
        if ("password" in element.states) throw PhoneActionException("That's a password field. Ask the user to enter it themselves.")
        return context.driver.setValue(element.index, Args.rawString(args, "value", maxLength = 5_000))
    }
}

/** The element a point lands on: the deepest one whose bounds contain it, preferring actionable ones. */
internal fun AppSnapshot.hit(x: Int, y: Int): AppElement? {
    var best: AppElement? = null
    fun visit(list: List<AppElement>, depth: Int, bestDepth: IntArray) {
        for (element in list) {
            val inside = element.bounds?.contains(x, y) ?: true
            if (!inside) continue
            if (element.bounds != null && depth >= bestDepth[0]) { best = element; bestDepth[0] = depth }
            visit(element.children, depth + 1, bestDepth)
        }
    }
    visit(elements, 0, intArrayOf(-1))
    return best
}
