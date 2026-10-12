package com.localfirst.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.localfirst.assistant.tools.phone.PhoneActionException
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * User-enabled bridge for phone use: reads windows, dispatches gestures, takes screenshots (Android 11+)
 * and, on Android 13+, types through the accessibility input method. Events are only timestamped
 * (to know when the screen has settled) and used to remember recently used apps; no other work runs
 * from them.
 */
class PhoneControlAccessibilityService : AccessibilityService() {
    @Volatile var lastEventAt = 0L
        private set
    internal val overlay by lazy { PhoneUseOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && !com.localfirst.assistant.tools.phoneuse.ProtectedApps.isTransient(pkg)) {
                synchronized(recent) { recent.remove(pkg); recent.add(0, pkg); while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex) }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        overlay.hide()
        if (connected === this) connected = null
        super.onDestroy()
    }

    /** Waits until no window events arrived for [quietMillis], at most [maxMillis]. */
    suspend fun awaitSettled(minMillis: Long = 250, quietMillis: Long = 300, maxMillis: Long = 2_500) {
        val start = SystemClock.uptimeMillis()
        delay(minMillis)
        while (SystemClock.uptimeMillis() - start < maxMillis) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMillis) return
            delay(50)
        }
    }

    /** Runs one gesture of one or more strokes; the overlay steps aside so it can't catch the touch. */
    suspend fun gesture(vararg strokes: GestureDescription.StrokeDescription): Boolean = withContext(Dispatchers.Main.immediate) {
        overlay.whileHidden {
            val builder = GestureDescription.Builder()
            strokes.forEach(builder::addStroke)
            val done = CompletableDeferred<Boolean>()
            val accepted = dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
            }, null)
            if (!accepted) throw PhoneActionException("Android rejected that screen gesture.")
            withTimeoutOrNull(6_000) { done.await() } == true
        }
    }

    fun stroke(points: List<android.graphics.Point>, startMillis: Long, durationMillis: Long, willContinue: Boolean = false): GestureDescription.StrokeDescription {
        val path = Path().apply {
            moveTo(points.first().x.toFloat(), points.first().y.toFloat())
            points.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
        }
        return if (willContinue && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(path, startMillis, durationMillis.coerceAtLeast(1), true)
        } else {
            GestureDescription.StrokeDescription(path, startMillis, durationMillis.coerceAtLeast(1))
        }
    }

    /** A screenshot of the default display, without FRIDAY's banner; null with [failure] set when Android refuses. */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshot(): Pair<Bitmap?, String?> = withContext(Dispatchers.Main.immediate) {
        overlay.whileHidden {
            var result = takeOnce()
            if (result.second == "interval") {
                delay(SCREENSHOT_INTERVAL_MS)
                result = takeOnce()
            }
            result
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeOnce(): Pair<Bitmap?, String?> = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { hardware ->
                        hardware.copy(Bitmap.Config.ARGB_8888, false).also { hardware.recycle() }
                    }
                } finally {
                    buffer.close()
                }
                if (cont.isActive) cont.resume(bitmap to (if (bitmap == null) "Android returned an unreadable screenshot" else null))
            }

            override fun onFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "interval"
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "the app blocks screenshots"
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "screenshots aren't allowed for FRIDAY's accessibility service; turn it off and on again in Accessibility settings"
                    else -> "Android couldn't take a screenshot (error $errorCode)"
                }
                if (cont.isActive) cont.resume(null to reason)
            }
        })
    }

    companion object {
        private const val MAX_RECENT = 12
        private const val SCREENSHOT_INTERVAL_MS = 350L
        private val recent = mutableListOf<String>()
        @Volatile private var connected: PhoneControlAccessibilityService? = null

        fun component(context: Context) = ComponentName(context, PhoneControlAccessibilityService::class.java)

        fun isEnabled(context: Context): Boolean {
            val expected = component(context).flattenToString()
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** The running service, or null when the user hasn't enabled it. */
        fun instance(): PhoneControlAccessibilityService? = connected

        fun service(): PhoneControlAccessibilityService = connected
            ?: throw PhoneActionException("Turn on FRIDAY's accessibility service in Android Settings > Accessibility > FRIDAY phone control, then try again.")

        /** Packages seen in front since the service started, most recent first. */
        fun recentPackages(): List<String> = synchronized(recent) { recent.toList() }
    }
}
