package com.localfirst.assistant.genui

import com.localfirst.assistant.platform.platformRegex

import com.localfirst.assistant.genui.script.ArrayPattern
import com.localfirst.assistant.genui.script.BodyNode
import com.localfirst.assistant.genui.script.CallExpr
import com.localfirst.assistant.genui.script.Declarator
import com.localfirst.assistant.genui.script.Ident
import com.localfirst.assistant.genui.script.IdentPattern
import com.localfirst.assistant.genui.script.MarkupNode
import com.localfirst.assistant.genui.script.MarkupRules
import com.localfirst.assistant.genui.script.MemberExpr
import com.localfirst.assistant.genui.script.ObjectLit
import com.localfirst.assistant.genui.script.Parser
import com.localfirst.assistant.genui.script.PropertyMember
import com.localfirst.assistant.genui.script.StringLit
import com.localfirst.assistant.genui.script.VarDecl

enum class Severity { INFO, WARNING, ERROR }

/** Something the compiler or runtime repaired, removed or couldn't run. */
data class UiDiagnostic(val code: String, val message: String, val severity: Severity = Severity.WARNING, val at: Int = -1)

/** A response compiled for the runtime: its markup tree and what was repaired on the way. */
data class CompiledUi(
    val source: String,
    val nodes: List<MarkupNode>,
    val diagnostics: List<UiDiagnostic>,
    /** The response has finished streaming. */
    val complete: Boolean,
)

/**
 * Compiles a reply written in FRIDAY's interface language: Markdown, catalog component tags, `{…}`
 * expressions, `{@body …}` logic, `{#if}` and `{#each}`. The language matches ChatGPT's DIL, so the
 * model writes notation it already knows. Compilation runs on every streamed update (it is fast) and
 * keeps working on half-written text: unfinished constructs are cut, open elements with content are
 * closed, and each repair is recorded.
 */
object UiCompiler {
    private val directive = platformRegex("\\{(@body|@const|#if|#each)\\b")
    private val tag = platformRegex("<([A-Za-z][A-Za-z0-9_-]*)")
    private val userComponent = platformRegex("(?:function\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(|(?:const|let|var)\\s+([A-Z][A-Za-z0-9_]*)\\s*=)")
    private val fence = platformRegex("(?ms)^\\s*(```|~~~).*?(^\\s*\\1\\s*$|\\z)")
    private val inlineCode = platformRegex("`[^`\\n]*`")

    /** Whether a reply uses interface components at all; plain Markdown takes the faster path. */
    fun isInterface(text: String, catalog: Catalog = Catalog.default): Boolean {
        if ('<' !in text && '{' !in text) return false
        val prose = inlineCode.replace(fence.replace(text, ""), "")
        if (directive.containsMatchIn(prose)) return true
        val defined = userComponents(prose)
        return tag.findAll(prose).any { m -> val name = m.groupValues[1]; catalog.isSignature(name) || name in defined }
    }

    private fun userComponents(text: String): Set<String> =
        userComponent.findAll(text).mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { null } }.toSet()

    fun compile(source: String, complete: Boolean, catalog: Catalog = Catalog.default): CompiledUi {
        val defined = userComponents(source)
        val rules = MarkupRules(
            isComponent = { catalog.has(it) || it in defined },
            isComponentPrefix = { prefix -> catalog.isPrefix(prefix) || defined.any { it.startsWith(prefix) } },
            isVoid = { catalog.isVoid(it) },
            isRaw = { catalog.isRaw(it) },
            complete = complete,
        )
        val parser = Parser(source, rules)
        val nodes = try {
            parser.parseDocument()
        } catch (e: RuntimeException) {
            // The markup reader repairs what it can; anything else falls back to plain text.
            return CompiledUi(source, listOf(com.localfirst.assistant.genui.script.TextNode(listOf(source), 0)), listOf(UiDiagnostic("parse_failed", e.message ?: "Unreadable response", Severity.ERROR)), complete)
        }
        val diagnostics = parser.diagnostics.map { UiDiagnostic(it.code, it.message, Severity.INFO, it.at) }
        return CompiledUi(source, nodes.map(::withStateKeys), diagnostics, complete)
    }

    /**
     * Gives each `const [x, setX] = useState(…)` the key "x", so its value survives every
     * recompile while the response streams, and reloads (as ChatGPT's compiler does).
     */
    private fun withStateKeys(node: MarkupNode): MarkupNode {
        if (node !is BodyNode) return node
        return node.copy(statements = node.statements.map { stmt ->
            if (stmt !is VarDecl) stmt else stmt.copy(declarations = stmt.declarations.map(::keyed))
        })
    }

    private val hooks = setOf("useState", "useReducer")

    private fun keyed(d: Declarator): Declarator {
        val call = d.init as? CallExpr ?: return d
        val name = when (val callee = call.callee) {
            is Ident -> callee.name
            is MemberExpr -> (callee.property as? StringLit)?.value
            else -> null
        } ?: return d
        if (name !in hooks) return d
        val variable = ((d.target as? ArrayPattern)?.elements?.firstOrNull()?.target as? IdentPattern)?.name ?: return d
        val keyArgIndex = if (name == "useReducer") 3 else 1
        if (call.args.size > keyArgIndex) return d
        val args = call.args.toMutableList()
        while (args.size < keyArgIndex) args += com.localfirst.assistant.genui.script.UndefinedLit(call.pos)
        args += ObjectLit(listOf(PropertyMember(StringLit("key", call.pos), false, StringLit(variable, call.pos))), call.pos)
        return d.copy(init = call.copy(args = args))
    }
}
