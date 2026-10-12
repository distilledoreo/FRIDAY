package com.localfirst.assistant.tools.phoneuse

/**
 * Makes a snapshot's tree cheaper to read without losing anything the model can act on. Web views
 * are the usual offenders: every node offers "Context menu", and pages nest each control in several
 * unlabeled containers.
 */
object AppTree {
    /** Unlabeled elements of these roles are only layout: their children take their place. */
    private val WRAPPERS = setOf("text", "group", "view", "layout")
    private const val COMMON_MIN = 8

    fun simplify(snapshot: AppSnapshot): AppSnapshot {
        val all = mutableListOf<AppElement>()
        fun collect(list: List<AppElement>) { list.forEach { all += it; collect(it.children) } }
        collect(snapshot.elements)
        val common = all.flatMap { it.secondaryActions.distinct() }.groupingBy { it }.eachCount()
            // A lifted action is advertised for every element, so it must actually be universal.
            .filter { (_, n) -> n >= COMMON_MIN && n == all.size }.keys
        fun clean(element: AppElement, top: Boolean): List<AppElement> {
            val children = element.children.flatMap { clean(it, top = false) }
            val actions = element.secondaryActions.filter { it !in common }
            val bare = !top && element.label == null && element.value == null && element.states.isEmpty() &&
                element.secondaryActions.isEmpty() && snapshot.commonActions.isEmpty() && element.role in WRAPPERS
            return if (bare) children else listOf(element.copy(secondaryActions = actions, children = children))
        }
        return snapshot.copy(
            elements = snapshot.elements.flatMap { clean(it, top = true) },
            commonActions = (snapshot.commonActions + common).distinct(),
        )
    }
}
