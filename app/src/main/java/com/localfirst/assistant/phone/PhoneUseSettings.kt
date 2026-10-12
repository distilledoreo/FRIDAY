package com.localfirst.assistant.phone

import android.content.Context
import com.localfirst.assistant.tools.phoneuse.AccessDecision
import com.localfirst.assistant.tools.phoneuse.AppAccess
import com.localfirst.assistant.tools.phoneuse.AppAccessStore
import com.localfirst.assistant.tools.phoneuse.PhoneApp
import com.localfirst.assistant.tools.phoneuse.PhoneUseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Settings > Phone use shows and changes. */
data class PhoneUsePreferences(
    /** Apps without a rule: ask first (default) or allow. */
    val askByDefault: Boolean = true,
    /** Include a screenshot with every app state, as Codex does. Off sends only the element tree. */
    val screenshots: Boolean = true,
    /** Ask before taps that send, buy, delete or otherwise commit something. */
    val reviewActions: Boolean = true,
    /** Saved per-app rules, by package. */
    val rules: Map<String, AppAccess> = emptyMap(),
)

/** Phone-use settings in SharedPreferences, also the [AppAccessStore] the sessions consult. One per process: [get]. */
class PhoneUseSettings private constructor(context: Context) : AppAccessStore {
    private val prefs = context.applicationContext.getSharedPreferences("phone_use", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<PhoneUsePreferences> = _state.asStateFlow()

    private fun load() = PhoneUsePreferences(
        askByDefault = prefs.getBoolean("ask_by_default", true),
        screenshots = prefs.getBoolean("screenshots", true),
        reviewActions = prefs.getBoolean("review_actions", true),
        rules = prefs.getStringSet("rules", emptySet()).orEmpty().mapNotNull { entry ->
            val (pkg, access) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            runCatching { pkg to AppAccess.valueOf(access) }.getOrNull()
        }.toMap(),
    )

    fun update(change: (PhoneUsePreferences) -> PhoneUsePreferences) {
        val next = change(_state.value)
        prefs.edit()
            .putBoolean("ask_by_default", next.askByDefault)
            .putBoolean("screenshots", next.screenshots)
            .putBoolean("review_actions", next.reviewActions)
            .putStringSet("rules", next.rules.map { (pkg, access) -> "$pkg=${access.name}" }.toSet())
            .apply()
        _state.value = next
    }

    companion object {
        @Volatile private var instance: PhoneUseSettings? = null
        fun get(context: Context): PhoneUseSettings = instance ?: synchronized(this) {
            instance ?: PhoneUseSettings(context.applicationContext).also { instance = it }
        }
    }

    override val defaultAccess: AppAccess get() = if (_state.value.askByDefault) AppAccess.ASK else AppAccess.ALLOW
    override fun rule(packageName: String): AppAccess? = _state.value.rules[packageName]
    override fun setRule(packageName: String, access: AppAccess?) =
        update { it.copy(rules = if (access == null) it.rules - packageName else it.rules + (packageName to access)) }
}

/**
 * One phone-use session per conversation, created by the chat. Approval questions go through the
 * chat's approval card; [current] is what the phone-use tools act through.
 */
class PhoneUseController(
    private val context: Context,
    val settings: PhoneUseSettings,
) {
    @Volatile var current: PhoneUseSession? = null
        private set

    /** Starts a fresh session for a new or reopened conversation. */
    fun newConversation(
        askAccess: suspend (PhoneApp) -> AccessDecision,
        reviewAction: suspend (PhoneApp, String) -> Boolean,
    ) {
        current = PhoneUseSession(
            access = settings,
            askAccess = askAccess,
            ownPackage = context.packageName,
            reviewAction = { app, text -> if (settings.state.value.reviewActions) reviewAction(app, text) else true },
        )
    }

    /** The session tools should use, or null when the accessibility service is off. */
    fun active(): PhoneUseSession? = current?.takeIf { PhoneControlAccessibilityService.instance() != null }
}
