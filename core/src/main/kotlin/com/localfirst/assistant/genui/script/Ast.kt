package com.localfirst.assistant.genui.script

/**
 * Syntax tree for FRIDAY's JavaScript subset and the markup it is embedded in. [pos] is the source
 * offset, for diagnostics.
 */
sealed interface Node { val pos: Int }

// ---- expressions -------------------------------------------------------------------

sealed interface Expr : Node

data class NumberLit(val value: Double, override val pos: Int) : Expr
data class StringLit(val value: String, override val pos: Int) : Expr
data class BoolLit(val value: Boolean, override val pos: Int) : Expr
data class NullLit(override val pos: Int) : Expr
data class UndefinedLit(override val pos: Int) : Expr
data class RegexLit(val pattern: String, val flags: String, override val pos: Int) : Expr
data class TemplateLit(val quasis: List<String>, val exprs: List<Expr>, override val pos: Int) : Expr
data class Ident(val name: String, override val pos: Int) : Expr
data class ThisExpr(override val pos: Int) : Expr

/** `...expr` inside an array, call or object. */
data class SpreadElement(val arg: Expr, override val pos: Int) : Expr

data class ArrayLit(val elements: List<Expr?>, override val pos: Int) : Expr

sealed interface ObjectMember
data class PropertyMember(val key: Expr, val computed: Boolean, val value: Expr) : ObjectMember
data class SpreadMember(val arg: Expr) : ObjectMember
data class ObjectLit(val members: List<ObjectMember>, override val pos: Int) : Expr

data class Param(val target: Pattern, val default: Expr?, val rest: Boolean)

data class FunctionExpr(
    val name: String?,
    val params: List<Param>,
    /** A block, or an expression body for concise arrows. */
    val body: Node,
    val arrow: Boolean,
    val async: Boolean,
    override val pos: Int,
) : Expr

data class UnaryExpr(val op: String, val arg: Expr, override val pos: Int) : Expr
data class UpdateExpr(val op: String, val prefix: Boolean, val target: Expr, override val pos: Int) : Expr
data class BinaryExpr(val op: String, val left: Expr, val right: Expr, override val pos: Int) : Expr
data class LogicalExpr(val op: String, val left: Expr, val right: Expr, override val pos: Int) : Expr
data class ConditionalExpr(val test: Expr, val then: Expr, val otherwise: Expr, override val pos: Int) : Expr
data class AssignExpr(val op: String, val target: Pattern, val value: Expr, override val pos: Int) : Expr
data class SequenceExpr(val exprs: List<Expr>, override val pos: Int) : Expr
data class CallExpr(val callee: Expr, val args: List<Expr>, val optional: Boolean, override val pos: Int) : Expr
data class NewExpr(val callee: Expr, val args: List<Expr>, override val pos: Int) : Expr
data class MemberExpr(val obj: Expr, val property: Expr, val computed: Boolean, val optional: Boolean, override val pos: Int) : Expr
data class AwaitExpr(val arg: Expr, override val pos: Int) : Expr

/** Markup inside an expression: `items.map(i => <chip label={i}/>)`. */
data class MarkupExpr(val node: MarkupNode, override val pos: Int) : Expr

// ---- patterns (destructuring and assignment targets) ----------------------------------

sealed interface Pattern : Node
data class IdentPattern(val name: String, override val pos: Int) : Pattern
data class ExprPattern(val expr: Expr, override val pos: Int) : Pattern
data class ObjectPattern(val props: List<ObjectPatternProp>, val rest: String?, override val pos: Int) : Pattern
data class ObjectPatternProp(val key: Expr, val computed: Boolean, val value: Pattern, val default: Expr?)
data class ArrayPattern(val elements: List<ArrayPatternElement?>, val rest: Pattern?, override val pos: Int) : Pattern
data class ArrayPatternElement(val target: Pattern, val default: Expr?)

// ---- statements -------------------------------------------------------------------------

sealed interface Stmt : Node

data class VarDecl(val kind: String, val declarations: List<Declarator>, override val pos: Int) : Stmt
data class Declarator(val target: Pattern, val init: Expr?)
data class FunctionDecl(val function: FunctionExpr, override val pos: Int) : Stmt
data class ExprStmt(val expr: Expr, override val pos: Int) : Stmt
data class BlockStmt(val body: List<Stmt>, override val pos: Int) : Stmt
data class ReturnStmt(val value: Expr?, override val pos: Int) : Stmt
data class IfStmt(val test: Expr, val then: Stmt, val otherwise: Stmt?, override val pos: Int) : Stmt
data class ForStmt(val init: Node?, val test: Expr?, val update: Expr?, val body: Stmt, override val pos: Int) : Stmt
data class ForOfStmt(val kind: String?, val target: Pattern, val iterable: Expr, val body: Stmt, val forIn: Boolean, override val pos: Int) : Stmt
data class WhileStmt(val test: Expr, val body: Stmt, val doWhile: Boolean, override val pos: Int) : Stmt
data class BreakStmt(val label: String?, override val pos: Int) : Stmt
data class ContinueStmt(val label: String?, override val pos: Int) : Stmt
data class ThrowStmt(val value: Expr, override val pos: Int) : Stmt
data class TryStmt(val block: BlockStmt, val param: Pattern?, val handler: BlockStmt?, val finalizer: BlockStmt?, override val pos: Int) : Stmt
data class SwitchStmt(val discriminant: Expr, val cases: List<SwitchCase>, override val pos: Int) : Stmt
data class SwitchCase(val test: Expr?, val body: List<Stmt>)
data class LabeledStmt(val label: String, val body: Stmt, override val pos: Int) : Stmt
data class EmptyStmt(override val pos: Int) : Stmt

// ---- markup (DIL) ------------------------------------------------------------------------------

sealed interface MarkupNode : Node

/** Markdown text with `{expression}` holes. [parts] alternate between Strings and Exprs. */
data class TextNode(val parts: List<Any>, override val pos: Int) : MarkupNode {
    val isStatic get() = parts.all { it is String }
    val staticText get() = parts.filterIsInstance<String>().joinToString("")
}

sealed interface Attribute
data class NamedAttribute(val name: String, val value: Expr, val literal: Boolean, val pos: Int) : Attribute
data class SpreadAttribute(val value: Expr, val pos: Int) : Attribute

data class ElementNode(
    val tag: String,
    val attributes: List<Attribute>,
    val children: List<MarkupNode>,
    /** Closed by the compiler because the response ended (streaming) or a closing tag was missing. */
    val autoClosed: Boolean,
    override val pos: Int,
) : MarkupNode

data class FragmentNode(val children: List<MarkupNode>, override val pos: Int) : MarkupNode

/** `{expression}` whose value is markup or text. */
data class ExprNode(val expr: Expr, override val pos: Int) : MarkupNode

/** `{@body statements}`: component logic, run before the markup renders. */
data class BodyNode(val statements: List<Stmt>, override val pos: Int) : MarkupNode

data class IfBranch(val test: Expr?, val children: List<MarkupNode>)
data class IfNode(val branches: List<IfBranch>, override val pos: Int) : MarkupNode

data class EachNode(
    val list: Expr,
    val item: Pattern,
    val index: String?,
    val key: Expr?,
    val children: List<MarkupNode>,
    val otherwise: List<MarkupNode>,
    override val pos: Int,
) : MarkupNode

/** `<AppBlock …>raw html</AppBlock>`: kept verbatim for the web sandbox. */
data class RawNode(val tag: String, val attributes: List<Attribute>, val raw: String, val complete: Boolean, override val pos: Int) : MarkupNode
