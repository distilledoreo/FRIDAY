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
class PhoneScriptTool(private val tools: List<Tool>) : Tool {
    override val name = PhoneUseToolNames.SCRIPT
    override val description = "Run JavaScript that uses the phone, for several steps you are sure of (fill a form, work through a list). " +
        "Globals: phone (alias cua) with await phone.listApps(), phone.getState(app), phone.click(app, target, {count, button}), " +
        "phone.type(app, text), phone.key(app, key), phone.scroll(app, direction, {element, pages}), phone.drag(app, from, to), " +
        "phone.setValue(app, element, value), phone.secondary(app, element, action), phone.wait(ms); console.log(...). " +
        "A target is an element index, {x, y}, or a label to find. getState returns {app, window, text, elements, find(label)}; " +
        "actions return the new state. A failed action throws an Error you can catch. The result shows console output, " +
        "the script's last value and the final screen with a screenshot. Prefer the one-step tools when unsure what the screen will show."
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
        var lastState: String? = null
        var lastImages: List<Attachment> = emptyList()
        val job = currentCoroutineContext()[Job]
        val handle = job?.invokeOnCompletion { interpreter.cancelled = true }

        fun call(tool: String, args: JsonObject): Any? {
            val target = byName[tool] ?: throw interpreter.error("Error", "$tool isn't available")
            val result = runBlocking { target.execute(args) }
            if (!result.success) throw interpreter.error("Error", result.content.lineSequence().first().take(500))
            if (result.images.isNotEmpty()) {
                lastImages.forEach { old -> old.path?.let { java.io.File(it).delete() } }
                lastImages = result.images
            }
            val text = result.content
            val stateText = text.substringAfter("\n\n", text).takeIf { it.startsWith("App: ") } ?: text.takeIf { it.startsWith("App: ") }
            if (stateText != null) lastState = stateText
            return stateObject(interpreter, text.substringBefore("\n\n").takeIf { stateText != null && it != stateText }, stateText ?: text)
        }

        fun str(v: Any?) = Values.toStr(Values.normalize(v))
        fun target(app: String, v: Any?): JsonObject = buildJsonObject {
            put("app", JsonPrimitive(app))
            when (val t = Values.normalize(v)) {
                is Double -> put("element_index", JsonPrimitive(t.toInt()))
                is String -> put("element_index", JsonPrimitive(findIndex(interpreter, lastState, t)))
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

        return try {
            val value = withContext(Dispatchers.IO) { interpreter.execProgram(program) }
            val output = buildString {
                if (interpreter.console.isNotEmpty()) append("Console:\n").append(interpreter.console.joinToString("\n").take(6_000)).append("\n\n")
                val shown = Values.normalize(value)
                if (shown !== Undefined && shown !is StateObject) append("Result: ").append(Values.toStr(shown).take(2_000)).append("\n\n")
                append(lastState ?: "The script didn't look at the phone.")
            }
            ToolExecutionResult(true, "Script finished.\n\n" + output.trim(), images = lastImages)
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsThrow) {
            ToolExecutionResult(false, "The script stopped: ${e.message}\n\n" + (interpreter.console.takeIf { it.isNotEmpty() }?.joinToString("\n", "Console:\n", "\n\n").orEmpty()) + (lastState ?: ""), images = lastImages)
        } catch (e: ScriptLimit) {
            ToolExecutionResult(false, "The script stopped: ${e.message}\n\n" + (lastState ?: ""), images = lastImages)
        } finally {
            handle?.dispose()
        }
    }

    /** A state as the script sees it: {app, window, text, summary, elements, find(label)}. */
    private class StateObject : JsObject()

    private fun stateObject(interpreter: Interpreter, summary: String?, text: String): JsObject {
        val state = StateObject()
        val lines = text.lines()
        state.set("text", text)
        summary?.let { state.set("summary", it) }
        lines.firstOrNull { it.startsWith("App: ") }?.removePrefix("App: ")?.let { state.set("app", it.substringBefore(" (")); state.set("package", it.substringAfter("(").removeSuffix(")")) }
        lines.firstOrNull { it.startsWith("Window: ") }?.let { state.set("window", it.removePrefix("Window: ")) }
        val elements = lines.mapNotNull { parseElementLine(interpreter, it) }
        state.set("elements", interpreter.newArray(elements))
        state.set("find", NativeFunction("find", 1) { _, _, a ->
            val wanted = Values.toStr(a.getOrNull(0)).lowercase()
            elements.firstOrNull { (it.get("label") as? String)?.lowercase() == wanted }
                ?: elements.firstOrNull { (it.get("label") as? String)?.lowercase()?.contains(wanted) == true }
                ?: com.localfirst.assistant.genui.script.Null
        })
        return state
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
