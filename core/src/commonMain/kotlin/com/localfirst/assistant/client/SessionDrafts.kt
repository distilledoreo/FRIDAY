package com.localfirst.assistant.client

/** Session-only unsent work. Platform hosts own the attachment objects and their cleanup. */
data class ComposerDraft<A>(val text: String = "", val attachments: List<A> = emptyList(), val imageMode: Boolean = false)
data class DraftSnapshot<A>(val composer: ComposerDraft<A>, val editingIndex: Int? = null, val beforeEdit: ComposerDraft<A>? = null)

/** Saved chats use stable IDs; unnamed chats are scoped to their selected project. */
class SessionDrafts<A> {
    private val saved = mutableMapOf<Pair<String?, String?>, DraftSnapshot<A>>()
    private fun key(id: String?, project: String?) = id to project.takeIf { id == null }
    fun put(id: String?, project: String?, snapshot: DraftSnapshot<A>) { saved[key(id, project)] = snapshot }
    fun take(id: String?, project: String?): DraftSnapshot<A>? = saved.remove(key(id, project))
    fun remove(id: String?, project: String?): DraftSnapshot<A>? = saved.remove(key(id, project))
    fun clear(): List<DraftSnapshot<A>> = saved.values.toList().also { saved.clear() }
    fun attachments(): List<A> = saved.values.flatMap { it.composer.attachments + it.beforeEdit?.attachments.orEmpty() }

    /** Imports may complete while their owner is parked or hidden behind an edit. */
    fun updateAttachment(matches: (A) -> Boolean, update: (A) -> A): Boolean {
        var found = false
        fun changed(draft: ComposerDraft<A>) = draft.copy(attachments = draft.attachments.map {
            if (matches(it)) { found = true; update(it) } else it
        })
        saved.keys.toList().forEach { key ->
            val snapshot = saved.getValue(key)
            saved[key] = snapshot.copy(composer = changed(snapshot.composer), beforeEdit = snapshot.beforeEdit?.let(::changed))
        }
        return found
    }
}
