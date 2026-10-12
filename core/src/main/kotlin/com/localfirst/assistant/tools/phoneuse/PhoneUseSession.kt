package com.localfirst.assistant.tools.phoneuse

/**
 * Per-conversation phone-use state: which apps the user allowed for this chat, the app FRIDAY is
 * working in, and the latest snapshot. Like Codex, get_app_state must run in the current turn before
 * acting on an app, so element indices always come from a screen the model saw this turn.
 */
class PhoneUseSession(
    private val access: AppAccessStore,
    /** Asks the user whether FRIDAY may use an app. */
    private val askAccess: suspend (PhoneApp) -> AccessDecision,
    /** FRIDAY's own package: never operated. */
    private val ownPackage: String,
    /** Asks the user before a consequential tap (send, buy, delete…). Null skips the review. */
    private val reviewAction: (suspend (PhoneApp, String) -> Boolean)? = null,
) {
    private val conversationGrants = mutableSetOf<String>()
    private var snapshotTurn = -1L
    /** The screen is known to have changed since [snapshot]: indices and coordinates no longer apply. */
    private var stale = false
    private var lastAction: String? = null
    /** How many times in a row [lastAction] ran without changing anything. */
    private var unchanged = 0

    var turn = 0L
        private set
    var current: PhoneApp? = null
        private set
    var snapshot: AppSnapshot? = null
        private set

    /** A new user turn: the model has to look at the app again before acting. */
    fun beginTurn() { turn++; forgetActions() }

    /** The turn ended (finished, stopped or failed). */
    fun endTurn() {
        snapshot = null
        snapshotTurn = -1
        forgetActions()
    }

    private fun forgetActions() { lastAction = null; unchanged = 0 }

    /** What applies to [app] right now, without asking. */
    fun effectiveAccess(app: PhoneApp): AppAccess = when {
        app.packageName == ownPackage || app.packageName in ProtectedApps.SYSTEM -> AppAccess.DENY
        app.packageName in conversationGrants -> AppAccess.ALLOW
        else -> access.rule(app.packageName) ?: access.defaultAccess
    }

    /** Null when FRIDAY may use [app]; otherwise the reason it may not, for the model. */
    suspend fun authorize(app: PhoneApp): String? {
        if (app.packageName == ownPackage) return "FRIDAY can't operate its own app. Answer the user in chat instead."
        if (app.packageName in ProtectedApps.SYSTEM) {
            return "${app.label} grants permissions or installs software, so FRIDAY never operates it. Ask the user to finish this step themselves, then continue."
        }
        return when (effectiveAccess(app)) {
            AppAccess.ALLOW -> null
            AppAccess.DENY -> "The user doesn't allow FRIDAY to use ${app.label}. Don't try to reach it another way; tell the user, and ask them to change it in Settings > Phone use if they want."
            AppAccess.ASK -> when (askAccess(app)) {
                AccessDecision.DENY -> "The user declined FRIDAY's request to use ${app.label}. Don't retry or use another app to do the same thing unless they ask."
                AccessDecision.ALLOW_FOR_CONVERSATION -> { conversationGrants += app.packageName; null }
                AccessDecision.ALWAYS_ALLOW -> { access.setRule(app.packageName, AppAccess.ALLOW); null }
            }
        }
    }

    fun record(snapshot: AppSnapshot) {
        this.snapshot = snapshot
        this.current = snapshot.app
        snapshotTurn = turn
        stale = false
    }

    /** Something showed the screen changed under the latest snapshot. */
    fun markStale() { stale = true }

    /**
     * Null when [action] may run. An identical action that already ran twice in a row without changing
     * anything is refused: repeating it can't work, and a third try is how agents get stuck. Typing the
     * same key twice is fine, because each press changes the screen.
     */
    fun repeatRefusal(action: String, app: PhoneApp): String? =
        if (action == lastAction && unchanged >= MAX_UNCHANGED_REPEATS)
            "Not done: this exact action already ran $unchanged times in a row without changing anything in ${app.label}. " +
                "Look at the state for what is in the way (a message, a full or disabled field, a dialog, a page still loading) and try " +
                "something different, or tell the user what is blocking you."
        else null

    /** Records an action that ran and whether the app's state changed after it. */
    fun noteAction(action: String, changed: Boolean) {
        unchanged = when {
            changed -> 0
            action == lastAction -> unchanged + 1
            else -> 1
        }
        lastAction = action
    }

    /** Null when actions on [app] may use the latest snapshot; otherwise what the model must do first. */
    fun requireFresh(app: PhoneApp): String? {
        val latest = snapshot
        return when {
            latest == null || snapshotTurn != turn ->
                "Call get_app_state for ${app.label} first. It must run once in each turn before acting on an app, so element indices match the current screen."
            latest.app.packageName != app.packageName ->
                "FRIDAY's latest look was at ${latest.app.label}, not ${app.label}. Call get_app_state for ${app.label} first."
            stale -> "The screen of ${app.label} changed since FRIDAY last looked, so indices and coordinates no longer apply. Call get_app_state for ${app.label} first."
            else -> null
        }
    }

    /** True when the tap may go ahead; asks the user for consequential ones. */
    suspend fun review(app: PhoneApp, description: String): Boolean = reviewAction?.invoke(app, description) ?: true

    companion object {
        /** Identical actions in a row that changed nothing, before another identical one is refused. */
        const val MAX_UNCHANGED_REPEATS = 2
    }
}

/**
 * Taps that commit something outside the phone (send, pay, delete…) get a confirmation card, like
 * Codex's action review for computer use. Deterministic: the element's own words decide, never the
 * model's description of its intent.
 */
object ConsequentialActions {
    private val verbs = listOf(
        "send", "post", "publish", "reply all", "pay", "buy", "purchase", "order", "place order", "checkout", "check out",
        "subscribe", "donate", "transfer", "delete", "erase", "uninstall", "reset", "wipe", "factory reset", "submit",
        "confirm", "accept", "agree", "install", "book", "reserve", "call", "sign out", "log out", "empty trash", "remove account",
        "report", "block", "decline", "end call",
    )
    private val pattern = Regex("^(" + verbs.joinToString("|") { Regex.escape(it) } + ")\\b", RegexOption.IGNORE_CASE)

    /** The words that make the element consequential, or null for an everyday tap. */
    fun check(element: AppElement?): String? {
        val label = element?.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (element.role !in setOf("button", "image button", "menu item", "link", "text", "group", "list item")) return null
        return pattern.find(label)?.value
    }
}
