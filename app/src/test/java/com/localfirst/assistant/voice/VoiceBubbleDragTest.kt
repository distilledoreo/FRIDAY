package com.localfirst.assistant.voice

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test

/**
 * The bubble once moved a few pixels on the first MOVE event and then stuck:
 * the position update lived inside a `if (!dragMoved)` block. These pins drive
 * real touch events through the overlay and check the window position follows.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceBubbleDragTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun bubble(): VoiceBubble = VoiceBubble(context, scope).also { it.show() }

    private fun down(x: Float, y: Float) =
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, x, y, 0)

    private fun move(x: Float, y: Float) =
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y, 0)

    private fun up(x: Float, y: Float) =
        MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)

    @Test
    fun dragFollowsEveryMoveEventNotJustTheFirst() {
        val bubble = bubble()
        val startX = bubble.params.x
        val startY = bubble.params.y

        bubble.overlay.dispatchTouchEvent(down(100f, 100f))
        bubble.overlay.dispatchTouchEvent(move(160f, 120f))
        val afterFirst = bubble.params.x
        assertTrue("bubble should move on first drag, start=$startX now=$afterFirst", afterFirst > startX)

        // The old bug: the second MOVE was ignored and the bubble stuck.
        bubble.overlay.dispatchTouchEvent(move(300f, 200f))
        assertEquals(startX + 200, bubble.params.x)
        assertEquals(startY + 100, bubble.params.y)

        bubble.overlay.dispatchTouchEvent(up(300f, 200f))
        // A real drag must not toggle expand: the mute control stays hidden.
        assertEquals(View.GONE, bubble.overlay.getChildAt(2).visibility)
    }

    @Test
    fun tapTogglesExpandedWithoutMoving() {
        val bubble = bubble()
        val startX = bubble.params.x
        assertEquals(View.GONE, bubble.overlay.getChildAt(2).visibility)

        bubble.overlay.dispatchTouchEvent(down(50f, 20f))
        bubble.overlay.dispatchTouchEvent(up(51f, 21f)) // within touch slop: a tap, not a drag

        assertEquals(View.VISIBLE, bubble.overlay.getChildAt(2).visibility)
        assertEquals(startX, bubble.params.x)
    }

    @Test
    fun dragCannotLoseTheBubbleOffScreen() {
        val bubble = bubble()
        bubble.overlay.dispatchTouchEvent(down(100f, 100f))
        bubble.overlay.dispatchTouchEvent(move(-5000f, -5000f))
        bubble.overlay.dispatchTouchEvent(up(-5000f, -5000f))
        assertTrue(bubble.params.x >= 0)
        assertTrue(bubble.params.y >= 0)
    }

    @Test
    fun clampBubbleAxisKeepsWindowOnScreen() {
        assertEquals(5, VoiceBubble.clampBubbleAxis(5, 1000))
        assertEquals(0, VoiceBubble.clampBubbleAxis(-40, 1000))
        assertEquals(1000, VoiceBubble.clampBubbleAxis(5000, 1000))
        assertEquals(0, VoiceBubble.clampBubbleAxis(10, 0))
    }
}
