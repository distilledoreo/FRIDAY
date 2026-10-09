package com.localfirst.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localfirst.assistant.tools.phone.GlobalPhoneAction
import com.localfirst.assistant.tools.phone.PhoneAccessibilityActions
import com.localfirst.assistant.tools.phone.PhoneActionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** User-enabled bridge for screen reading and input. No work is performed from accessibility events. */
class PhoneControlAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }

    private suspend fun readScreen(): String = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: throw PhoneActionException("No active phone screen is available. Open the app you want FRIDAY to inspect, then try again.")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val lines = mutableListOf<String>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++
            if (node.isPassword) return
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                ?.replace('\n', ' ')?.trim()?.takeIf(String::isNotEmpty)
                ?: "[unlabeled clickable]".takeIf { node.isClickable }
            if (label != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val x1 = (bounds.left * 1000 / width).coerceIn(0, 1000)
                val y1 = (bounds.top * 1000 / height).coerceIn(0, 1000)
                val x2 = (bounds.right * 1000 / width).coerceIn(0, 1000)
                val y2 = (bounds.bottom * 1000 / height).coerceIn(0, 1000)
                val flags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                }.joinToString(", ").ifEmpty { "text" }
                lines += "- $label [$flags, bounds=($x1,$y1)-($x2,$y2)]"
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }

        try { visit(root, 0) } finally { root.recycle() }
        if (lines.isEmpty()) "The current screen exposes no readable text." else lines.joinToString("\n").take(MAX_TEXT_CHARS)
    }

    private suspend fun tap(x: Int, y: Int): String = gesture(x, y, x, y, 60, "Tapped the phone screen.")

    private suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int): String =
        gesture(fromX, fromY, toX, toY, durationMillis, "Swiped the phone screen.")

    private suspend fun gesture(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int, success: String): String =
        withContext(Dispatchers.Main.immediate) {
            val metrics = resources.displayMetrics
            val startX = x1.coerceIn(0, 1000) * (metrics.widthPixels - 1).coerceAtLeast(1) / 1000f
            val startY = y1.coerceIn(0, 1000) * (metrics.heightPixels - 1).coerceAtLeast(1) / 1000f
            val endX = x2.coerceIn(0, 1000) * (metrics.widthPixels - 1).coerceAtLeast(1) / 1000f
            val endY = y2.coerceIn(0, 1000) * (metrics.heightPixels - 1).coerceAtLeast(1) / 1000f
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            val description = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(60, 2_000).toLong()))
                .build()
            val result = CompletableDeferred<Boolean>()
            val accepted = dispatchGesture(description, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
            }, null)
            if (!accepted) throw PhoneActionException("Android rejected that screen gesture.")
            if (withTimeoutOrNull(3_000) { result.await() } != true) throw PhoneActionException("The screen gesture did not complete.")
            success
        }

    private suspend fun enterText(text: String): String = withContext(Dispatchers.Main.immediate) {
        val root = rootInActiveWindow ?: throw PhoneActionException("No active phone screen is available.")
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw PhoneActionException("Focus a text field on the phone first.")
            try {
                if (!focused.isEditable || focused.isPassword) throw PhoneActionException("The focused field is not an editable non-password field.")
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                if (!focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    throw PhoneActionException("Android could not replace the focused field's text.")
                }
            } finally { focused.recycle() }
        } finally { root.recycle() }
        "Replaced the focused phone field's text."
    }

    private suspend fun globalAction(action: GlobalPhoneAction): String = withContext(Dispatchers.Main.immediate) {
        val androidAction = when (action) {
            GlobalPhoneAction.BACK -> GLOBAL_ACTION_BACK
            GlobalPhoneAction.HOME -> GLOBAL_ACTION_HOME
            GlobalPhoneAction.RECENTS -> GLOBAL_ACTION_RECENTS
            GlobalPhoneAction.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            GlobalPhoneAction.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            GlobalPhoneAction.POWER_DIALOG -> GLOBAL_ACTION_POWER_DIALOG
            GlobalPhoneAction.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw PhoneActionException("Lock screen requires Android 9 or newer.")
                GLOBAL_ACTION_LOCK_SCREEN
            }
        }
        if (!performGlobalAction(androidAction)) throw PhoneActionException("Android could not perform '${action.wireName}'.")
        "Performed phone action '${action.wireName}'."
    }

    companion object {
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 30
        private const val MAX_TEXT_CHARS = 12_000
        @Volatile private var connected: PhoneControlAccessibilityService? = null

        fun component(context: Context) = ComponentName(context, PhoneControlAccessibilityService::class.java)

        fun isEnabled(context: Context): Boolean {
            val expected = component(context).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
            return enabled
        }

        private fun service(): PhoneControlAccessibilityService = connected
            ?: throw PhoneActionException("Enable FRIDAY's accessibility service in Android Settings > Accessibility, then try again.")
    }

    class Actions : PhoneAccessibilityActions {
        override suspend fun readScreen() = service().readScreen()
        override suspend fun tap(x: Int, y: Int) = service().tap(x, y)
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int) =
            service().swipe(fromX, fromY, toX, toY, durationMillis)
        override suspend fun enterText(text: String) = service().enterText(text)
        override suspend fun globalAction(action: GlobalPhoneAction) = service().globalAction(action)
    }
}
