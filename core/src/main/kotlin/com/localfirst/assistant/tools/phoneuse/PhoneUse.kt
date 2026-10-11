package com.localfirst.assistant.tools.phoneuse

import com.localfirst.assistant.conversation.Attachment

/**
 * Phone use in the style of Codex's Computer Use plugin: the model works inside one app at a time,
 * reads it as an indexed accessibility tree plus a screenshot ([AppSnapshot]), and acts on elements
 * by index (or on screenshot pixels). Every action returns the app's fresh state, so the model always
 * sees what its action did. Android specifics live behind [PhoneUseDriver]; this package is plain
 * Kotlin and unit-tested with a fake driver.
 */
data class PhoneApp(
    val label: String,
    val packageName: String,
    /** Running now or recently used, when Android reports it. */
    val recent: Boolean = false,
)

/** Element bounds in the snapshot's coordinate space (screenshot pixels, origin top-left). */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
}

/**
 * One element of the app's accessibility tree. [index] is valid only for the snapshot it came from:
 * every action returns a new snapshot with new indices.
 */
data class AppElement(
    val index: Int,
    /** Plain role: button, text field, text, image, checkbox, switch, slider, list, group, … */
    val role: String,
    val label: String? = null,
    /** Current text of a field, a slider's value, or a selected option. Never a password. */
    val value: String? = null,
    /** focused, selected, checked, unchecked, disabled, settable, scrollable, password, expanded, collapsed. */
    val states: List<String> = emptyList(),
    /** Named non-primary actions, used with perform_secondary_action. */
    val secondaryActions: List<String> = emptyList(),
    val bounds: Bounds? = null,
    val children: List<AppElement> = emptyList(),
)

data class AppSnapshot(
    /** The app in the foreground when the snapshot was taken. */
    val app: PhoneApp,
    val windowTitle: String? = null,
    val elements: List<AppElement>,
    /** Coordinate space of [AppElement.bounds] and x/y action arguments. */
    val width: Int,
    val height: Int,
    val displayWidth: Int = width,
    val displayHeight: Int = height,
    val screenshot: Attachment? = null,
    /** Why there is no screenshot, when there isn't one. */
    val screenshotNote: String? = null,
    /** The on-screen keyboard's name when it is showing. */
    val keyboard: String? = null,
    /** Other windows over the app: dialogs, the notification shade, system prompts. */
    val otherWindows: List<String> = emptyList(),
    /** Elements were left out to keep the tree readable. */
    val truncated: Boolean = false,
) {
    fun element(index: Int): AppElement? {
        fun find(list: List<AppElement>): AppElement? {
            for (element in list) {
                if (element.index == index) return element
                find(element.children)?.let { return it }
            }
            return null
        }
        return find(elements)
    }

    val elementCount: Int get() {
        fun count(list: List<AppElement>): Int = list.sumOf { 1 + count(it.children) }
        return count(elements)
    }
}

enum class MouseButton(val wireName: String) { LEFT("left"), RIGHT("right"), MIDDLE("middle") }

enum class ScrollDirection(val wireName: String) { UP("up"), DOWN("down"), LEFT("left"), RIGHT("right") }

/** Where a click lands: an element of the latest snapshot, or a point in its coordinate space. */
sealed interface Target {
    data class Element(val index: Int) : Target
    data class Point(val x: Int, val y: Int) : Target
}

/**
 * Keys press_key understands. Names follow xdotool, as Codex's press_key does ("Return", "BackSpace",
 * "ctrl+a"), with Android's own keys added.
 */
enum class PhoneKey(val wireName: String, vararg val aliases: String) {
    ENTER("Return", "enter", "kp_enter", "ime_enter"),
    BACKSPACE("BackSpace", "backspace", "delete_backward"),
    DELETE("Delete", "del", "delete_forward"),
    TAB("Tab"),
    ESCAPE("Escape", "esc"),
    SPACE("space", "spacebar"),
    UP("Up", "arrow_up", "dpad_up"),
    DOWN("Down", "arrow_down", "dpad_down"),
    LEFT("Left", "arrow_left", "dpad_left"),
    RIGHT("Right", "arrow_right", "dpad_right"),
    PAGE_UP("Page_Up", "pageup", "prior"),
    PAGE_DOWN("Page_Down", "pagedown", "next"),
    LINE_START("line_start", "kp_home"),
    LINE_END("End", "line_end", "kp_end"),
    SELECT_ALL("ctrl+a", "select_all", "cmd+a"),
    COPY("ctrl+c", "copy", "cmd+c"),
    CUT("ctrl+x", "cut", "cmd+x"),
    PASTE("ctrl+v", "paste", "cmd+v"),
    BACK("Back", "android_back", "browser_back", "alt+left"),
    HOME("Home", "android_home", "super"),
    RECENTS("Recents", "app_switch", "overview"),
    NOTIFICATIONS("Notifications", "notification_shade"),
    QUICK_SETTINGS("Quick_Settings", "quicksettings"),
    SEARCH("Search");

    companion object {
        private fun norm(value: String) = value.trim().lowercase().replace(' ', '_').replace('-', '_')

        fun parse(name: String): PhoneKey? {
            val wanted = norm(name)
            return entries.firstOrNull { key -> norm(key.wireName) == wanted || key.aliases.any { norm(it) == wanted } }
        }

        val names: List<String> get() = entries.map { it.wireName }
    }
}

/** Android side of phone use. All coordinates are in the latest snapshot's coordinate space. */
interface PhoneUseDriver {
    /** Launchable apps, recently used first when known. */
    suspend fun listApps(): List<PhoneApp>

    /** The package currently in the foreground, or null when unknown. */
    suspend fun foregroundPackage(): String?

    /** Brings [app] to the foreground (launching it if needed) without taking a snapshot. */
    suspend fun open(app: PhoneApp)

    /**
     * Waits for the UI to settle, then reads the foreground app. [screenshot] false skips the image.
     * Indices in the returned snapshot are what element targets refer to until the next snapshot.
     */
    suspend fun snapshot(screenshot: Boolean): AppSnapshot

    /** Returns a short past-tense description of what was done, e.g. "Clicked button “Send”.". */
    suspend fun click(target: Target, clickCount: Int, button: MouseButton): String
    suspend fun secondaryAction(element: Int, action: String): String
    suspend fun scroll(element: Int?, direction: ScrollDirection, pages: Double): String
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): String
    suspend fun typeText(text: String): String
    suspend fun pressKey(key: PhoneKey): String
    suspend fun setValue(element: Int, value: String): String

    /** The turn ended: hide the on-screen indicator and release references to the screen. */
    suspend fun endSession() {}

    /** Shows which app FRIDAY is working in, with a Stop button. Null hides it. */
    fun showActivity(app: PhoneApp?) {}
}

/** Whether FRIDAY may operate an app. */
enum class AppAccess { ALLOW, ASK, DENY }

/** The user's answer when FRIDAY asks to use an app. */
enum class AccessDecision { DENY, ALLOW_FOR_CONVERSATION, ALWAYS_ALLOW }

/** The user's saved per-app rules. */
interface AppAccessStore {
    /** What applies to apps without a rule: [AppAccess.ASK] (default) or [AppAccess.ALLOW]. */
    val defaultAccess: AppAccess
    fun rule(packageName: String): AppAccess?
    fun setRule(packageName: String, access: AppAccess?)
}

class InMemoryAppAccessStore(
    override var defaultAccess: AppAccess = AppAccess.ASK,
    rules: Map<String, AppAccess> = emptyMap(),
) : AppAccessStore {
    private val rules = rules.toMutableMap()
    override fun rule(packageName: String) = rules[packageName]
    override fun setRule(packageName: String, access: AppAccess?) {
        if (access == null) rules.remove(packageName) else rules[packageName] = access
    }
}

/**
 * Apps FRIDAY never operates, whatever the settings say: itself, and the system screens that grant
 * permissions or install software. These are hand-offs: the model asks the user to do them.
 */
object ProtectedApps {
    val SYSTEM = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** Windows that belong to the phone, not an app: the keyboard, system UI, the launcher's overlays. */
    fun isTransient(packageName: String?): Boolean = packageName != null && (
        packageName == "com.android.systemui" ||
            packageName.contains("inputmethod") ||
            packageName == "com.google.android.inputmethod.latin" ||
            packageName == "com.samsung.android.honeyboard" ||
            packageName == "com.touchtype.swiftkey"
        )
}
