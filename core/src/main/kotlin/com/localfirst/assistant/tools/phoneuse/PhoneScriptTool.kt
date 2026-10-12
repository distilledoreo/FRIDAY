package com.localfirst.assistant.tools.phoneuse

import com.localfirst.assistant.conversation.Attachment
import com.localfirst.assistant.genui.script.Interpreter
import com.localfirst.assistant.genui.script.JsObject
import com.localfirst.assistant.genui.script.JsThrow
import com.localfirst.assistant.genui.script.Limits
import com.localfirst.assistant.genui.script.NativeFunction
import com.localfirst.assistant.genui.script.Parser
import com.localfirst.assistant.genui.script.ScriptLimit
import com.localfirst.assistant.genui.script.ScriptSyntaxError
import com.localfirst.assistant.genui.script.IncompleteInput
import com.localfirst.assistant.genui.script.Undefined
import com.localfirst.assistant.genui.script.Values
import com.localfirst.assistant.json.JsonCodec
import com.localfirst.assistant.tools.Tool
import com.localfirst.assistant.tools.ToolExecutionResult
import com.localfirst.assistant.tools.phone.Args
import com.localfirst.assistant.tools.phone.objectSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Code mode for phone use, like Codex's computer-use REPL (`cua_repl` → `js {title, code}`): the model
 * writes JavaScript that drives the phone through `phone.*` (also `cua.*`), so it can batch steps it is
 * sure of, loop over a list, and check results in code. Every call goes through the same tools, access
 * rules and reviews as the one-step tools. The script runs in FRIDAY's sandboxed interpreter: no
 * network, files or timers beyond `phone.wait`.
 */
class PhoneScriptTool(
    private val tools: List<Tool>,
    /** The session's latest snapshot, so scripts see elements exactly as recorded rather than re-parsed. */
    private val latest: () -> AppSnapshot? = { null },
) : Tool {
    override val name = PhoneUseToolNames.SCRIPT
    override val description = "Run JavaScript that uses the phone, for several steps you are sure of (fill a form, work through a list). " +
        "Globals: phone (alias cua) with await phone.listApps(), phone.getState(app), phone.click(app, target, {count, button}), " +
        "phone.type(app, text), phone.key(app, key), phone.scroll(app, direction, {element, pages}), phone.drag(app, from, to), " +
        "phone.setValue(app, element, value), phone.secondary(app, element, action), phone.wait(ms); console.log(...). " +
        "A target is an element index, {x, y}, or a label to find. getState returns {app, window, text, announcements, elements, find(label), " +
        "findAll(label)}; each element is {index, role, label, value, states, enabled, focused, selected, checked, actions, bounds: [l,t,r,b], " +
        "x, y, depth, parent}. Actions return the new state, with changed (false when the app looks the same as before) and outcome. " +
        "A failed action throws an Error you can catch. The result lists every action the script took, console output, the script's " +
        "last value and the final screen with a screenshot. Prefer the one-step tools when unsure what the screen will show."
    override val inputSchema: JsonObject = objectSchema {
        string("title", "A short description of what the script does, shown to the user.", required = true)
        string("code", "JavaScript to run. Top-level await works.", required = true)
    }

    private val byName = tools.associateBy { it.name }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val code = try { Args.rawString(arguments, "code", maxLength = 20_000) } catch (e: IllegalArgumentException) { return ToolExecutionResult(false, e.message ?: "Missing code.") }
        Args.optionalString(arguments, "title", 200)
        val program = try {
            Parser(code).parseProgram()
        } catch (e: ScriptSyntaxError) {
            return ToolExecutionResult(false, "Syntax error at ${e.at}: ${e.message}")
        } catch (e: IncompleteInput) {
            return ToolExecutionResult(false, "The script ends early: ${e.message}")
        }
        val interpreter = Interpreter(Limits(maxSteps = 5_000_000, maxMillis = SCRIPT_MILLIS, maxCallDepth = 120))
        var lastSnapshot: AppSnapshot? = latest()
        var lastState: String? = lastSnapshot?.let(AppStateText::render)
        var lastImages: List<Attachment> = emptyList()
        val actions = mutableListOf<String>()
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { interpreter.cancelled = true }

        fun call(tool: String, args: JsonObject): Any? {
            val target = byName[tool] ?: throw interpreter.error("Error", "$tool isn't available")
            val result = runBlocking { target.execute(args) }
            val text = result.content
            val stateText = text.substring(text.indexOf("App: ").coerceAtLeast(0)).takeIf { it.startsWith("App: ") }
            if (stateText != null) {
                val keptPaths = result.images.mapNotNull { it.path }.toSet()
                lastImages.forEach { old -> old.path?.takeIf { it !in keptPaths }?.let { java.io.File(it).delete() } }
                lastImages = result.images
                lastState = stateText
                lastSnapshot = latest()
            }
            val outcome = text.substringBefore("\n\n").takeIf { stateText != null && !it.startsWith("App: ") } ?: text.takeIf { stateText == null }
            if (tool != PhoneUseToolNames.GET_APP_STATE) actions += (if (result.success) "" else "Failed: ") + (outcome ?: tool).lineSequence().first().take(300)
            if (!result.success) throw interpreter.error("Error", text.lineSequence().first().take(500))
            return stateObject(interpreter, outcome?.takeIf { tool != PhoneUseToolNames.GET_APP_STATE }, stateText ?: text, lastSnapshot.takeIf { stateText != null })
        }

        fun str(v: Any?) = Values.toStr(Values.normalize(v))
        fun target(app: String, v: Any?): JsonObject = buildJsonObject {
            put("app", JsonPrimitive(app))
            when (val t = Values.normalize(v)) {
                is Double -> put("element_index", JsonPrimitive(t.toInt()))
                is String -> put("element_index", JsonPrimitive(lastSnapshot?.let { s -> findElement(s, t)?.index } ?: findIndex(interpreter, lastState, t)))
                is JsObject -> {
                    val index = t.get("index")
                    if (index is Double) put("element_index", JsonPrimitive(index.toInt()))
                    else { put("x", JsonPrimitive(Values.toNumber(t.get("x")).toInt())); put("y", JsonPrimitive(Values.toNumber(t.get("y")).toInt())) }
                }
                else -> throw interpreter.typeError("A target is an element index, {x, y} or a label")
            }
        }
        fun native(name: String, body: (List<Any?>) -> Any?) = NativeFunction(name) { _, _, a -> body(a) }
        val phone = JsObject()
        phone.set("listApps", native("listApps") { _ ->
            val text = byName[PhoneUseToolNames.LIST_APPS]?.let { runBlocking { it.execute(JsonObject(emptyMap())) } }?.content.orEmpty()
            interpreter.newArray(Regex("- (.+) \\(([^)]+)\\)").findAll(text).map { m ->
                interpreter.newObject().apply { set("name", m.groupValues[1]); set("package", m.groupValues[2]) }
            }.toList())
        })
        phone.set("getState", native("getState") { a -> call(PhoneUseToolNames.GET_APP_STATE, buildJsonObject { put("app", JsonPrimitive(str(a.getOrNull(0)))) }) })
        phone.set("click", native("click") { a ->
            val app = str(a.getOrNull(0))
            val options = Values.normalize(a.getOrNull(2)) as? JsObject
            val args = target(app, a.getOrNull(1)).toMutableMap()
            options?.get("count")?.takeIf { it is Double }?.let { args["click_count"] = JsonPrimitive((it as Double).toInt()) }
            options?.get("button")?.takeIf { it is String }?.let { args["mouse_button"] = JsonPrimitive(it as String) }
            call(PhoneUseToolNames.CLICK, JsonObject(args))
        })
        phone.set("type", native("type") { a -> call(PhoneUseToolNames.TYPE_TEXT, buildJsonObject { put("app", JsonPrimitive(str(a.getOrNull(0)))); put("text", JsonPrimitive(str(a.getOrNull(1)))) }) })
        phone.set("key", native("key") { a -> call(PhoneUseToolNames.PRESS_KEY, buildJsonObject { put("app", JsonPrimitive(str(a.getOrNull(0)))); put("key", JsonPrimitive(str(a.getOrNull(1)))) }) })
        phone.set("scroll", native("scroll") { a ->
            val app = str(a.getOrNull(0))
            val options = Values.normalize(a.getOrNull(2)) as? JsObject
            call(PhoneUseToolNames.SCROLL, buildJsonObject {
                put("app", JsonPrimitive(app)); put("direction", JsonPrimitive(str(a.getOrNull(1))))
                options?.get("pages")?.takeIf { it is Double }?.let { put("pages", JsonPrimitive(it as Double)) }
                options?.get("element")?.takeIf { it !== Undefined }?.let { (target(app, it)["element_index"])?.let { idx -> put("element_index", idx) } }
            })
        })
        phone.set("drag", native("drag") { a ->
            val from = Values.normalize(a.getOrNull(1)) as? JsObject ?: throw interpreter.typeError("drag needs {x, y} points")
            val to = Values.normalize(a.getOrNull(2)) as? JsObject ?: throw interpreter.typeError("drag needs {x, y} points")
            call(PhoneUseToolNames.DRAG, buildJsonObject {
                put("app", JsonPrimitive(str(a.getOrNull(0))))
                put("from_x", JsonPrimitive(Values.toNumber(from.get("x")).toInt())); put("from_y", JsonPrimitive(Values.toNumber(from.get("y")).toInt()))
                put("to_x", JsonPrimitive(Values.toNumber(to.get("x")).toInt())); put("to_y", JsonPrimitive(Values.toNumber(to.get("y")).toInt()))
            })
        })
        phone.set("setValue", native("setValue") { a ->
            val app = str(a.getOrNull(0))
            call(PhoneUseToolNames.SET_VALUE, JsonObject(target(app, a.getOrNull(1)) + ("value" to JsonPrimitive(str(a.getOrNull(2))))))
        })
        phone.set("secondary", native("secondary") { a ->
            val app = str(a.getOrNull(0))
            call(PhoneUseToolNames.SECONDARY_ACTION, JsonObject(target(app, a.getOrNull(1)) + ("action" to JsonPrimitive(str(a.getOrNull(2))))))
        })
        phone.set("wait", native("wait") { a -> Thread.sleep(Values.toNumber(a.getOrNull(0)).toLong().coerceIn(0, 5_000)); Undefined })
        phone.frozen = true
        interpreter.globals.declare("phone", phone, constant = true)
        interpreter.globals.declare("cua", phone, constant = true)

        fun taken() = if (actions.isEmpty()) "" else actions.joinToString("\n", "Actions taken:\n", "\n\n") { "- $it" }.take(4_000)
        return try {
            val value = withContext(Dispatchers.IO) { interpreter.execProgram(program) }
            val output = buildString {
                append(taken())
                if (interpreter.console.isNotEmpty()) append("Console:\n").append(interpreter.console.joinToString("\n").take(6_000)).append("\n\n")
                val shown = Values.normalize(value)
                if (shown !== Undefined && shown !is StateObject) append("Result: ").append(Values.toStr(shown).take(2_000)).append("\n\n")
                append(lastState ?: "The script didn't look at the phone.")
            }
            ToolExecutionResult(true, "Script finished.\n\n" + output.trim(), images = lastImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsThrow) {
            ToolExecutionResult(false, "The script stopped: ${e.message}\n\n" + taken() + (interpreter.console.takeIf { it.isNotEmpty() }?.joinToString("\n", "Console:\n", "\n\n").orEmpty()) + (lastState ?: ""), images = lastImages)
        } catch (e: ScriptLimit) {
            ToolExecutionResult(false, "The script stopped: ${e.message}\n\n" + taken() + (lastState ?: ""), images = lastImages)
        } finally {
            handle?.dispose()
        }
    }

    /** A state as the script sees it: {app, window, text, outcome, changed, announcements, elements, find(label), findAll(label)}. */
    private class StateObject : JsObject()

    private fun stateObject(interpreter: Interpreter, outcome: String?, text: String, snapshot: AppSnapshot?): JsObject {
        val state = StateObject()
        val lines = text.lines()
        state.set("text", text)
        outcome?.let {
            state.set("outcome", it)
            state.set("summary", it)
            state.set("changed", !it.contains("looks the same as before"))
        }
        lines.firstOrNull { it.startsWith("App: ") }?.removePrefix("App: ")?.let { state.set("app", it.substringBefore(" (")); state.set("package", it.substringAfter("(").removeSuffix(")")) }
        lines.firstOrNull { it.startsWith("Window: ") }?.let { state.set("window", it.removePrefix("Window: ")) }
        state.set("announcements", interpreter.newArray(snapshot?.announcements.orEmpty()))
        val elements = snapshot?.let { elementObjects(interpreter, it) } ?: lines.mapNotNull { parseElementLine(interpreter, it) }
        state.set("elements", interpreter.newArray(elements))
        fun matches(wanted: String): List<JsObject> {
            val w = wanted.lowercase()
            val exact = elements.filter { (it.get("label") as? String)?.lowercase() == w }
            return exact + elements.filter { it !in exact && (it.get("label") as? String)?.lowercase()?.contains(w) == true }
        }
        state.set("find", NativeFunction("find", 1) { _, _, a -> matches(Values.toStr(a.getOrNull(0))).firstOrNull() ?: com.localfirst.assistant.genui.script.Null })
        state.set("findAll", NativeFunction("findAll", 1) { _, _, a -> interpreter.newArray(matches(Values.toStr(a.getOrNull(0)))) })
        return state
    }

    /** Elements in reading order, with everything the text form shows. */
    private fun elementObjects(interpreter: Interpreter, snapshot: AppSnapshot): List<JsObject> {
        val out = mutableListOf<JsObject>()
        fun visit(list: List<AppElement>, depth: Int, parent: Int?) {
            for (e in list) {
                val obj = interpreter.newObject()
                obj.set("index", e.index.toDouble())
                obj.set("role", e.role)
                obj.set("label", e.label ?: "")
                obj.set("value", e.value ?: com.localfirst.assistant.genui.script.Null)
                obj.set("states", interpreter.newArray(e.states))
                obj.set("enabled", "disabled" !in e.states)
                obj.set("focused", "focused" in e.states)
                obj.set("selected", "selected" in e.states)
                obj.set("checked", if ("checked" in e.states) true else if ("unchecked" in e.states) false else com.localfirst.assistant.genui.script.Null)
                obj.set("actions", interpreter.newArray(e.secondaryActions + snapshot.commonActions))
                e.bounds?.let { b ->
                    obj.set("bounds", interpreter.newArray(listOf(b.left.toDouble(), b.top.toDouble(), b.right.toDouble(), b.bottom.toDouble())))
                    obj.set("x", b.centerX.toDouble()); obj.set("y", b.centerY.toDouble())
                    obj.set("width", b.width.toDouble()); obj.set("height", b.height.toDouble())
                }
                obj.set("depth", depth.toDouble())
                obj.set("parent", parent?.toDouble() ?: com.localfirst.assistant.genui.script.Null)
                out += obj
                visit(e.children, depth + 1, e.index)
            }
        }
        visit(snapshot.elements, 0, null)
        return out
    }

    private fun findElement(snapshot: AppSnapshot, label: String): AppElement? {
        val wanted = label.lowercase()
        val all = mutableListOf<AppElement>()
        fun collect(list: List<AppElement>) { list.forEach { all += it; collect(it.children) } }
        collect(snapshot.elements)
        return all.firstOrNull { it.label?.lowercase() == wanted } ?: all.firstOrNull { it.label?.lowercase()?.contains(wanted) == true }
    }

    private val elementLine = Regex("^\\t*(\\d+) (.+?)(?: \\[(\\d+),(\\d+),(\\d+),(\\d+)])?$")

    private fun parseElementLine(interpreter: Interpreter, line: String): JsObject? {
        val m = elementLine.find(line) ?: return null
        val obj = interpreter.newObject()
        obj.set("index", m.groupValues[1].toDouble())
        val rest = m.groupValues[2]
        val role = KNOWN_ROLES.firstOrNull { rest == it || rest.startsWith("$it ") } ?: rest.substringBefore(' ')
        obj.set("role", role)
        val label = rest.removePrefix(role).trim().substringBefore(", Value: ").substringBefore(", Secondary Actions: ").substringBefore(" (").trim()
        if (label.isNotEmpty()) obj.set("label", label)
        if (m.groupValues[3].isNotEmpty()) {
            val (l, t, r, b) = (3..6).map { m.groupValues[it].toDouble() }
            obj.set("bounds", interpreter.newArray(listOf(l, t, r, b)))
            obj.set("x", (l + r) / 2); obj.set("y", (t + b) / 2)
        }
        return obj
    }

    private fun findIndex(interpreter: Interpreter, state: String?, label: String): Int {
        val wanted = label.lowercase()
        val lines = state?.lines().orEmpty().mapNotNull { elementLine.find(it) }
        val hit = lines.firstOrNull { it.groupValues[2].lowercase().let { r -> r.endsWith(" $wanted") || r.contains(" $wanted,") || r.contains(" $wanted (") || r.contains(" $wanted [") } }
            ?: lines.firstOrNull { it.groupValues[2].lowercase().contains(wanted) }
            ?: throw interpreter.error("Error", "No element labeled “$label” in the latest state. Call phone.getState(app) first, or use an index.")
        return hit.groupValues[1].toInt()
    }

    companion object {
        const val SCRIPT_MILLIS = 180_000L
        private val KNOWN_ROLES = listOf("text field", "image button", "radio button", "toggle button", "pop up button", "progress indicator", "scroll view", "web view", "tab group", "menu item", "list item")
            .sortedByDescending { it.length }
    }
}
