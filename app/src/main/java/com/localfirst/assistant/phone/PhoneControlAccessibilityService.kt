package com.localfirst.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.localfirst.assistant.tools.phone.GlobalPhoneAction
import com.localfirst.assistant.tools.phone.PhoneAccessibilityActions
import com.localfirst.assistant.tools.phone.PhoneActionException
import com.localfirst.assistant.voice.ScreenContextService
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
        val coordinateMap = try { coordinateMap(root) } catch (e: Exception) { root.recycle(); throw e }
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
                val displayBounds = Rect().also(node::getBoundsInScreen)
                val bounds = if (coordinateMap.usesScreenshotPixels) coordinateMap.toImageBounds(displayBounds) else displayBounds
                val x1 = bounds.left
                val y1 = bounds.top
                val x2 = bounds.right
                val y2 = bounds.bottom
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
        val coordinateHeader = "Coordinate system: ${coordinateMap.coordinatesDescription()}; use these exact pixel coordinates and include screen_width=${coordinateMap.imageWidth}, screen_height=${coordinateMap.imageHeight} in touch tools. Android display=${coordinateMap.displayWidth}x${coordinateMap.displayHeight}."
        val elements = if (lines.isEmpty()) "The current screen exposes no readable text." else lines.joinToString("\n").take(MAX_TEXT_CHARS)
        "$coordinateHeader\n$elements"
    }

    private suspend fun tap(x: Int, y: Int): String = gesture(x, y, x, y, 60, null, null, "tap")

    private suspend fun tap(x: Int, y: Int, coordinateWidth: Int, coordinateHeight: Int): String =
        gesture(x, y, x, y, 60, coordinateWidth, coordinateHeight, "tap")

    private suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int, coordinateWidth: Int, coordinateHeight: Int): String =
        gesture(fromX, fromY, toX, toY, durationMillis, coordinateWidth, coordinateHeight, "swipe")

    private suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int): String =
        gesture(fromX, fromY, toX, toY, durationMillis, null, null, "swipe")

    private suspend fun gesture(
        x1: Int, y1: Int, x2: Int, y2: Int, duration: Int,
        coordinateWidth: Int?, coordinateHeight: Int?, action: String,
    ): String =
        withContext(Dispatchers.Main.immediate) {
            val root = rootInActiveWindow
            val coordinateMap = try { coordinateMap(root, coordinateWidth, coordinateHeight) } finally { root?.recycle() }
            var start = try { coordinateMap.toDisplay(x1, y1) } catch (e: IllegalArgumentException) { throw PhoneActionException(e.message ?: "Invalid screenshot coordinates.") }
            var end = try { coordinateMap.toDisplay(x2, y2) } catch (e: IllegalArgumentException) { throw PhoneActionException(e.message ?: "Invalid screenshot coordinates.") }
            // Keep taps off the system gesture/navigation bars: clamping to the full display edge
            // hands a bottom-row tap to the OS instead of the app (looks like a miss, retries loop).
            val density = resources.displayMetrics.density.takeIf { it > 0 } ?: 1f
            val maxMargin = minOf(coordinateMap.displayWidth, coordinateMap.displayHeight) / 4
            val margin = (EDGE_TOUCH_INSET_DP * density).toInt().coerceIn(0, maxMargin)
            val rangeX = PhoneScreenCoordinateMap.insetRange(coordinateMap.displayWidth, margin)
            val rangeY = PhoneScreenCoordinateMap.insetRange(coordinateMap.displayHeight, margin)
            var targetNote = ""
            if (action == "tap") {
                val adjusted = Point(start.x.coerceIn(rangeX), start.y.coerceIn(rangeY))
                if (adjusted != start) Log.i(TAG, "tap moved off the screen edge ($start -> $adjusted); margin=${margin}px")
                start = adjusted
                end = adjusted
                // Name the control under the tap for the model to verify against — reported,
                // never acted on. The coordinates themselves are always tapped exactly as given.
                rootInActiveWindow?.let { fresh ->
                    try {
                        val label = tapTargetLabel(start.x, start.y, collectTapCandidates(fresh))
                        if (label != null) {
                            Log.i(TAG, "tap at $start lands on '$label'")
                            targetNote = " The tap point lands on the on-screen control '$label'."
                        }
                    } finally {
                        fresh.recycle()
                    }
                }
            }
            val path = Path().apply { moveTo(start.x.toFloat(), start.y.toFloat()); lineTo(end.x.toFloat(), end.y.toFloat()) }
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
            val startDiagnostic = coordinateMap.diagnostic(x1, y1, start)
            val endDiagnostic = if (action == "swipe") " end=${coordinateMap.diagnostic(x2, y2, end)}" else ""
            val diagnostic = "$action $startDiagnostic$endDiagnostic"
            Log.i(TAG, diagnostic)
            "Android completed the $action gesture. $diagnostic$targetNote"
        }

    private fun coordinateMap(
        root: AccessibilityNodeInfo?,
        requestedWidth: Int? = null,
        requestedHeight: Int? = null,
    ): PhoneScreenCoordinateMap {
        val display = Point()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(display)
        val displayWidth = display.x.coerceAtLeast(1)
        val displayHeight = display.y.coerceAtLeast(1)
        val frame = ScreenContextService.currentFrameGeometry()
        val map = if (ScreenContextService.active.value) {
            if (frame == null) throw PhoneActionException("Screen sharing is on but no screen image has arrived yet. If you chose single-app sharing, bring that app to the foreground; otherwise stop screen sharing and start it again, then read the screen before touching it.")
            val activeBounds = root?.let { Rect().also(it::getBoundsInScreen) }
            try {
                PhoneScreenCoordinateMap.forSharedFrame(frame, displayWidth, displayHeight, activeBounds)
            } catch (e: IllegalArgumentException) {
                throw PhoneActionException("Cannot safely map screenshot coordinates: ${e.message} Keep the full display visible or capture an aligned screen region.")
            }
        } else {
            PhoneScreenCoordinateMap.displayPixels(displayWidth, displayHeight)
        }
        if (requestedWidth != null && requestedHeight != null &&
            (requestedWidth != map.imageWidth || requestedHeight != map.imageHeight)
        ) {
            throw PhoneActionException(
                "Coordinate image changed: the request used ${requestedWidth}x$requestedHeight, but the current ${map.coordinatesDescription()} has dimensions ${map.imageWidth}x${map.imageHeight}. Read the current screen again before touching it."
            )
        }
        return map
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
        /** Clearance around the display edge reserved for system gestures, not app taps. */
        private const val EDGE_TOUCH_INSET_DP = 12
        private const val MAX_TAP_CANDIDATES = 250

        /** Flattens the live tree into tap retarget candidates, in display pixels. */
        private fun collectTapCandidates(root: AccessibilityNodeInfo): List<TapCandidate> {
            val out = mutableListOf<TapCandidate>()
            fun visit(node: AccessibilityNodeInfo) {
                if (out.size >= MAX_TAP_CANDIDATES || !node.isVisibleToUser) return
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    val label = (node.text?.toString() ?: node.contentDescription?.toString())
                        ?.replace('\n', ' ')?.trim()?.take(80)?.takeIf(String::isNotEmpty)
                    out += TapCandidate(Rect(bounds), label, node.isClickable)
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try {
                        visit(child)
                    } finally {
                        child.recycle()
                    }
                }
            }
            visit(root)
            return out
        }
        private const val TAG = "FRIDAYTouchMap"
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
        override suspend fun tap(x: Int, y: Int, screenWidth: Int, screenHeight: Int) = service().tap(x, y, screenWidth, screenHeight)
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int) = service().swipe(fromX, fromY, toX, toY, durationMillis)
        override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMillis: Int, screenWidth: Int, screenHeight: Int) =
            service().swipe(fromX, fromY, toX, toY, durationMillis, screenWidth, screenHeight)
        override suspend fun enterText(text: String) = service().enterText(text)
        override suspend fun globalAction(action: GlobalPhoneAction) = service().globalAction(action)
    }
}
