package com.localfirst.assistant.genui.script

/** Something the parser repaired or left out, reported like ChatGPT's recoveryDiagnostics. */
data class ParseDiagnostic(val code: String, val message: String, val at: Int)

/** What the markup reader needs to know about the component catalog. */
data class MarkupRules(
    /** Whether `<name` in prose starts a component tag. */
    val isComponent: (String) -> Boolean = { true },
    /** Whether a tag name still being streamed could become a component, so it isn't shown as text yet. */
    val isComponentPrefix: (String) -> Boolean = { true },
    /** Components that never have children: `<divider>` without `/>` is closed right away. */
    val isVoid: (String) -> Boolean = { false },
    /** Components whose body is raw source (AppBlock). */
    val isRaw: (String) -> Boolean = { it == "AppBlock" },
    /** The text is final. Unfinished constructs then stay as text instead of waiting for more. */
    val complete: Boolean = false,
)

/**
 * Parses FRIDAY's JavaScript subset and the markup it lives in (Markdown, component tags, `{…}`
 * expressions, `{@body …}`, `{#if}`, `{#each}`). The markup side tolerates half-written input: it
 * stops at the last complete construct, closes open elements that already have content, and records
 * each repair in [diagnostics].
 *
 * [isComponent] decides which `<name` starts a tag in prose; anything else stays literal text, so a
 * reply can still say "a < b" or "<3".
 */
class Parser(
    private val src: String,
    private val rules: MarkupRules = MarkupRules(),
) {
    private val isComponent get() = rules.isComponent
    private val isRawComponent get() = rules.isRaw

    private val lexer = Lexer(src)
    private var tok: Token = Token(TokenType.EOF, "", 0, 0, false)
    val diagnostics = mutableListOf<ParseDiagnostic>()

    // ---- whole inputs --------------------------------------------------------------------

    /** A plain script: statements to the end. */
    fun parseProgram(): List<Stmt> {
        sync(0)
        val body = mutableListOf<Stmt>()
        while (tok.type != TokenType.EOF) body += parseStatement()
        return body
    }

    /** One expression, for tests and host evaluation. */
    fun parseExpressionOnly(): Expr {
        sync(0)
        val expr = parseExpression()
        if (tok.type != TokenType.EOF) throw ScriptSyntaxError(tok.start, "Unexpected '${tok.text}'")
        return expr
    }

    /** A whole response: markup to the end, repaired where it stops mid-construct. */
    fun parseDocument(): List<MarkupNode> = Markup().document()

    // ---- tokens --------------------------------------------------------------------------

    private fun sync(at: Int, regexAllowed: Boolean = true) {
        lexer.pos = at
        tok = lexer.next(regexAllowed)
    }

    private fun regexAllowedAfter(t: Token): Boolean = when (t.type) {
        TokenType.IDENT -> t.text in REGEX_KEYWORDS
        TokenType.NUMBER, TokenType.STRING, TokenType.REGEX, TokenType.TEMPLATE -> false
        TokenType.PUNCT -> t.text != ")" && t.text != "]" && t.text != "}"
        TokenType.EOF -> true
    }

    private fun advance(): Token {
        val t = tok
        tok = lexer.next(regexAllowedAfter(t))
        return t
    }

    private fun isPunct(text: String) = tok.type == TokenType.PUNCT && tok.text == text
    private fun isWord(text: String) = tok.type == TokenType.IDENT && tok.text == text

    private fun eat(text: String): Boolean {
        if (isPunct(text)) { advance(); return true }
        return false
    }

    private fun expect(text: String): Token {
        if (isPunct(text)) return advance()
        throw unexpected("Expected '$text'")
    }

    private fun unexpected(what: String = "Unexpected token"): RuntimeException =
        if (tok.type == TokenType.EOF) IncompleteInput(tok.start, "$what at end of input")
        else ScriptSyntaxError(tok.start, "$what, found '${tok.text}'")

    private fun identifier(): String {
        if (tok.type != TokenType.IDENT) throw unexpected("Expected a name")
        if (tok.text in RESERVED) throw ScriptSyntaxError(tok.start, "'${tok.text}' can't be used as a name")
        return advance().text
    }

    /** Peeks at the token after the current one without consuming anything. */
    private fun peek(): Token {
        val saveTok = tok
        val savePos = lexer.pos
        val next = lexer.next(regexAllowedAfter(tok))
        lexer.pos = savePos
        tok = saveTok
        return next
    }

    private fun consumeSemicolon() {
        if (eat(";")) return
        if (isPunct("}") || tok.type == TokenType.EOF || tok.newlineBefore) return
        throw unexpected("Expected ';'")
    }

    // ---- statements ------------------------------------------------------------------------

    private fun parseStatement(): Stmt {
        val start = tok.start
        if (tok.type == TokenType.PUNCT) when (tok.text) {
            "{" -> return parseBlock()
            ";" -> { advance(); return EmptyStmt(start) }
        }
        if (tok.type == TokenType.IDENT) when (tok.text) {
            "var", "const" -> return parseVarDecl().also { consumeSemicolon() }
            "let" -> if (peek().let { it.type == TokenType.IDENT || it.text == "[" || it.text == "{" }) return parseVarDecl().also { consumeSemicolon() }
            "function" -> return FunctionDecl(parseFunction(async = false, declaration = true), start)
            "async" -> if (peek().let { it.text == "function" && !it.newlineBefore }) { advance(); return FunctionDecl(parseFunction(async = true, declaration = true), start) }
            "if" -> return parseIf()
            "for" -> return parseFor()
            "while" -> {
                advance(); expect("(")
                val test = parseExpression(); expect(")")
                return WhileStmt(test, parseStatement(), doWhile = false, pos = start)
            }
            "do" -> {
                advance()
                val body = parseStatement()
                if (!isWord("while")) throw unexpected("Expected 'while'")
                advance(); expect("(")
                val test = parseExpression(); expect(")")
                eat(";")
                return WhileStmt(test, body, doWhile = true, pos = start)
            }
            "return" -> {
                advance()
                val value = if (isPunct(";") || isPunct("}") || tok.type == TokenType.EOF || tok.newlineBefore) null else parseExpression()
                consumeSemicolon()
                return ReturnStmt(value, start)
            }
            "break", "continue" -> {
                val word = advance().text
                val label = if (tok.type == TokenType.IDENT && !tok.newlineBefore && tok.text !in RESERVED) advance().text else null
                consumeSemicolon()
                return if (word == "break") BreakStmt(label, start) else ContinueStmt(label, start)
            }
            "throw" -> {
                advance()
                val value = parseExpression()
                consumeSemicolon()
                return ThrowStmt(value, start)
            }
            "try" -> return parseTry()
            "switch" -> return parseSwitch()
            "class" -> throw ScriptSyntaxError(start, "Classes aren't supported here; use functions and objects")
            "import", "export" -> throw ScriptSyntaxError(start, "Modules aren't available here")
        }
        if (tok.type == TokenType.IDENT && tok.text !in RESERVED && peek().text == ":") {
            val label = advance().text
            advance()
            return LabeledStmt(label, parseStatement(), start)
        }
        val expr = parseExpression()
        consumeSemicolon()
        return ExprStmt(expr, start)
    }

    private fun parseBlock(): BlockStmt {
        val start = expect("{").start
        val body = mutableListOf<Stmt>()
        while (!isPunct("}")) {
            if (tok.type == TokenType.EOF) throw IncompleteInput(tok.start, "Unclosed block")
            body += parseStatement()
        }
        advance()
        return BlockStmt(body, start)
    }

    private fun parseVarDecl(): VarDecl {
        val start = tok.start
        val kind = advance().text
        val declarations = mutableListOf<Declarator>()
        do {
            val target = parseBindingTarget()
            val init = if (eat("=")) parseAssign() else null
            declarations += Declarator(target, init)
        } while (eat(","))
        return VarDecl(kind, declarations, start)
    }

    private fun parseIf(): IfStmt {
        val start = advance().start
        expect("(")
        val test = parseExpression()
        expect(")")
        val then = parseStatement()
        val otherwise = if (isWord("else")) { advance(); parseStatement() } else null
        return IfStmt(test, then, otherwise, start)
    }

    private fun parseFor(): Stmt {
        val start = advance().start
        if (isWord("await")) advance()
        expect("(")
        var init: Node? = null
        if (!isPunct(";")) {
            if (isWord("var") || isWord("let") || isWord("const")) {
                val declStart = tok.start
                val kind = advance().text
                val target = parseBindingTarget()
                if (isWord("of") || isWord("in")) {
                    val forIn = advance().text == "in"
                    val iterable = if (forIn) parseExpression() else parseAssign()
                    expect(")")
                    return ForOfStmt(kind, target, iterable, parseStatement(), forIn, start)
                }
                val declarations = mutableListOf(Declarator(target, if (eat("=")) parseAssign(noIn = true) else null))
                while (eat(",")) {
                    val next = parseBindingTarget()
                    declarations += Declarator(next, if (eat("=")) parseAssign(noIn = true) else null)
                }
                init = VarDecl(kind, declarations, declStart)
            } else {
                val expr = parseExpression(noIn = true)
                if (isWord("of") || isWord("in")) {
                    val forIn = advance().text == "in"
                    val iterable = if (forIn) parseExpression() else parseAssign()
                    expect(")")
                    return ForOfStmt(null, toPattern(expr), iterable, parseStatement(), forIn, start)
                }
                init = expr
            }
        }
        expect(";")
        val test = if (isPunct(";")) null else parseExpression()
        expect(";")
        val update = if (isPunct(")")) null else parseExpression()
        expect(")")
        return ForStmt(init, test, update, parseStatement(), start)
    }

    private fun parseTry(): TryStmt {
        val start = advance().start
        val block = parseBlock()
        var param: Pattern? = null
        var handler: BlockStmt? = null
        if (isWord("catch")) {
            advance()
            if (eat("(")) { param = parseBindingTarget(); expect(")") }
            handler = parseBlock()
        }
        val finalizer = if (isWord("finally")) { advance(); parseBlock() } else null
        if (handler == null && finalizer == null) throw unexpected("Expected 'catch' or 'finally'")
        return TryStmt(block, param, handler, finalizer, start)
    }

    private fun parseSwitch(): SwitchStmt {
        val start = advance().start
        expect("(")
        val discriminant = parseExpression()
        expect(")")
        expect("{")
        val cases = mutableListOf<SwitchCase>()
        while (!isPunct("}")) {
            val test = when {
                isWord("case") -> { advance(); parseExpression() }
                isWord("default") -> { advance(); null }
                else -> throw unexpected("Expected 'case'")
            }
            expect(":")
            val body = mutableListOf<Stmt>()
            while (!isWord("case") && !isWord("default") && !isPunct("}")) {
                if (tok.type == TokenType.EOF) throw IncompleteInput(tok.start, "Unclosed switch")
                body += parseStatement()
            }
            cases += SwitchCase(test, body)
        }
        advance()
        return SwitchStmt(discriminant, cases, start)
    }

    // ---- functions ---------------------------------------------------------------------------

    private fun parseFunction(async: Boolean, declaration: Boolean): FunctionExpr {
        val start = tok.start
        if (!isWord("function")) throw unexpected("Expected 'function'")
        advance()
        eat("*")
        val name = if (tok.type == TokenType.IDENT && !isPunct("(")) identifier() else if (declaration) throw unexpected("Expected a function name") else null
        val params = parseParams()
        val body = parseBlock()
        return FunctionExpr(name, params, body, arrow = false, async = async, pos = start)
    }

    private fun parseParams(): List<Param> {
        expect("(")
        val params = mutableListOf<Param>()
        while (!isPunct(")")) {
            val rest = eat("...")
            val target = parseBindingTarget()
            val default = if (!rest && eat("=")) parseAssign() else null
            params += Param(target, default, rest)
            if (!isPunct(")")) expect(",")
        }
        advance()
        return params
    }

    private fun parseArrowBody(params: List<Param>, async: Boolean, start: Int): FunctionExpr {
        expect("=>")
        val body: Node = if (isPunct("{")) parseBlock() else parseAssign()
        return FunctionExpr(null, params, body, arrow = true, async = async, pos = start)
    }

    /** Whether `(` at the current token starts arrow-function parameters. */
    private fun parenIsArrow(): Boolean {
        val saveTok = tok
        val savePos = lexer.pos
        try {
            var depth = 0
            while (true) {
                when {
                    tok.type == TokenType.EOF -> return false
                    tok.type == TokenType.PUNCT && tok.text in setOf("(", "[", "{") -> depth++
                    tok.type == TokenType.PUNCT && tok.text in setOf(")", "]", "}") -> {
                        depth--
                        if (depth == 0) {
                            advance()
                            return isPunct("=>") && !tok.newlineBefore
                        }
                    }
                }
                advance()
            }
        } catch (e: RuntimeException) {
            return false
        } finally {
            lexer.pos = savePos
            tok = saveTok
        }
    }

    // ---- binding patterns -----------------------------------------------------------------------

    private fun parseBindingTarget(): Pattern {
        val start = tok.start
        return when {
            isPunct("[") -> {
                advance()
                val elements = mutableListOf<ArrayPatternElement?>()
                var rest: Pattern? = null
                while (!isPunct("]")) {
                    if (isPunct(",")) { advance(); elements += null; continue }
                    if (eat("...")) { rest = parseBindingTarget(); break }
                    val target = parseBindingTarget()
                    val default = if (eat("=")) parseAssign() else null
                    elements += ArrayPatternElement(target, default)
                    if (!isPunct("]")) expect(",")
                }
                expect("]")
                ArrayPattern(elements, rest, start)
            }
            isPunct("{") -> {
                advance()
                val props = mutableListOf<ObjectPatternProp>()
                var rest: String? = null
                while (!isPunct("}")) {
                    if (eat("...")) { rest = identifier(); break }
                    val (key, computed) = propertyKey()
                    val value: Pattern = if (eat(":")) parseBindingTarget() else {
                        val name = (key as? StringLit)?.value ?: throw unexpected("Expected a name")
                        IdentPattern(name, key.pos)
                    }
                    val default = if (eat("=")) parseAssign() else null
                    props += ObjectPatternProp(key, computed, value, default)
                    if (!isPunct("}")) expect(",")
                }
                expect("}")
                ObjectPattern(props, rest, start)
            }
            else -> IdentPattern(identifier(), start)
        }
    }

    /** Converts an expression on the left of `=` (or in for-of) into an assignment target. */
    private fun toPattern(expr: Expr): Pattern = when (expr) {
        is Ident -> IdentPattern(expr.name, expr.pos)
        is MemberExpr -> ExprPattern(expr, expr.pos)
        is ArrayLit -> ArrayPattern(
            expr.elements.filter { it !is SpreadElement }.map { element ->
                element?.let {
                    if (it is AssignExpr && it.op == "=") ArrayPatternElement(it.target, it.value) else ArrayPatternElement(toPattern(it), null)
                }
            },
            (expr.elements.lastOrNull() as? SpreadElement)?.let { toPattern(it.arg) },
            expr.pos,
        )
        is ObjectLit -> ObjectPattern(
            expr.members.filterIsInstance<PropertyMember>().map { member ->
                val value = member.value
                if (value is AssignExpr && value.op == "=") ObjectPatternProp(member.key, member.computed, value.target, value.value)
                else ObjectPatternProp(member.key, member.computed, toPattern(value), null)
            },
            expr.members.filterIsInstance<SpreadMember>().firstOrNull()?.let { (it.arg as? Ident)?.name },
            expr.pos,
        )
        is AssignExpr -> if (expr.op == "=") expr.target else throw ScriptSyntaxError(expr.pos, "Invalid assignment target")
        else -> throw ScriptSyntaxError(expr.pos, "Invalid assignment target")
    }

    // ---- expressions --------------------------------------------------------------------------

    private fun parseExpression(noIn: Boolean = false): Expr {
        val start = tok.start
        val first = parseAssign(noIn)
        if (!isPunct(",")) return first
        val exprs = mutableListOf(first)
        while (eat(",")) exprs += parseAssign(noIn)
        return SequenceExpr(exprs, start)
    }

    private fun parseAssign(noIn: Boolean = false): Expr {
        val start = tok.start
        // Arrow functions: `x => …`, `async x => …`, `(a, b) => …`, `async (a) => …`.
        if (tok.type == TokenType.IDENT && tok.text !in RESERVED) {
            val next = peek()
            if (next.text == "=>" && next.type == TokenType.PUNCT && !next.newlineBefore) {
                val name = advance().text
                return parseArrowBody(listOf(Param(IdentPattern(name, start), null, false)), async = false, start = start)
            }
            if (tok.text == "async" && !next.newlineBefore && (next.type == TokenType.IDENT || next.text == "(")) {
                val saveTok = tok
                val savePos = lexer.pos
                advance()
                if (tok.type == TokenType.IDENT && peek().text == "=>") {
                    val name = advance().text
                    return parseArrowBody(listOf(Param(IdentPattern(name, start), null, false)), async = true, start = start)
                }
                if (isPunct("(") && parenIsArrow()) return parseArrowBody(parseParams(), async = true, start = start)
                lexer.pos = savePos
                tok = saveTok
            }
        }
        if (isPunct("(") && parenIsArrow()) return parseArrowBody(parseParams(), async = false, start = start)

        val left = parseConditional(noIn)
        if (tok.type == TokenType.PUNCT && tok.text in ASSIGN_OPS) {
            val op = advance().text
            val target = if (op == "=") toPattern(left) else when (left) {
                is Ident -> IdentPattern(left.name, left.pos)
                is MemberExpr -> ExprPattern(left, left.pos)
                else -> throw ScriptSyntaxError(left.pos, "Invalid assignment target")
            }
            return AssignExpr(op, target, parseAssign(noIn), start)
        }
        return left
    }

    private fun parseConditional(noIn: Boolean): Expr {
        val start = tok.start
        val test = parseBinary(0, noIn)
        if (!eat("?")) return test
        val then = parseAssign()
        expect(":")
        return ConditionalExpr(test, then, parseAssign(noIn), start)
    }

    private fun binaryPrecedence(noIn: Boolean): Int {
        if (tok.type == TokenType.IDENT) return when (tok.text) {
            "instanceof" -> 8
            "in" -> if (noIn) -1 else 8
            else -> -1
        }
        if (tok.type != TokenType.PUNCT) return -1
        return BINARY_PRECEDENCE[tok.text] ?: -1
    }

    private fun parseBinary(minPrecedence: Int, noIn: Boolean): Expr {
        var left = parseUnary()
        while (true) {
            val precedence = binaryPrecedence(noIn)
            if (precedence < 0 || precedence < minPrecedence) return left
            val op = advance().text
            // `**` is right-associative.
            val right = parseBinary(if (op == "**") precedence else precedence + 1, noIn)
            left = if (op == "&&" || op == "||" || op == "??") LogicalExpr(op, left, right, left.pos) else BinaryExpr(op, left, right, left.pos)
        }
    }

    private fun parseUnary(): Expr {
        val start = tok.start
        if (tok.type == TokenType.PUNCT && tok.text in setOf("!", "-", "+", "~")) {
            val op = advance().text
            return UnaryExpr(op, parseUnary(), start)
        }
        if (tok.type == TokenType.PUNCT && (tok.text == "++" || tok.text == "--")) {
            val op = advance().text
            return UpdateExpr(op, prefix = true, target = parseUnary(), pos = start)
        }
        if (tok.type == TokenType.IDENT && tok.text in setOf("typeof", "void", "delete")) {
            val op = advance().text
            return UnaryExpr(op, parseUnary(), start)
        }
        if (isWord("await")) {
            advance()
            return AwaitExpr(parseUnary(), start)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        val start = tok.start
        val expr = parseCallMember(parsePrimary())
        if (tok.type == TokenType.PUNCT && (tok.text == "++" || tok.text == "--") && !tok.newlineBefore) {
            val op = advance().text
            return UpdateExpr(op, prefix = false, target = expr, pos = start)
        }
        return expr
    }

    private fun parseArguments(): List<Expr> {
        expect("(")
        val args = mutableListOf<Expr>()
        while (!isPunct(")")) {
            val start = tok.start
            args += if (eat("...")) SpreadElement(parseAssign(), start) else parseAssign()
            if (!isPunct(")")) expect(",")
        }
        advance()
        return args
    }

    private fun propertyName(): String = when (tok.type) {
        TokenType.IDENT -> advance().text
        else -> throw unexpected("Expected a property name")
    }

    private fun parseCallMember(primary: Expr, allowCall: Boolean = true): Expr {
        var expr = primary
        while (true) {
            val start = tok.start
            when {
                isPunct(".") -> {
                    advance()
                    if (isPunct("#")) throw ScriptSyntaxError(tok.start, "Private fields aren't supported")
                    expr = MemberExpr(expr, StringLit(propertyName(), start), computed = false, optional = false, pos = start)
                }
                isPunct("?.") -> {
                    advance()
                    expr = when {
                        isPunct("(") -> CallExpr(expr, parseArguments(), optional = true, pos = start)
                        isPunct("[") -> { advance(); val key = parseExpression(); expect("]"); MemberExpr(expr, key, computed = true, optional = true, pos = start) }
                        else -> MemberExpr(expr, StringLit(propertyName(), start), computed = false, optional = true, pos = start)
                    }
                }
                isPunct("[") -> {
                    advance()
                    val key = parseExpression()
                    expect("]")
                    expr = MemberExpr(expr, key, computed = true, optional = false, pos = start)
                }
                isPunct("(") && allowCall -> expr = CallExpr(expr, parseArguments(), optional = false, pos = start)
                tok.type == TokenType.TEMPLATE -> throw ScriptSyntaxError(tok.start, "Tagged templates aren't supported")
                else -> return expr
            }
        }
    }

    private fun propertyKey(): Pair<Expr, Boolean> {
        val start = tok.start
        return when (tok.type) {
            TokenType.IDENT -> StringLit(advance().text, start) to false
            TokenType.STRING -> StringLit(advance().text, start) to false
            TokenType.NUMBER -> StringLit(Values.numberToString(advance().number), start) to false
            else -> if (eat("[")) { val key = parseAssign(); expect("]"); key to true } else throw unexpected("Expected a property name")
        }
    }

    private fun parsePrimary(): Expr {
        val start = tok.start
        when (tok.type) {
            TokenType.NUMBER -> return NumberLit(advance().number, start)
            TokenType.STRING -> return StringLit(advance().text, start)
            TokenType.REGEX -> { val t = advance(); return RegexLit(t.text, t.flags, start) }
            TokenType.TEMPLATE -> return parseTemplate(start)
            TokenType.EOF -> throw IncompleteInput(start, "Expression expected at end of input")
            TokenType.IDENT -> when (tok.text) {
                "true" -> { advance(); return BoolLit(true, start) }
                "false" -> { advance(); return BoolLit(false, start) }
                "null" -> { advance(); return NullLit(start) }
                "undefined" -> { advance(); return UndefinedLit(start) }
                "this" -> { advance(); return ThisExpr(start) }
                "function" -> return parseFunction(async = false, declaration = false)
                "async" -> if (peek().text == "function") { advance(); return parseFunction(async = true, declaration = false) }
                "new" -> {
                    advance()
                    if (isPunct(".")) { advance(); propertyName(); return UndefinedLit(start) } // new.target
                    val callee = parseCallMember(parsePrimary(), allowCall = false)
                    val args = if (isPunct("(")) parseArguments() else emptyList()
                    return NewExpr(callee, args, start)
                }
                "class" -> throw ScriptSyntaxError(start, "Classes aren't supported here; use functions and objects")
                else -> {
                    if (tok.text in RESERVED) throw ScriptSyntaxError(start, "Unexpected '${tok.text}'")
                    return Ident(advance().text, start)
                }
            }
            TokenType.PUNCT -> when (tok.text) {
                "(" -> {
                    advance()
                    val expr = parseExpression()
                    expect(")")
                    return expr
                }
                "[" -> {
                    advance()
                    val elements = mutableListOf<Expr?>()
                    while (!isPunct("]")) {
                        if (isPunct(",")) { advance(); elements += null; continue }
                        val elementStart = tok.start
                        elements += if (eat("...")) SpreadElement(parseAssign(), elementStart) else parseAssign()
                        if (!isPunct("]")) expect(",")
                    }
                    advance()
                    return ArrayLit(elements, start)
                }
                "{" -> return parseObject()
                "<" -> {
                    val after = src.getOrNull(tok.end)
                    if (after != null && (after.isLetter() || after == '>')) {
                        val node = Markup().elementAt(tok.start, inExpression = true)
                            ?: throw IncompleteInput(tok.start, "Unterminated element")
                        sync(markupEnd, regexAllowed = false)
                        return MarkupExpr(node, start)
                    }
                }
            }
        }
        throw unexpected()
    }

    private fun parseTemplate(start: Int): Expr {
        // The lexer sits just after the backtick.
        val quasis = mutableListOf<String>()
        val exprs = mutableListOf<Expr>()
        while (true) {
            val (text, ended) = lexer.templateChunk()
            quasis += text
            if (ended) break
            tok = lexer.next(true)
            exprs += parseExpression()
            if (!isPunct("}")) throw unexpected("Expected '}' in template")
            lexer.pos = tok.end
        }
        tok = lexer.next(false)
        return TemplateLit(quasis, exprs, start)
    }

    private fun parseObject(): Expr {
        val start = expect("{").start
        val members = mutableListOf<ObjectMember>()
        while (!isPunct("}")) {
            if (eat("...")) {
                members += SpreadMember(parseAssign())
            } else {
                var async = false
                if (isWord("async") && peek().let { it.type != TokenType.PUNCT || it.text == "[" }) { advance(); async = true }
                if ((isWord("get") || isWord("set")) && peek().let { it.type == TokenType.IDENT || it.type == TokenType.STRING || it.text == "[" }) {
                    throw ScriptSyntaxError(tok.start, "Getters and setters aren't supported; use a plain property or method")
                }
                val keyToken = tok
                val (key, computed) = propertyKey()
                when {
                    eat(":") -> members += PropertyMember(key, computed, parseAssign())
                    isPunct("(") -> {
                        val params = parseParams()
                        val body = parseBlock()
                        members += PropertyMember(key, computed, FunctionExpr((key as? StringLit)?.value, params, body, arrow = false, async = async, pos = keyToken.start))
                    }
                    else -> {
                        if (computed || keyToken.type != TokenType.IDENT) throw unexpected("Expected ':'")
                        val value: Expr = Ident(keyToken.text, keyToken.start)
                        // Shorthand with default, only valid as a pattern: `{a = 1} = obj`.
                        members += if (isPunct("=")) { advance(); PropertyMember(key, false, AssignExpr("=", IdentPattern(keyToken.text, keyToken.start), parseAssign(), keyToken.start)) }
                        else PropertyMember(key, false, value)
                    }
                }
            }
            if (!isPunct("}")) expect(",")
        }
        advance()
        return ObjectLit(members, start)
    }

    // ---- markup -------------------------------------------------------------------------------

    /** Where the last markup element parsed from inside an expression ended. */
    private var markupEnd = 0

    private enum class End { EOF, CLOSE_TAG, PARENT_CLOSE, ELSE_IF, ELSE, END_IF, END_EACH }

    private class Children(val nodes: List<MarkupNode>, val end: End, val elseIf: Expr? = null)

    /**
     * Character-level markup reader. Text accumulates until a component tag, a `{…}` construct, or
     * the closing tag of the element being read; fenced and inline code stay verbatim.
     */
    private inner class Markup {
        var pos = 0
        /** Open element names, innermost last, for closing tags that skip a level. */
        private val open = mutableListOf<String>()

        fun document(): List<MarkupNode> {
            pos = 0
            return children(close = null, inExpression = false).nodes
        }

        fun elementAt(at: Int, inExpression: Boolean): MarkupNode? {
            pos = at
            val node = element(inExpression)
            markupEnd = pos
            return node
        }

        private fun startsWith(text: String) = src.startsWith(text, pos)

        private fun atLineStart(): Boolean {
            var i = pos - 1
            while (i >= 0 && (src[i] == ' ' || src[i] == '\t')) i--
            return i < 0 || src[i] == '\n'
        }

        private fun children(close: String?, inExpression: Boolean): Children {
            val nodes = mutableListOf<MarkupNode>()
            val text = StringBuilder()
            var textStart = pos
            fun flush() {
                if (text.isNotEmpty()) nodes += TextNode(listOf(text.toString()), textStart)
                text.clear()
                textStart = pos
            }
            while (pos < src.length) {
                val c = src[pos]
                // Fenced code blocks are verbatim, even half-written.
                if ((c == '`' || c == '~') && atLineStart() && (startsWith("```") || startsWith("~~~"))) {
                    val fence = src.substring(pos, pos + 3)
                    val lineEnd = src.indexOf('\n', pos).let { if (it < 0) src.length else it }
                    var end = src.indexOf("\n" + fence, lineEnd)
                    while (end >= 0) {
                        // The closing fence must be the line's only content.
                        val after = src.indexOf('\n', end + 1 + fence.length).let { if (it < 0) src.length else it }
                        if (src.substring(end + 1 + fence.length, after).isBlank()) break
                        end = src.indexOf("\n" + fence, end + 1)
                    }
                    val stop = if (end < 0) src.length else (src.indexOf('\n', end + 1 + fence.length).let { if (it < 0) src.length else it })
                    text.append(src, pos, stop)
                    pos = stop
                    continue
                }
                if (c == '`') {
                    var run = 0
                    while (pos + run < src.length && src[pos + run] == '`') run++
                    val ticks = "`".repeat(run)
                    val end = src.indexOf(ticks, pos + run)
                    val paragraphEnd = src.indexOf("\n\n", pos).let { if (it < 0) src.length else it }
                    val stop = if (end in 0 until paragraphEnd) end + run else pos + run
                    text.append(src, pos, stop)
                    pos = stop
                    continue
                }
                if (c == '\\' && pos + 1 < src.length && (src[pos + 1] == '{' || src[pos + 1] == '<' || src[pos + 1] == '}')) {
                    text.append(src[pos + 1]); pos += 2; continue
                }
                if (c == '<') {
                    if (src.startsWith("</", pos)) {
                        val name = src.substring(pos + 2).takeWhile { it.isLetterOrDigit() || it in "._-:" }
                        if (close != null && name == close) {
                            val gt = src.indexOf('>', pos)
                            if (gt < 0) { flush(); pos = src.length; return Children(nodes, End.EOF) }
                            flush()
                            pos = gt + 1
                            return Children(nodes, End.CLOSE_TAG)
                        }
                        if (name.isNotEmpty() && name in open) {
                            flush()
                            diagnostics += ParseDiagnostic("unclosed_element", "<$close> was closed by </$name>", pos)
                            return Children(nodes, End.PARENT_CLOSE)
                        }
                        if (name.isEmpty() && inExpression && startsWith("</>")) {
                            flush(); pos += 3; return Children(nodes, End.CLOSE_TAG)
                        }
                        if (name.isEmpty() && pos + 2 >= src.length) { flush(); pos = src.length; return Children(nodes, End.EOF) }
                        // A closing tag nothing opened: keep it as text.
                    } else {
                        val name = src.substring(pos + 1).takeWhile { it.isLetterOrDigit() || it in "._-:" }
                        val tagLike = name.isNotEmpty() && name[0].isLetter()
                        if (tagLike && (inExpression || isComponent(name))) {
                            flush()
                            val start = pos
                            val node = try {
                                element(inExpression)
                            } catch (e: IncompleteInput) {
                                diagnostics += ParseDiagnostic("unterminated_tag", "<$name> is incomplete", start)
                                if (rules.complete) {
                                    pos = start + 1
                                    text.append('<')
                                    continue
                                }
                                pos = src.length
                                null
                            } catch (e: ScriptSyntaxError) {
                                diagnostics += ParseDiagnostic("invalid_tag", e.message ?: "<$name> can't be read", start)
                                pos = start + 1
                                text.append('<')
                                continue
                            }
                            node?.let { nodes += it }
                            textStart = pos
                            if (pos >= src.length && node is ElementNode && node.autoClosed) return Children(nodes, End.EOF)
                            continue
                        }
                        // `<` right at the end might be the start of a tag still streaming in.
                        if (!rules.complete && (pos + 1 >= src.length || (tagLike && pos + 1 + name.length >= src.length))) {
                            flush()
                            if (name.isEmpty() || isComponentPrefix(name)) { pos = src.length; return Children(nodes, End.EOF) }
                        }
                    }
                }
                if (c == '{') {
                    val handled = brace(nodes, text, ::flush, close, inExpression)
                    if (handled != null) return handled
                    textStart = if (text.isEmpty()) pos else textStart
                    continue
                }
                text.append(c)
                pos++
            }
            flush()
            return Children(nodes, End.EOF)
        }

        private fun isComponentPrefix(name: String) = rules.isComponentPrefix(name)

        /**
         * Handles `{` at [pos]. Returns a [Children] result when the brace ends the current block
         * (`{:else}`, `{/if}`, `{/each}`), else null after consuming what it read.
         */
        private fun brace(nodes: MutableList<MarkupNode>, text: StringBuilder, flush: () -> Unit, close: String?, inExpression: Boolean): Children? {
            val start = pos
            when {
                startsWith("{@body") || startsWith("{@const") -> {
                    flush()
                    val keyword = if (startsWith("{@body")) "{@body" else "{@"
                    try {
                        sync(pos + keyword.length)
                        val statements = mutableListOf<Stmt>()
                        while (!isPunct("}")) {
                            if (tok.type == TokenType.EOF) throw IncompleteInput(tok.start, "Unclosed {@body}")
                            statements += parseStatement()
                        }
                        pos = tok.end
                        nodes += BodyNode(statements, start)
                    } catch (e: IncompleteInput) {
                        diagnostics += ParseDiagnostic("unterminated_braced_value", "{@body} statement is incomplete", start)
                        pos = if (rules.complete) skipBrace(start) else src.length
                    } catch (e: ScriptSyntaxError) {
                        diagnostics += ParseDiagnostic("invalid_statement", e.message ?: "Invalid statement", e.at)
                        pos = skipBrace(start)
                    }
                    return null
                }
                startsWith("{#if") -> {
                    flush()
                    nodes += ifBlock(start, inExpression) ?: return Children(nodes, End.EOF)
                    return null
                }
                startsWith("{#each") -> {
                    flush()
                    nodes += eachBlock(start, inExpression) ?: return Children(nodes, End.EOF)
                    return null
                }
                startsWith("{:else if") || startsWith("{:elseif") -> {
                    flush()
                    return try {
                        sync(pos + if (startsWith("{:else if")) 9 else 8)
                        val test = parseExpression()
                        if (!isPunct("}")) throw unexpected("Expected '}'")
                        pos = tok.end
                        Children(nodes, End.ELSE_IF, test)
                    } catch (e: IncompleteInput) {
                        pos = src.length
                        Children(nodes, End.EOF)
                    }
                }
                startsWith("{:else}") -> { flush(); pos += 7; return Children(nodes, End.ELSE) }
                startsWith("{/if}") -> { flush(); pos += 5; return Children(nodes, End.END_IF) }
                startsWith("{/each}") -> { flush(); pos += 7; return Children(nodes, End.END_EACH) }
                startsWith("{#") || startsWith("{:") || startsWith("{/") -> {
                    if (src.indexOf('}', pos) < 0) { flush(); pos = src.length; return Children(nodes, End.EOF) }
                    text.append('{'); pos++; return null
                }
                else -> {
                    // `{expression}`; text that doesn't parse as one stays literal.
                    try {
                        sync(pos + 1)
                        if (isPunct("}")) { text.append("{}"); pos = tok.end; return null }
                        val expr = parseExpression()
                        if (!isPunct("}")) throw ScriptSyntaxError(tok.start, "Expected '}'")
                        flush()
                        pos = tok.end
                        nodes += ExprNode(expr, start)
                    } catch (e: IncompleteInput) {
                        if (rules.complete) {
                            text.append('{')
                            pos = start + 1
                            return null
                        }
                        flush()
                        diagnostics += ParseDiagnostic("unterminated_braced_value", "A {…} value is incomplete", start)
                        pos = src.length
                        return Children(nodes, End.EOF)
                    } catch (e: ScriptSyntaxError) {
                        text.append('{')
                        pos = start + 1
                    }
                    return null
                }
            }
        }

        /** After a broken `{…}`, continues after its matching brace, or at the end. */
        private fun skipBrace(start: Int): Int {
            var depth = 0
            var i = start
            while (i < src.length) {
                when (src[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return i + 1 } }
                i++
            }
            return src.length
        }

        private fun ifBlock(start: Int, inExpression: Boolean): MarkupNode? {
            val branches = mutableListOf<IfBranch>()
            var test: Expr? = try {
                sync(pos + 4)
                val expr = parseExpression()
                if (!isPunct("}")) throw unexpected("Expected '}'")
                pos = tok.end
                expr
            } catch (e: IncompleteInput) {
                diagnostics += ParseDiagnostic("unterminated_braced_value", "{#if} condition is incomplete", start)
                pos = src.length
                return null
            }
            var elseSeen = false
            while (true) {
                val body = children(close = null, inExpression = inExpression).also { }
                branches += IfBranch(if (elseSeen) null else test, body.nodes)
                when (body.end) {
                    End.ELSE_IF -> { test = body.elseIf }
                    End.ELSE -> { elseSeen = true; test = null }
                    End.END_IF -> return IfNode(branches, start)
                    else -> {
                        diagnostics += ParseDiagnostic("unclosed_block", "{#if} has no {/if}", start)
                        if (body.end == End.EOF) pos = src.length
                        return IfNode(branches, start)
                    }
                }
            }
        }

        private fun eachBlock(start: Int, inExpression: Boolean): MarkupNode? {
            val list: Expr
            val item: Pattern
            var index: String? = null
            var key: Expr? = null
            try {
                sync(pos + 6)
                list = parseConditional(noIn = false)
                if (!isWord("as")) throw unexpected("Expected 'as'")
                advance()
                item = parseBindingTarget()
                if (eat(",")) index = identifier()
                if (eat("(")) { key = parseExpression(); expect(")") }
                if (!isPunct("}")) throw unexpected("Expected '}'")
                pos = tok.end
            } catch (e: IncompleteInput) {
                diagnostics += ParseDiagnostic("unterminated_braced_value", "{#each} header is incomplete", start)
                pos = src.length
                return null
            } catch (e: ScriptSyntaxError) {
                diagnostics += ParseDiagnostic("invalid_each", e.message ?: "Invalid {#each}", start)
                pos = skipBrace(start)
                return null
            }
            val body = children(close = null, inExpression = inExpression)
            var otherwise = emptyList<MarkupNode>()
            when (body.end) {
                End.END_EACH -> Unit
                End.ELSE -> {
                    val alt = children(close = null, inExpression = inExpression)
                    otherwise = alt.nodes
                    if (alt.end != End.END_EACH) diagnostics += ParseDiagnostic("unclosed_block", "{#each} has no {/each}", start)
                }
                else -> {
                    diagnostics += ParseDiagnostic("unclosed_block", "{#each} has no {/each}", start)
                    if (body.end == End.EOF) pos = src.length
                }
            }
            return EachNode(list, item, index, key, body.nodes, otherwise, start)
        }

        /** Reads `<name …>children</name>` or `<name …/>` at [pos]. Null when it can't be completed. */
        fun element(inExpression: Boolean): MarkupNode? {
            val start = pos
            pos++ // '<'
            if (pos < src.length && src[pos] == '>') {
                pos++
                open += ""
                val body = try { children(close = null, inExpression = true) } finally { open.removeAt(open.lastIndex) }
                return FragmentNode(body.nodes, start)
            }
            val name = src.substring(pos).takeWhile { it.isLetterOrDigit() || it in "._-:" }
            pos += name.length
            val attributes = mutableListOf<Attribute>()
            while (true) {
                while (pos < src.length && src[pos].isWhitespace()) pos++
                if (pos >= src.length) throw IncompleteInput(start, "Unterminated tag <$name>")
                if (startsWith("/>")) {
                    pos += 2
                    return if (isRawComponent(name)) RawNode(name, attributes, "", true, start) else ElementNode(name, attributes, emptyList(), false, start)
                }
                if (src[pos] == '>') { pos++; break }
                if (src[pos] == '{') {
                    val attrStart = pos
                    sync(pos + 1)
                    if (!eat("...")) throw ScriptSyntaxError(attrStart, "Expected '...' in attribute spread")
                    val value = parseAssign()
                    if (!isPunct("}")) throw unexpected("Expected '}'")
                    pos = tok.end
                    attributes += SpreadAttribute(value, attrStart)
                    continue
                }
                val attrStart = pos
                val attrName = src.substring(pos).takeWhile { it.isLetterOrDigit() || it in "_-:." || it == '$' }
                if (attrName.isEmpty()) throw ScriptSyntaxError(pos, "Unexpected '${src[pos]}' in <$name>")
                pos += attrName.length
                while (pos < src.length && src[pos].isWhitespace() && src[pos] != '\n') pos++
                if (pos < src.length && src[pos] == '=') {
                    pos++
                    while (pos < src.length && src[pos].isWhitespace()) pos++
                    if (pos >= src.length) throw IncompleteInput(start, "Unterminated tag <$name>")
                    when (val q = src[pos]) {
                        '"', '\'' -> {
                            val end = src.indexOf(q, pos + 1)
                            if (end < 0) throw IncompleteInput(start, "Unterminated attribute in <$name>")
                            attributes += NamedAttribute(attrName, StringLit(decodeEntities(src.substring(pos + 1, end)), pos), literal = true, pos = attrStart)
                            pos = end + 1
                        }
                        '{' -> {
                            sync(pos + 1)
                            val value = parseExpression()
                            if (!isPunct("}")) throw unexpected("Expected '}'")
                            pos = tok.end
                            attributes += NamedAttribute(attrName, value, literal = isLiteral(value), pos = attrStart)
                        }
                        else -> {
                            val raw = src.substring(pos).takeWhile { !it.isWhitespace() && it != '>' && !(it == '/' ) }
                            pos += raw.length
                            attributes += NamedAttribute(attrName, StringLit(raw, attrStart), literal = true, pos = attrStart)
                        }
                    }
                } else {
                    attributes += NamedAttribute(attrName, BoolLit(true, attrStart), literal = true, pos = attrStart)
                }
            }
            if (rules.isVoid(name)) {
                // HTML-style `<divider>`: no children; skip a stray `</divider>` right after it.
                var after = pos
                while (after < src.length && src[after].isWhitespace() && src[after] != '\n') after++
                if (src.startsWith("</$name>", after)) pos = after + name.length + 3
                return ElementNode(name, attributes, emptyList(), false, start)
            }
            if (isRawComponent(name)) {
                val closing = "</$name>"
                val end = src.indexOf(closing, pos)
                return if (end < 0) {
                    val raw = src.substring(pos)
                    pos = src.length
                    RawNode(name, attributes, raw, complete = false, pos = start)
                } else {
                    val raw = src.substring(pos, end)
                    pos = end + closing.length
                    RawNode(name, attributes, raw, complete = true, pos = start)
                }
            }
            open += name
            val body = try { children(close = name, inExpression = inExpression) } finally { open.removeAt(open.lastIndex) }
            return when (body.end) {
                End.CLOSE_TAG -> ElementNode(name, attributes, body.nodes, autoClosed = false, pos = start)
                End.EOF -> {
                    // Streaming: close it if it already shows something, drop it otherwise.
                    val hasContent = body.nodes.any { it !is TextNode || it.staticText.isNotBlank() }
                    if (!hasContent && !rules.complete) {
                        diagnostics += ParseDiagnostic("unterminated_tag", "<$name> has no content yet", start)
                        null
                    } else {
                        diagnostics += ParseDiagnostic("unclosed_element", "<$name> was closed automatically", start)
                        ElementNode(name, attributes, body.nodes, autoClosed = true, pos = start)
                    }
                }
                else -> {
                    diagnostics += ParseDiagnostic("unclosed_element", "<$name> was closed automatically", start)
                    ElementNode(name, attributes, body.nodes, autoClosed = true, pos = start)
                }
            }
        }

        private fun isLiteral(expr: Expr): Boolean = when (expr) {
            is NumberLit, is StringLit, is BoolLit, is NullLit -> true
            is UnaryExpr -> expr.op == "-" && expr.arg is NumberLit
            is ArrayLit -> expr.elements.all { it != null && isLiteral(it) }
            is ObjectLit -> expr.members.all { it is PropertyMember && !it.computed && isLiteral(it.value) }
            else -> false
        }
    }

    companion object {
        private val REGEX_KEYWORDS = setOf("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield", "await")
        val RESERVED = setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else", "export",
            "extends", "finally", "for", "function", "if", "import", "in", "instanceof", "new", "return", "super", "switch",
            "this", "throw", "try", "typeof", "var", "void", "while", "with", "null", "true", "false",
        )
        private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=", "%=", "**=", "<<=", ">>=", ">>>=", "&=", "|=", "^=", "&&=", "||=", "??=")
        private val BINARY_PRECEDENCE = mapOf(
            "??" to 1, "||" to 2, "&&" to 3, "|" to 4, "^" to 5, "&" to 6,
            "==" to 7, "!=" to 7, "===" to 7, "!==" to 7,
            "<" to 8, ">" to 8, "<=" to 8, ">=" to 8,
            "<<" to 9, ">>" to 9, ">>>" to 9,
            "+" to 10, "-" to 10, "*" to 11, "/" to 11, "%" to 11, "**" to 12,
        )

        private val ENTITIES = mapOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ")
        fun decodeEntities(text: String): String = if ('&' !in text) text else ENTITIES.entries.fold(text) { acc, (k, v) -> acc.replace(k, v) }
    }
}
