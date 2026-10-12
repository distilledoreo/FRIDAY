package com.localfirst.assistant.genui.script

import com.localfirst.assistant.platform.PlatformCharacter
import com.localfirst.assistant.platform.PlatformStackOverflowError
import com.localfirst.assistant.platform.PlatformSystem
import com.localfirst.assistant.platform.PlatformVolatile
import com.localfirst.assistant.platform.codePoints

/** Budgets that keep model-written code from hanging or exhausting the phone. */
data class Limits(
    val maxSteps: Long = 2_000_000,
    val maxMillis: Long = 1_500,
    val maxCallDepth: Int = 180,
    val maxAllocations: Long = 500_000,
)

class Binding(var value: Any?, val constant: Boolean)

/** A lexical scope. Function scopes also hold `var`s and `this`. */
class Scope(val parent: Scope?, val functionScope: Boolean = false) {
    val vars = HashMap<String, Binding>()

    fun lookup(name: String): Binding? {
        var s: Scope? = this
        while (s != null) {
            s.vars[name]?.let { return it }
            s = s.parent
        }
        return null
    }

    fun declare(name: String, value: Any?, constant: Boolean = false) {
        vars[name] = Binding(value, constant)
    }

    fun functionRoot(): Scope {
        var s = this
        while (!s.functionScope && s.parent != null) s = s.parent!!
        return s
    }
}

private sealed interface Completion
private object Normal : Completion
private class Returned(val value: Any?) : Completion
private class Broke(val label: String?) : Completion
private class Continued(val label: String?) : Completion

/** Marks an optional chain (`a?.b.c`) that stopped at a nullish value. */
private object ShortCircuit

/**
 * Tree-walking interpreter for FRIDAY's JavaScript subset. Single-threaded and synchronous: host
 * functions that do slow work (phone actions) block the interpreter's thread, so `await` simply
 * passes values through. Budgets in [limits] apply per [run]: a program that loops forever, recurses
 * too deep or allocates too much stops with [ScriptLimit].
 */
class Interpreter(
    val limits: Limits = Limits(),
    /** Turns markup inside expressions into a value; set by the UI runtime. */
    var markup: ((MarkupNode, Scope) -> Any?)? = null,
) {
    val globals = Scope(null, functionScope = true)
    private var steps = 0L
    private var deadline = Long.MAX_VALUE
    private var depth = 0
    private var allocations = 0L
    @PlatformVolatile var cancelled = false

    /** Lines written with console.log, for diagnostics and code-mode output. */
    val console = mutableListOf<String>()

    init { Builtins.install(this) }

    /** Runs [block] with fresh budgets. Nested runs share the outer budget. */
    fun <T> run(block: () -> T): T {
        val outer = deadline != Long.MAX_VALUE
        if (!outer) {
            steps = 0
            allocations = 0
            deadline = PlatformSystem.currentTimeMillis() + limits.maxMillis
        }
        try {
            return block()
        } catch (e: PlatformStackOverflowError) {
            throw ScriptLimit("The program recursed too deeply.")
        } finally {
            if (!outer) deadline = Long.MAX_VALUE
        }
    }

    private fun tick() {
        if (++steps > limits.maxSteps) throw ScriptLimit("The program ran too long (step limit).")
        if (steps and 1023L == 0L) {
            if (cancelled) throw ScriptLimit("The program was stopped.")
            if (PlatformSystem.currentTimeMillis() > deadline) throw ScriptLimit("The program ran too long (time limit).")
        }
    }

    fun allocate(count: Int = 1) {
        allocations += count
        if (allocations > limits.maxAllocations) throw ScriptLimit("The program used too much memory.")
    }

    // ---- errors ---------------------------------------------------------------------------------

    fun error(type: String, message: String): JsThrow {
        val ctor = globals.lookup(type)?.value as? JsObject
        val proto = ctor?.get("prototype") as? JsObject
        val err = JsObject(proto = proto, className = "Error")
        err.set("message", message)
        err.set("name", type)
        return JsThrow(err)
    }

    fun typeError(message: String) = error("TypeError", message)

    // ---- programs ---------------------------------------------------------------------------------

    fun execProgram(statements: List<Stmt>, scope: Scope = Scope(globals, functionScope = true)): Any? = run {
        hoist(statements, scope)
        var last: Any? = Undefined
        for (stmt in statements) {
            if (stmt is ExprStmt) { last = eval(stmt.expr, scope); continue }
            when (val c = exec(stmt, scope)) {
                is Returned -> return@run c.value
                else -> Unit
            }
        }
        last
    }

    /** Declares `var`s and functions before a body runs, as JS does. */
    fun hoist(statements: List<Stmt>, scope: Scope) {
        val root = scope.functionRoot()
        fun vars(stmt: Stmt) {
            when (stmt) {
                is VarDecl -> if (stmt.kind == "var") stmt.declarations.forEach { d -> names(d.target).forEach { if (root.vars[it] == null) root.declare(it, Undefined) } }
                is BlockStmt -> stmt.body.forEach(::vars)
                is IfStmt -> { vars(stmt.then); stmt.otherwise?.let(::vars) }
                is ForStmt -> { (stmt.init as? Stmt)?.let(::vars); vars(stmt.body) }
                is ForOfStmt -> { if (stmt.kind == "var") names(stmt.target).forEach { if (root.vars[it] == null) root.declare(it, Undefined) }; vars(stmt.body) }
                is WhileStmt -> vars(stmt.body)
                is TryStmt -> { vars(stmt.block); stmt.handler?.let(::vars); stmt.finalizer?.let(::vars) }
                is SwitchStmt -> stmt.cases.forEach { it.body.forEach(::vars) }
                is LabeledStmt -> vars(stmt.body)
                else -> Unit
            }
        }
        statements.forEach(::vars)
        for (stmt in statements) if (stmt is FunctionDecl) scope.declare(stmt.function.name!!, closure(stmt.function, scope))
    }

    private fun names(pattern: Pattern): List<String> = when (pattern) {
        is IdentPattern -> listOf(pattern.name)
        is ObjectPattern -> pattern.props.flatMap { names(it.value) } + listOfNotNull(pattern.rest)
        is ArrayPattern -> pattern.elements.filterNotNull().flatMap { names(it.target) } + (pattern.rest?.let(::names) ?: emptyList())
        is ExprPattern -> emptyList()
    }

    // ---- statements -----------------------------------------------------------------------------

    private fun execBlock(statements: List<Stmt>, scope: Scope): Completion {
        hoist(statements, scope)
        for (stmt in statements) {
            val c = exec(stmt, scope)
            if (c !== Normal) return c
        }
        return Normal
    }

    private fun exec(stmt: Stmt, scope: Scope): Completion {
        tick()
        when (stmt) {
            is ExprStmt -> eval(stmt.expr, scope)
            is VarDecl -> for (d in stmt.declarations) {
                val value = d.init?.let { eval(it, scope) } ?: Undefined
                if (d.init is FunctionExpr && d.target is IdentPattern && value is Closure && value.function.name == null) value.inferredName = d.target.name
                bind(d.target, value, scope, declare = stmt.kind, constant = stmt.kind == "const")
            }
            is FunctionDecl -> Unit // hoisted
            is BlockStmt -> return execBlock(stmt.body, Scope(scope))
            is ReturnStmt -> return Returned(stmt.value?.let { eval(it, scope) } ?: Undefined)
            is IfStmt -> return if (Values.truthy(eval(stmt.test, scope))) exec(stmt.then, scope) else stmt.otherwise?.let { exec(it, scope) } ?: Normal
            is ForStmt -> return execFor(stmt, scope, null)
            is ForOfStmt -> return execForOf(stmt, scope, null)
            is WhileStmt -> return execWhile(stmt, scope, null)
            is BreakStmt -> return Broke(stmt.label)
            is ContinueStmt -> return Continued(stmt.label)
            is ThrowStmt -> throw JsThrow(eval(stmt.value, scope))
            is TryStmt -> return execTry(stmt, scope)
            is SwitchStmt -> return execSwitch(stmt, scope)
            is LabeledStmt -> {
                val c = when (val body = stmt.body) {
                    is ForStmt -> execFor(body, scope, stmt.label)
                    is ForOfStmt -> execForOf(body, scope, stmt.label)
                    is WhileStmt -> execWhile(body, scope, stmt.label)
                    else -> exec(body, scope)
                }
                return if (c is Broke && c.label == stmt.label) Normal else c
            }
            is EmptyStmt -> Unit
        }
        return Normal
    }

    /** True when the loop should stop and return [out]. */
    private fun loopControl(c: Completion, label: String?): Pair<Boolean, Completion> = when {
        c === Normal -> false to Normal
        c is Broke && (c.label == null || c.label == label) -> true to Normal
        c is Continued && (c.label == null || c.label == label) -> false to Normal
        else -> true to c
    }

    private fun execFor(stmt: ForStmt, scope: Scope, label: String?): Completion {
        val loopScope = Scope(scope)
        when (val init = stmt.init) {
            is Stmt -> exec(init, loopScope)
            is Expr -> eval(init, loopScope)
            else -> Unit
        }
        val perIteration = (stmt.init as? VarDecl)?.takeIf { it.kind != "var" }?.declarations?.flatMap { names(it.target) }.orEmpty()
        var current = loopScope
        while (true) {
            tick()
            if (stmt.test != null && !Values.truthy(eval(stmt.test, current))) return Normal
            val body = Scope(current)
            val (stop, out) = loopControl(exec(stmt.body, body), label)
            if (stop) return out
            // Each iteration gets its own copy of let bindings, so closures capture that iteration.
            if (perIteration.isNotEmpty()) {
                val next = Scope(scope)
                perIteration.forEach { name -> current.vars[name]?.let { next.vars[name] = Binding(it.value, it.constant) } }
                current = next
            }
            stmt.update?.let { eval(it, current) }
        }
    }

    private fun execForOf(stmt: ForOfStmt, scope: Scope, label: String?): Completion {
        val source = eval(stmt.iterable, scope)
        val items: List<Any?> = if (stmt.forIn) keysOf(source) else iterate(source)
        for (item in items) {
            tick()
            val body = Scope(scope)
            bind(stmt.target, item, body, declare = stmt.kind, constant = stmt.kind == "const")
            val (stop, out) = loopControl(exec(stmt.body, body), label)
            if (stop) return out
        }
        return Normal
    }

    private fun execWhile(stmt: WhileStmt, scope: Scope, label: String?): Completion {
        if (stmt.doWhile) {
            do {
                tick()
                val (stop, out) = loopControl(exec(stmt.body, Scope(scope)), label)
                if (stop) return out
            } while (Values.truthy(eval(stmt.test, scope)))
            return Normal
        }
        while (Values.truthy(eval(stmt.test, scope))) {
            tick()
            val (stop, out) = loopControl(exec(stmt.body, Scope(scope)), label)
            if (stop) return out
        }
        return Normal
    }

    private fun execTry(stmt: TryStmt, scope: Scope): Completion {
        var result: Completion = Normal
        try {
            result = execBlock(stmt.block.body, Scope(scope))
        } catch (e: JsThrow) {
            val handler = stmt.handler ?: throw e
            val catchScope = Scope(scope)
            stmt.param?.let { bind(it, e.value, catchScope, declare = "let", constant = false) }
            result = execBlock(handler.body, catchScope)
        } finally {
            stmt.finalizer?.let { finalizer ->
                val f = execBlock(finalizer.body, Scope(scope))
                if (f !== Normal) return f
            }
        }
        return result
    }

    private fun execSwitch(stmt: SwitchStmt, scope: Scope): Completion {
        val value = eval(stmt.discriminant, scope)
        val switchScope = Scope(scope)
        var matched = stmt.cases.indexOfFirst { it.test != null && Values.strictEquals(eval(it.test, switchScope), value) }
        if (matched < 0) matched = stmt.cases.indexOfFirst { it.test == null }
        if (matched < 0) return Normal
        for (case in stmt.cases.drop(matched)) {
            for (s in case.body) {
                val c = exec(s, switchScope)
                if (c is Broke && c.label == null) return Normal
                if (c !== Normal) return c
            }
        }
        return Normal
    }

    // ---- binding --------------------------------------------------------------------------------

    /** Assigns [value] to [pattern]: declaring with [declare] (var/let/const) or assigning when null. */
    fun bind(pattern: Pattern, value: Any?, scope: Scope, declare: String?, constant: Boolean) {
        when (pattern) {
            is IdentPattern -> if (declare == null) assignName(pattern.name, value, scope, pattern.pos)
            else if (declare == "var") {
                val root = scope.functionRoot()
                root.vars[pattern.name]?.let { it.value = value } ?: root.declare(pattern.name, value)
            } else scope.declare(pattern.name, value, constant)
            is ExprPattern -> {
                val member = pattern.expr as? MemberExpr ?: throw error("SyntaxError", "Invalid assignment target")
                val obj = eval(member.obj, scope)
                setProperty(obj, memberKey(member, scope), value)
            }
            is ArrayPattern -> {
                val items = iterate(value)
                pattern.elements.forEachIndexed { i, element ->
                    element ?: return@forEachIndexed
                    var v = items.getOrElse(i) { Undefined }
                    if (v === Undefined && element.default != null) v = eval(element.default, scope)
                    bind(element.target, v, scope, declare, constant)
                }
                pattern.rest?.let { bind(it, newArray(items.drop(pattern.elements.size)), scope, declare, constant) }
            }
            is ObjectPattern -> {
                if (Values.isNullish(value)) throw typeError("Cannot destructure ${Values.toStr(value)}")
                val used = mutableSetOf<String>()
                for (prop in pattern.props) {
                    val key = if (prop.computed) Values.propertyKey(eval(prop.key, scope)) else (prop.key as StringLit).value
                    used += key
                    var v = getProperty(value, key)
                    if (v === Undefined && prop.default != null) v = eval(prop.default, scope)
                    bind(prop.value, v, scope, declare, constant)
                }
                pattern.rest?.let { name ->
                    val rest = newObject()
                    keysOf(value).map { Values.toStr(it) }.filter { it !in used }.forEach { rest.set(it, getProperty(value, it)) }
                    bind(IdentPattern(name, pattern.pos), rest, scope, declare, constant)
                }
            }
        }
    }

    private fun assignName(name: String, value: Any?, scope: Scope, pos: Int) {
        val binding = scope.lookup(name) ?: throw error("ReferenceError", "$name is not defined")
        if (binding.constant) throw typeError("Assignment to constant variable '$name'")
        binding.value = value
    }

    // ---- expressions ----------------------------------------------------------------------------

    fun eval(expr: Expr, scope: Scope): Any? {
        val v = evalChain(expr, scope)
        return if (v === ShortCircuit) Undefined else v
    }

    private fun evalChain(expr: Expr, scope: Scope): Any? {
        tick()
        return when (expr) {
            is NumberLit -> expr.value
            is StringLit -> expr.value
            is BoolLit -> expr.value
            is NullLit -> Null
            is UndefinedLit -> Undefined
            is RegexLit -> try { JsRegExp(expr.pattern, expr.flags) } catch (e: Exception) { throw error("SyntaxError", "Invalid regular expression /${expr.pattern}/") }
            is TemplateLit -> {
                val sb = StringBuilder(expr.quasis[0])
                expr.exprs.forEachIndexed { i, e -> sb.append(Values.toStr(Values.toPrimitive(eval(e, scope)))); sb.append(expr.quasis[i + 1]) }
                checkString(sb.length)
                sb.toString()
            }
            is Ident -> {
                val binding = scope.lookup(expr.name) ?: throw error("ReferenceError", "${expr.name} is not defined")
                binding.value
            }
            is ThisExpr -> scope.lookup("this")?.value ?: Undefined
            is ArrayLit -> {
                val out = mutableListOf<Any?>()
                for (element in expr.elements) {
                    when (element) {
                        null -> out += Undefined
                        is SpreadElement -> out.addAll(iterate(eval(element.arg, scope)))
                        else -> out += eval(element, scope)
                    }
                }
                newArray(out)
            }
            is ObjectLit -> {
                val obj = newObject()
                for (member in expr.members) when (member) {
                    is PropertyMember -> {
                        val key = if (member.computed) Values.propertyKey(eval(member.key, scope)) else (member.key as StringLit).value
                        val value = eval(member.value, scope)
                        if (value is Closure && value.function.name == null) value.inferredName = key
                        obj.set(key, value)
                    }
                    is SpreadMember -> {
                        val source = eval(member.arg, scope)
                        if (source is String) source.forEachIndexed { i, c -> obj.set(i.toString(), c.toString()) }
                        else if (!Values.isNullish(source)) keysOf(source).forEach { k -> val key = Values.toStr(k); obj.set(key, getProperty(source, key)) }
                    }
                }
                obj
            }
            is FunctionExpr -> closure(expr, scope)
            is UnaryExpr -> unary(expr, scope)
            is UpdateExpr -> {
                val old = Values.toNumber(eval(expr.target, scope))
                val new = if (expr.op == "++") old + 1 else old - 1
                assignTo(expr.target, new, scope)
                if (expr.prefix) new else old
            }
            is BinaryExpr -> Operators.binary(this, expr.op, eval(expr.left, scope), eval(expr.right, scope))
            is LogicalExpr -> {
                val left = eval(expr.left, scope)
                when (expr.op) {
                    "&&" -> if (Values.truthy(left)) eval(expr.right, scope) else left
                    "||" -> if (Values.truthy(left)) left else eval(expr.right, scope)
                    else -> if (Values.isNullish(left)) eval(expr.right, scope) else left
                }
            }
            is ConditionalExpr -> if (Values.truthy(eval(expr.test, scope))) eval(expr.then, scope) else eval(expr.otherwise, scope)
            is AssignExpr -> assign(expr, scope)
            is SequenceExpr -> { var last: Any? = Undefined; expr.exprs.forEach { last = eval(it, scope) }; last }
            is MemberExpr -> {
                val obj = evalChain(expr.obj, scope)
                if (obj === ShortCircuit) return ShortCircuit
                if (expr.optional && Values.isNullish(obj)) return ShortCircuit
                getProperty(obj, memberKey(expr, scope), expr)
            }
            is CallExpr -> call(expr, scope)
            is NewExpr -> construct(eval(expr.callee, scope), args(expr.args, scope), expr)
            is AwaitExpr -> eval(expr.arg, scope)
            is SpreadElement -> throw error("SyntaxError", "Unexpected spread")
            is MarkupExpr -> (markup ?: throw error("SyntaxError", "Markup isn't available here")).invoke(expr.node, scope)
        }
    }

    private fun memberKey(expr: MemberExpr, scope: Scope): String =
        if (expr.computed) Values.propertyKey(eval(expr.property, scope)) else (expr.property as StringLit).value

    private fun unary(expr: UnaryExpr, scope: Scope): Any? = when (expr.op) {
        "typeof" -> if (expr.arg is Ident && scope.lookup(expr.arg.name) == null) "undefined" else Values.typeOf(eval(expr.arg, scope))
        "!" -> !Values.truthy(eval(expr.arg, scope))
        "-" -> -Values.toNumber(Values.toPrimitive(eval(expr.arg, scope), "number"))
        "+" -> Values.toNumber(Values.toPrimitive(eval(expr.arg, scope), "number"))
        "~" -> Values.toInt32(eval(expr.arg, scope)).inv().toDouble()
        "void" -> { eval(expr.arg, scope); Undefined }
        "delete" -> {
            val member = expr.arg as? MemberExpr
            if (member == null) true else {
                val obj = eval(member.obj, scope)
                (obj as? JsObject)?.delete(memberKey(member, scope)) ?: true
            }
        }
        else -> throw error("SyntaxError", "Unknown operator ${expr.op}")
    }

    private fun assign(expr: AssignExpr, scope: Scope): Any? {
        if (expr.op == "=") {
            val value = eval(expr.value, scope)
            if (expr.target is IdentPattern && value is Closure && value.function.name == null) value.inferredName = expr.target.name
            bind(expr.target, value, scope, declare = null, constant = false)
            return value
        }
        val targetExpr: Expr = when (val t = expr.target) {
            is IdentPattern -> Ident(t.name, t.pos)
            is ExprPattern -> t.expr
            else -> throw error("SyntaxError", "Invalid assignment target")
        }
        val current = eval(targetExpr, scope)
        val value = when (expr.op) {
            "&&=" -> if (!Values.truthy(current)) return current else eval(expr.value, scope)
            "||=" -> if (Values.truthy(current)) return current else eval(expr.value, scope)
            "??=" -> if (!Values.isNullish(current)) return current else eval(expr.value, scope)
            else -> Operators.binary(this, expr.op.dropLast(1), current, eval(expr.value, scope))
        }
        assignTo(targetExpr, value, scope)
        return value
    }

    private fun assignTo(target: Expr, value: Any?, scope: Scope) {
        when (target) {
            is Ident -> assignName(target.name, value, scope, target.pos)
            is MemberExpr -> setProperty(eval(target.obj, scope), memberKey(target, scope), value)
            else -> throw error("SyntaxError", "Invalid assignment target")
        }
    }

    private fun args(list: List<Expr>, scope: Scope): List<Any?> {
        val out = ArrayList<Any?>(list.size)
        for (a in list) if (a is SpreadElement) out.addAll(iterate(eval(a.arg, scope))) else out.add(eval(a, scope))
        return out
    }

    private fun call(expr: CallExpr, scope: Scope): Any? {
        val callee = expr.callee
        val thisValue: Any?
        val fn: Any?
        if (callee is MemberExpr) {
            val obj = evalChain(callee.obj, scope)
            if (obj === ShortCircuit) return ShortCircuit
            if (callee.optional && Values.isNullish(obj)) return ShortCircuit
            thisValue = obj
            fn = getProperty(obj, memberKey(callee, scope), callee)
        } else {
            fn = evalChain(callee, scope)
            if (fn === ShortCircuit) return ShortCircuit
            thisValue = Undefined
        }
        if (expr.optional && Values.isNullish(fn)) return ShortCircuit
        if (fn !is JsFunction) throw typeError("${describe(callee)} is not a function")
        return callFunction(fn, thisValue, args(expr.args, scope))
    }

    fun describe(expr: Expr): String = when (expr) {
        is Ident -> expr.name
        is MemberExpr -> describe(expr.obj) + if (expr.computed) "[…]" else "." + (expr.property as StringLit).value
        is CallExpr -> describe(expr.callee) + "(…)"
        else -> "expression"
    }

    fun callFunction(fn: Any?, thisValue: Any?, args: List<Any?>): Any? {
        if (fn !is JsFunction) throw typeError("${Values.toStr(fn)} is not a function")
        if (++depth > limits.maxCallDepth) { depth--; throw ScriptLimit("Too much recursion.") }
        try {
            tick()
            return fn.call(this, thisValue, args)
        } finally {
            depth--
        }
    }

    private fun construct(ctor: Any?, args: List<Any?>, expr: NewExpr): Any? {
        when (ctor) {
            is NativeFunction -> {
                val construct = ctor.get("__construct") as? JsFunction
                return if (construct != null) callFunction(construct, Undefined, args) else callFunction(ctor, Undefined, args)
            }
            is Closure -> {
                if (ctor.function.arrow) throw typeError("${describe(expr.callee)} is not a constructor")
                allocate()
                val instance = JsObject(proto = ctor.get("prototype") as? JsObject ?: objectPrototype)
                val result = callFunction(ctor, instance, args)
                return if (result is JsObject) result else instance
            }
            else -> throw typeError("${describe(expr.callee)} is not a constructor")
        }
    }

    fun closure(function: FunctionExpr, scope: Scope): Closure {
        allocate()
        return Closure(function, scope)
    }

    /** A function written in the program. */
    inner class Closure(val function: FunctionExpr, private val scope: Scope) : JsFunction(function.name ?: "") {
        var inferredName: String? = null
        override val arity get() = function.params.count { it.default == null && !it.rest }
        override fun get(key: String): Any? = when {
            key == "name" -> function.name ?: inferredName ?: ""
            key == "prototype" && !function.arrow && !properties.containsKey("prototype") -> JsObject(proto = objectPrototype).also { properties["prototype"] = it }
            else -> super.get(key)
        }

        override fun call(interpreter: Interpreter, thisValue: Any?, args: List<Any?>): Any? {
            val local = Scope(scope, functionScope = true)
            if (!function.arrow) local.declare("this", thisValue)
            function.params.forEachIndexed { i, param ->
                if (param.rest) {
                    bind(param.target, newArray(args.drop(i)), local, declare = "let", constant = false)
                } else {
                    var value = args.getOrElse(i) { Undefined }
                    if (value === Undefined && param.default != null) value = eval(param.default, local)
                    bind(param.target, value, local, declare = "let", constant = false)
                }
            }
            return when (val body = function.body) {
                is Expr -> eval(body, local)
                is BlockStmt -> when (val c = execBlock(body.body, local)) {
                    is Returned -> c.value
                    else -> Undefined
                }
                else -> Undefined
            }
        }
    }

    // ---- properties -------------------------------------------------------------------------------

    fun getProperty(obj: Any?, key: String, at: Expr? = null): Any? {
        when (val o = Values.normalize(obj)) {
            Undefined, Null -> throw typeError("Cannot read properties of ${Values.toStr(o)} (reading '$key')" + (at?.let { " in ${describe(it)}" } ?: ""))
            is String -> return Builtins.stringProperty(this, o, key)
            is Double -> return Builtins.numberProperty(this, o, key)
            is Boolean -> return Builtins.booleanProperty(this, o, key)
            is JsArray -> {
                if (o.properties.containsKey(key) || key == "length" || key.toIntOrNull() != null) return o.get(key)
                return Builtins.arrayProperty(this, o, key)
            }
            is JsFunction -> {
                val own = o.get(key)
                if (own !== Undefined) return own
                return Builtins.functionProperty(this, o, key)
            }
            is JsObject -> {
                val value = o.get(key)
                if (value !== Undefined || o.has(key)) return value
                return Builtins.objectProperty(this, o, key)
            }
            else -> return Undefined
        }
    }

    fun setProperty(obj: Any?, key: String, value: Any?) {
        when (val o = Values.normalize(obj)) {
            Undefined, Null -> throw typeError("Cannot set properties of ${Values.toStr(o)} (setting '$key')")
            is JsObject -> {
                if (o.frozen) return
                if (key == "__proto__" || key == "constructor" && o !is JsArray) return
                o.set(key, value)
            }
            else -> Unit // primitives ignore writes
        }
    }

    // ---- iteration --------------------------------------------------------------------------------

    fun iterate(value: Any?): List<Any?> = when (val v = Values.normalize(value)) {
        is JsArray -> v.items.toList()
        is String -> codePoints(v).map { PlatformCharacter.toChars(it).concatToString() }
        is JsMap -> v.entries.values.map { (k, value) -> newArray(mutableListOf(k, value)) }
        is JsSet -> v.entries.values.map { it.first }
        is JsObject -> (v.get("__iterate") as? JsFunction)?.let { iterate(callFunction(it, v, emptyList())) }
            ?: throw typeError("${Values.toStr(v)} is not iterable")
        else -> throw typeError("${Values.toStr(v)} is not iterable")
    }

    fun keysOf(value: Any?): List<Any?> = when (val v = Values.normalize(value)) {
        is JsArray -> v.keys()
        is String -> v.indices.map { it.toString() }
        is JsObject -> v.keys()
        else -> emptyList()
    }

    fun newArray(items: List<Any?>): JsArray {
        allocate(1 + items.size / 16)
        if (items.size > Values.MAX_ARRAY) throw ScriptLimit("Array is too large.")
        return JsArray(items.toMutableList())
    }

    fun newObject(): JsObject {
        allocate()
        return JsObject(proto = objectPrototype)
    }

    fun checkString(length: Int) {
        if (length > Values.MAX_STRING) throw ScriptLimit("String is too long.")
    }

    internal var objectPrototype: JsObject? = null
}
