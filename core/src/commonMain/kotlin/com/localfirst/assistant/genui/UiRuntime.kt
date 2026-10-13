package com.localfirst.assistant.genui

import com.localfirst.assistant.platform.platformRegex

import com.localfirst.assistant.genui.script.Attribute
import com.localfirst.assistant.platform.putIfAbsent
import com.localfirst.assistant.genui.script.BodyNode
import com.localfirst.assistant.genui.script.EachNode
import com.localfirst.assistant.genui.script.ElementNode
import com.localfirst.assistant.genui.script.ExprNode
import com.localfirst.assistant.genui.script.FragmentNode
import com.localfirst.assistant.genui.script.IfNode
import com.localfirst.assistant.genui.script.Interpreter
import com.localfirst.assistant.genui.script.JsArray
import com.localfirst.assistant.genui.script.JsFunction
import com.localfirst.assistant.genui.script.JsObject
import com.localfirst.assistant.genui.script.JsThrow
import com.localfirst.assistant.genui.script.Limits
import com.localfirst.assistant.genui.script.MarkupNode
import com.localfirst.assistant.genui.script.NamedAttribute
import com.localfirst.assistant.genui.script.NativeFunction
import com.localfirst.assistant.genui.script.Null
import com.localfirst.assistant.genui.script.RawNode
import com.localfirst.assistant.genui.script.Scope
import com.localfirst.assistant.genui.script.ScriptLimit
import com.localfirst.assistant.genui.script.SpreadAttribute
import com.localfirst.assistant.genui.script.TextNode
import com.localfirst.assistant.genui.script.Undefined
import com.localfirst.assistant.genui.script.Values

/** What the renderer draws: catalog components and text, with stable keys. */
sealed interface UiNode { val key: String }

/** A catalog component. Function props are [UiCallback]s the renderer passes back to [UiRuntime.dispatch]. */
data class UiElement(
    val type: String,
    override val key: String,
    val props: Map<String, Any?>,
    val children: List<UiNode>,
) : UiNode {
    fun string(name: String): String? = props[name]?.let { if (it is String) it else if (it is Number || it is Boolean) Values.toStr(Values.normalize(it)) else null }
    fun number(name: String): Double? = when (val v = props[name]) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    fun bool(name: String): Boolean? = when (val v = props[name]) { is Boolean -> v; is String -> v == "true"; is Number -> v.toDouble() != 0.0; else -> null }
    fun callback(name: String): UiCallback? = props[name] as? UiCallback
}

/** Markdown (block context) or plain text with inline Markdown (inside text components). */
data class UiText(override val key: String, val text: String) : UiNode

/** Raw HTML for the AppBlock sandbox. */
data class UiRaw(override val key: String, val props: Map<String, Any?>, val source: String, val complete: Boolean) : UiNode

data class UiCallback(val id: String)

/** Something an interaction asks the app to do. Only user-initiated dispatches produce them. */
sealed interface UiAction {
    data class NewTurn(val text: String) : UiAction
    data class Copy(val text: String) : UiAction
    data class OpenUrl(val url: String) : UiAction
    data class OpenEntity(val ref: Any?) : UiAction
    data class Share(val text: String) : UiAction
    data class Toast(val text: String) : UiAction
}

data class RenderResult(val nodes: List<UiNode>, val diagnostics: List<UiDiagnostic>, val actions: List<UiAction> = emptyList())

/** Markup produced by an expression (`items.map(i => <chip/>)`), carried as a script value. */
private class MarkupValue(val nodes: List<Pending>) : JsObject(className = "Markup")

/** A node before final keys and callbacks are assigned. */
private sealed interface Pending
private class PendingElement(val type: String, val key: String?, val props: Map<String, Any?>, val children: List<Pending>) : Pending
private class PendingText(val text: String) : Pending
private class PendingRaw(val props: Map<String, Any?>, val source: String, val complete: Boolean) : Pending

/**
 * Runs a compiled response like ChatGPT's sandboxed worker: hook state in keyed slots, a render into a
 * node tree, and callbacks that update state and re-render with no model call. Model code runs in the
 * [Interpreter] sandbox under step, time and memory budgets; an expression that throws removes only
 * its own element. State survives recompiles (streaming) through the keys the compiler adds, and can
 * be saved and restored across app restarts.
 *
 * Not thread-safe: call it from one thread.
 */
class UiRuntime(
    private val catalog: Catalog = Catalog.default,
    /** Tool results the model may reference by id, e.g. `{web1_0.title}`. */
    bindings: Map<String, Any?> = emptyMap(),
    /** Saved state from an earlier session ([snapshotState]). */
    savedState: Map<String, Any?> = emptyMap(),
    limits: Limits = Limits(maxSteps = 1_000_000, maxMillis = 1_200),
) {
    private val interp = Interpreter(limits)
    private val slots = LinkedHashMap<String, Any?>()
    private val initial = LinkedHashMap<String, Any?>()
    private val setters = HashMap<String, JsFunction>()
    private val callbacks = HashMap<String, JsFunction>()
    private val diagnostics = LinkedHashMap<String, UiDiagnostic>()
    private val actions = mutableListOf<UiAction>()
    private var program: CompiledUi? = null
    private var rendering = false
    private var dirty = false
    private var lastGood: List<UiNode> = emptyList()
    private var failures = 0

    // The hook context of whatever is rendering: the response itself, or a function component.
    private var hookPath = "root"
    private var hookIndex = 0

    init {
        savedState.forEach { (k, v) -> slots[k] = Values.fromKotlin(v) }
        interp.markup = { node, scope -> MarkupValue(evalNodes(listOf(node), scope, "expr")) }
        installHooks()
        installActions()
        bindings.forEach { (name, value) -> interp.globals.declare(name, Values.fromKotlin(value), constant = true) }
        interp.globals.declare("appData", Values.fromKotlin(bindings), constant = true)
    }

    /** Whether the response has stopped running model code after repeated budget failures. */
    val quarantined: Boolean get() = failures >= MAX_FAILURES

    // ---- the public surface -------------------------------------------------------------------------

    /** Loads a newer compile of the response (a streamed update) and renders it with the existing state. */
    fun load(compiled: CompiledUi): RenderResult {
        program = compiled
        compiled.diagnostics.forEach { report(it) }
        return render()
    }

    fun render(): RenderResult {
        val compiled = program ?: return RenderResult(emptyList(), emptyList())
        if (quarantined) return RenderResult(lastGood, diagnostics.values.toList())
        var nodes: List<UiNode>? = null
        for (attempt in 0 until 3) {
            dirty = false
            nodes = renderOnce(compiled) ?: return RenderResult(lastGood, diagnostics.values.toList())
            // A state change during render (setX at top level) renders once more, at most twice.
            if (!dirty) break
            if (attempt == 2) report(UiDiagnostic("render_loop", "State kept changing while rendering; stopped after 3 passes"))
        }
        lastGood = nodes ?: lastGood
        return RenderResult(lastGood, diagnostics.values.toList())
    }

    /** Runs a callback for a user interaction (tap, slider move, form submit), then re-renders. */
    fun dispatch(callback: UiCallback, args: List<Any?>): RenderResult {
        val fn = callbacks[callback.id] ?: return RenderResult(lastGood, diagnostics.values.toList())
        actions.clear()
        try {
            interp.run { interp.callFunction(fn, Undefined, args.map(Values::fromKotlin)) }
        } catch (e: JsThrow) {
            report(UiDiagnostic("runtime_error", "${e.message} (in an event handler)"))
        } catch (e: ScriptLimit) {
            failures++
            report(UiDiagnostic("watchdog", "${e.message} (in an event handler)", Severity.ERROR))
        }
        val done = actions.toList()
        actions.clear()
        return render().copy(actions = done)
    }

    /** Hook state as plain data, for saving with the conversation. Functions are left out. */
    fun snapshotState(): Map<String, Any?> = slots.mapValues { (_, v) -> Values.toKotlin(v) }

    /** The response's top-level state that the user changed, for telling the model ("seats = 9"). */
    fun changedState(): Map<String, Any?> = slots.filterKeys { it.startsWith("root:") && !it.startsWith("root:#") }
        .filter { (k, v) -> !initial.containsKey(k) || Values.toKotlin(initial[k]) != Values.toKotlin(v) }
        .mapKeys { it.key.removePrefix("root:") }
        .mapValues { Values.toKotlin(it.value) }

    fun diagnostics(): List<UiDiagnostic> = diagnostics.values.toList()

    // ---- rendering ---------------------------------------------------------------------------------------

    private fun renderOnce(compiled: CompiledUi): List<UiNode>? {
        rendering = true
        hookPath = "root"
        hookIndex = 0
        callbacks.clear()
        try {
            return interp.run {
                val scope = Scope(interp.globals, functionScope = true)
                // {@body} statements run first, in order, like the top of a component function.
                compiled.nodes.filterIsInstance<BodyNode>().forEach { body -> runBody(body, scope) }
                val pending = evalNodes(compiled.nodes.filter { it !is BodyNode }, scope, "")
                finalize(pending, "", inline = false)
            }
        } catch (e: ScriptLimit) {
            failures++
            report(UiDiagnostic("watchdog", e.message ?: "The interface ran too long", Severity.ERROR))
            return null
        } finally {
            rendering = false
        }
    }

    private fun runBody(body: BodyNode, scope: Scope) {
        interp.hoist(body.statements, scope)
        for (stmt in body.statements) {
            try {
                interp.execProgram(listOf(stmt), scope)
            } catch (e: JsThrow) {
                report(UiDiagnostic("runtime_error", e.message, at = stmt.pos))
            }
        }
    }

    private fun evalNodes(nodes: List<MarkupNode>, scope: Scope, path: String): List<Pending> {
        val out = mutableListOf<Pending>()
        nodes.forEachIndexed { i, node ->
            val here = "$path/$i"
            try {
                when (node) {
                    is TextNode -> out += PendingText(node.staticText)
                    is ExprNode -> out += toPending(interp.eval(node.expr, scope))
                    is ElementNode -> out += evalElement(node, scope, here)
                    is FragmentNode -> out += evalNodes(node.children, scope, here)
                    is BodyNode -> runBody(node, scope)
                    is IfNode -> {
                        val branch = node.branches.firstOrNull { b -> b.test == null || Values.truthy(interp.eval(b.test, scope)) }
                        if (branch != null) out += evalNodes(branch.children, Scope(scope), "$here?${node.branches.indexOf(branch)}")
                    }
                    is EachNode -> out += evalEach(node, scope, here)
                    is RawNode -> out += PendingRaw(evalAttributes(node.attributes, scope, node.tag), node.raw, node.complete)
                }
            } catch (e: JsThrow) {
                // Like __dilSafe: a failing expression removes its own element, not the response.
                report(UiDiagnostic("runtime_error", e.message, at = node.pos))
            }
        }
        return out
    }

    private fun evalEach(node: EachNode, scope: Scope, path: String): List<Pending> {
        val items = interp.iterate(interp.eval(node.list, scope))
        if (items.isEmpty()) return evalNodes(node.otherwise, Scope(scope), "$path:else")
        val out = mutableListOf<Pending>()
        items.forEachIndexed { index, item ->
            val itemScope = Scope(scope)
            interp.bind(node.item, item, itemScope, declare = "const", constant = true)
            node.index?.let { itemScope.declare(it, index.toDouble(), constant = true) }
            val key = node.key?.let { Values.toStr(interp.eval(it, itemScope)) } ?: index.toString()
            val children = evalNodes(node.children, itemScope, "$path[$key]")
            // A fragment keeps each iteration's nodes together under the item's key.
            out += PendingElement("#fragment", key, emptyMap(), children)
        }
        return out
    }

    private fun toPending(value: Any?): List<Pending> = when (val v = Values.normalize(value)) {
        is MarkupValue -> v.nodes
        is JsArray -> v.items.flatMap(::toPending)
        is String -> listOf(PendingText(v))
        is Double -> listOf(PendingText(Values.toStr(v)))
        Undefined, Null, is Boolean -> emptyList()
        is JsFunction -> emptyList()
        is JsObject -> {
            report(UiDiagnostic("object_as_child", "An object can't be shown directly; pick a field such as .name"))
            emptyList()
        }
        else -> listOf(PendingText(Values.toStr(v)))
    }

    private fun evalAttributes(attributes: List<Attribute>, scope: Scope, tag: String): Map<String, Any?> {
        val props = LinkedHashMap<String, Any?>()
        for (attribute in attributes) when (attribute) {
            is NamedAttribute -> props[attribute.name] = interp.eval(attribute.value, scope)
            is SpreadAttribute -> {
                val value = interp.eval(attribute.value, scope)
                if (value is JsObject) value.keys().forEach { props[it] = value.get(it) }
            }
        }
        return props
    }

    private fun evalElement(node: ElementNode, scope: Scope, path: String): List<Pending> {
        val tag = node.tag
        // A function component the response defined (`function Row({label}) { return <row>… }`).
        val local = scope.lookup(tag)?.value as? JsFunction
        if (local != null && tag.first().isUpperCase()) return callComponent(local, node, scope, path)

        val resolved = catalog.resolve(tag)
        if (resolved == null) {
            report(UiDiagnostic("unknown_component", "<$tag> isn't a FRIDAY component; its content is shown without it", at = node.pos))
            return evalNodes(node.children, scope, path)
        }
        val (spec, fixed) = resolved
        val raw = LinkedHashMap<String, Any?>(fixed)
        raw += evalAttributes(node.attributes, scope, tag)
        val props = validate(spec, raw, node.pos)
        val children = evalNodes(node.children, Scope(scope), path)
        return listOf(PendingElement(spec.name, raw["key"]?.let { Values.toStr(Values.normalize(it)) }, props, children))
    }

    private fun callComponent(fn: JsFunction, node: ElementNode, scope: Scope, path: String): List<Pending> {
        val props = interp.newObject()
        evalAttributes(node.attributes, scope, node.tag).forEach { (k, v) -> props.set(k, v) }
        if (node.children.isNotEmpty()) props.set("children", MarkupValue(evalNodes(node.children, Scope(scope), "$path:children")))
        val savedPath = hookPath
        val savedIndex = hookIndex
        hookPath = "$path<${node.tag}>"
        hookIndex = 0
        try {
            return toPending(interp.callFunction(fn, Undefined, listOf(props)))
        } finally {
            hookPath = savedPath
            hookIndex = savedIndex
        }
    }

    // ---- props --------------------------------------------------------------------------------------------

    private val eventAliases = mapOf("onclick" to "onClick", "onchange" to "onChange", "onsubmit" to "onSubmit", "ontoggle" to "onToggle", "onPress" to "onClick", "onTap" to "onClick", "onValueChange" to "onChange", "onInput" to "onChange")

    /** Keeps the props the catalog knows, coerced to their types; reports and drops the rest. */
    private fun validate(spec: ComponentSpec, raw: Map<String, Any?>, at: Int): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((rawName, rawValue) in raw) {
            val name = eventAliases[rawName] ?: rawName
            val value = Values.normalize(rawValue)
            if (name == "style") { styleProps(value).forEach { (k, v) -> if (k in spec.props && k !in raw) out[k] = v }; continue }
            if (name == "className" || name == "class" || name == "key" || name == "id" || name == "children" || name.startsWith("aria")) continue
            val prop = spec.props[name] ?: catalog.commonProps[name]
            if (prop == null) {
                report(UiDiagnostic("unknown_prop", "<${spec.name}> has no property “$name”", at = at))
                continue
            }
            val coerced = coerce(prop, value)
            if (coerced === Invalid) {
                report(UiDiagnostic("invalid_value", "<${spec.name} $name> can't be ${Values.typeOf(value)} ${preview(value)}", at = at))
                continue
            }
            out[name] = coerced
        }
        return out
    }

    private fun preview(v: Any?): String = Values.toStr(v).take(30).let { if (v is String) "“$it”" else it }

    private object Invalid

    private fun coerce(prop: PropSpec, v: Any?): Any? {
        if (v === Undefined || v === Null) return Undefined
        return when (prop.type) {
            PropType.STRING, PropType.ICON -> when (v) { is String -> v; is Double, is Boolean -> Values.toStr(v); else -> Invalid }
            PropType.URL -> (v as? String)?.takeIf { safeUrl(it) } ?: Invalid
            PropType.NUMBER -> when (v) {
                is Double -> v.takeIf { it.isFinite() } ?: Invalid
                is String -> Values.parseNumber(v.removeSuffix("%").replace(",", "")).takeIf { it.isFinite() } ?: Invalid
                is Boolean -> if (v) 1.0 else 0.0
                else -> Invalid
            }
            PropType.BOOLEAN -> when (v) { is Boolean -> v; is String -> v != "false" && v != "0"; is Double -> v != 0.0; else -> Values.truthy(v) }
            PropType.ENUM -> Values.toStr(v).let { s -> prop.values.firstOrNull { it.equals(s, ignoreCase = true) } ?: Invalid }
            PropType.FUNCTION -> v as? JsFunction ?: Invalid
            PropType.ARRAY -> v as? JsArray ?: Invalid
            PropType.OBJECT -> (v as? JsObject)?.takeIf { it !is JsFunction } ?: Invalid
            PropType.SPACING -> when (v) {
                is Double -> v.takeIf { it in 0.0..64.0 } ?: Invalid
                is String -> Values.parseNumber(v).takeIf { it.isFinite() && it in 0.0..64.0 } ?: cssLength(v)?.let { "${it}px" } ?: Invalid
                else -> Invalid
            }
            PropType.RADIUS -> when (v) { is Double -> v; is String -> if (v in DesignTokens.radius) v else cssLength(v)?.let { "${it}px" } ?: Invalid; else -> Invalid }
            PropType.SIZE -> when (v) { is Double -> v; is String -> if (v in DesignTokens.sizes || v == "full" || v == "auto") v else cssLength(v)?.let { "${it}px" } ?: (if (v.endsWith("%")) v else Invalid); else -> Invalid }
            PropType.COLOR -> (v as? String)?.takeIf { it in DesignTokens.colors || it.matches(platformRegex("#[0-9a-fA-F]{3,8}")) || it.startsWith("rgb") || it.startsWith("var(") } ?: Invalid
            PropType.ANY -> v
        }
    }

    private fun cssLength(text: String): Double? = platformRegex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*(px|dp)?\\s*$").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun safeUrl(url: String) = (url.startsWith("https://") || url.startsWith("http://")) && url.length < 4_000 && ' ' !in url

    /** The few CSS properties components understand, so `style={{padding: 12}}` still works. */
    private fun styleProps(style: Any?): Map<String, Any?> {
        val pairs: Map<String, Any?> = when (style) {
            is JsObject -> style.keys().associateWith { style.get(it) }
            is String -> style.split(';').mapNotNull { decl -> decl.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            else -> emptyMap()
        }
        val out = LinkedHashMap<String, Any?>()
        for ((rawKey, value) in pairs) {
            val key = rawKey.split('-').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
            when (key) {
                "padding", "gap", "margin", "width", "height", "maxWidth", "minWidth", "minHeight", "maxHeight" -> out[key] = value
                "background", "backgroundColor" -> out["background"] = value
                "color" -> out["color"] = value
                "borderRadius" -> out["radius"] = value
                "textAlign" -> out["align"] = value
                "fontWeight" -> out["weight"] = when (Values.toStr(value)) { "700", "800", "900", "bold" -> "bold"; "600" -> "semibold"; "500" -> "medium"; else -> "regular" }
                "flexDirection" -> out["direction"] = value
                "justifyContent" -> out["justify"] = Values.toStr(value).removePrefix("space-").removePrefix("flex-")
                "alignItems" -> out["align"] = Values.toStr(value).removePrefix("flex-")
                "flex", "flexGrow" -> out["grow"] = value
            }
        }
        return out
    }

    // ---- final keys, callbacks and text merging ---------------------------------------------------------

    private fun finalize(nodes: List<Pending>, parentKey: String, inline: Boolean): List<UiNode> {
        val out = mutableListOf<UiNode>()
        val text = StringBuilder()
        var textIndex = 0
        fun flushText() {
            if (text.isEmpty()) return
            val value = text.toString()
            text.clear()
            // Whitespace between tags is layout, not content.
            if (value.isBlank() && (!inline || value.contains('\n'))) return
            out += UiText("$parentKey/t${textIndex++}", if (inline) value else value.trim('\n'))
        }
        var position = 0
        for (node in nodes) {
            when (node) {
                is PendingText -> text.append(node.text)
                is PendingElement -> {
                    if (node.type == "#fragment") {
                        flushText()
                        out += finalize(node.children, "$parentKey/${node.key ?: position}", inline)
                        position++
                        continue
                    }
                    flushText()
                    val key = "$parentKey/" + (node.key?.let { "k:$it" } ?: "${node.type}${position}")
                    val spec = catalog.resolve(node.type)?.first
                    val childInline = inline || spec?.kind == ComponentKind.TEXT || spec?.kind == ComponentKind.INLINE
                    val props = node.props.mapValues { (name, value) -> exportProp(key, name, value) }.filterValues { it !== Undefined }
                    out += UiElement(node.type, key, props, finalize(node.children, key, childInline))
                    position++
                }
                is PendingRaw -> {
                    flushText()
                    val key = "$parentKey/raw${position++}"
                    out += UiRaw(key, node.props.mapValues { (name, value) -> exportProp(key, name, value) }.filterValues { it !== Undefined }, node.source, node.complete)
                }
            }
        }
        flushText()
        return out
    }

    private fun exportProp(key: String, name: String, value: Any?): Any? = when (val v = Values.normalize(value)) {
        Undefined -> Undefined
        is JsFunction -> UiCallback("$key#$name").also { callbacks[it.id] = v }
        is MarkupValue -> finalize(v.nodes, "$key#$name", inline = false)
        // Numbers stay Doubles for renderers; saved state and changedState use whole numbers.
        else -> Values.toKotlin(v, wholeNumbersAsLong = false)
    }

    // ---- hooks and actions ------------------------------------------------------------------------------------

    private fun native(name: String, arity: Int = 0, body: (Interpreter, Any?, List<Any?>) -> Any?) = NativeFunction(name, arity, body)

    private fun slotKey(options: Any?): String {
        val explicit = (Values.normalize(options) as? JsObject)?.get("key")?.takeIf { it !== Undefined }?.let(Values::toStr)
        return "$hookPath:" + (explicit ?: "#${hookIndex++}")
    }

    private fun installHooks() {
        val useState = native("useState", 2) { i, _, a ->
            val key = slotKey(a.getOrNull(1))
            if (!slots.containsKey(key)) {
                val init = Values.normalize(a.getOrNull(0))
                val value = if (init is JsFunction) i.callFunction(init, Undefined, emptyList()) else init
                slots[key] = value
                if (!initial.containsKey(key)) initial[key] = value
            }
            val setter = setters.getOrPut(key) {
                native("set") { interp, _, args ->
                    val old = slots[key]
                    val next = Values.normalize(args.getOrNull(0)).let { if (it is JsFunction) interp.callFunction(it, Undefined, listOf(old)) else it }
                    if (!Values.strictEquals(old, next) || next is JsObject) { slots[key] = next; dirty = true }
                    Undefined
                }
            }
            i.newArray(listOf(slots[key], setter))
        }
        val useReducer = native("useReducer", 4) { i, _, a ->
            val reducer = Values.normalize(a.getOrNull(0)) as? JsFunction ?: throw i.typeError("useReducer needs a reducer function")
            val key = slotKey(a.getOrNull(3))
            if (!slots.containsKey(key)) {
                val init = Values.normalize(a.getOrNull(2)).let { f -> if (f is JsFunction) i.callFunction(f, Undefined, listOf(a.getOrNull(1))) else a.getOrNull(1) }
                slots[key] = init
                initial[key] = init
            }
            val dispatch = setters.getOrPut(key) {
                native("dispatch", 1) { interp, _, args -> slots[key] = interp.callFunction(reducer, Undefined, listOf(slots[key], args.getOrNull(0))); dirty = true; Undefined }
            }
            i.newArray(listOf(slots[key], dispatch))
        }
        val useRef = native("useRef", 1) { i, _, a ->
            val key = slotKey(null)
            slots.getOrPut(key) { i.newObject().also { it.set("current", Values.normalize(a.getOrNull(0))) } }
        }
        val useMemo = native("useMemo", 2) { i, _, a -> hookIndex++; (Values.normalize(a.getOrNull(0)) as? JsFunction)?.let { i.callFunction(it, Undefined, emptyList()) } ?: Undefined }
        val useCallback = native("useCallback", 2) { _, _, a -> hookIndex++; Values.normalize(a.getOrNull(0)) }
        val useEffect = native("useEffect", 2) { _, _, _ ->
            hookIndex++
            report(UiDiagnostic("effect_ignored", "Effects and timers don't run in FRIDAY's interface sandbox; derive values during render instead", Severity.INFO))
            Undefined
        }
        val dil = JsObject()
        listOf(useState, useReducer, useRef, useMemo, useCallback, useEffect).forEach { hook ->
            dil.set(hook.name, hook)
            interp.globals.declare(hook.name, hook, constant = true)
        }
        dil.set("useConstants", native("useConstants") { i, _, _ -> i.newObject() })
        dil.frozen = true
        interp.globals.declare("DIL", dil, constant = true)
        interp.globals.declare("React", dil, constant = true)
    }

    private fun installActions() {
        fun action(name: String, make: (Any?) -> UiAction?) = native(name, 1) { _, _, a ->
            if (rendering) {
                report(UiDiagnostic("action_during_render", "GenUI.$name only runs when the user taps; it was ignored during render"))
            } else make(Values.normalize(a.getOrNull(0)))?.let { actions += it }
            Undefined
        }
        val genui = JsObject()
        genui.set("issueNewTurn", action("issueNewTurn") { v -> Values.toStr(v).trim().takeIf { it.isNotEmpty() }?.take(4_000)?.let { UiAction.NewTurn(it) } })
        genui.set("sendMessage", genui.get("issueNewTurn"))
        genui.set("copy", action("copy") { v -> UiAction.Copy(Values.toStr(v).take(20_000)) })
        genui.set("openUrl", action("openUrl") { v -> Values.toStr(v).takeIf(::safeUrl)?.let { UiAction.OpenUrl(it) } })
        genui.set("openEntityDetail", action("openEntityDetail") { v -> UiAction.OpenEntity(Values.toKotlin(v)) })
        genui.set("share", action("share") { v -> UiAction.Share(Values.toStr(v).take(20_000)) })
        genui.set("toast", action("toast") { v -> UiAction.Toast(Values.toStr(v).take(200)) })
        genui.frozen = true
        interp.globals.declare("GenUI", genui, constant = true)
        interp.globals.declare("alert", genui.get("toast"), constant = true)
    }

    private fun report(diagnostic: UiDiagnostic) {
        if (diagnostics.size < 100) diagnostics.putIfAbsent(diagnostic.code + "|" + diagnostic.message, diagnostic)
    }

    companion object {
        private const val MAX_FAILURES = 3

        /** Compiles and renders [source] once, for tests and plain-text fallbacks. */
        fun renderOnce(source: String, complete: Boolean = true, catalog: Catalog = Catalog.default): RenderResult =
            UiRuntime(catalog).load(UiCompiler.compile(source, complete, catalog))
    }
}
