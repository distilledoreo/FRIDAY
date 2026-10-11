package com.localfirst.assistant.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.localfirst.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A draggable bubble drawn over other apps while voice mode runs in the
 * background. Collapsed, it shows a state dot and the phase word; tapped, it
 * expands into mute, resume and end controls. It only draws and relays taps:
 * the view model owns the voice state, so FRIDAY keeps speaking out the
 * phone's speaker wherever the user is.
 */
class VoiceBubble(context: Context, private val scope: CoroutineScope) {
    private val window = context.getSystemService(WindowManager::class.java)
    internal val overlay: LinearLayout
    private val dot: View
    private val label: TextView
    private val mute: ImageButton
    private val resume: ImageButton
    private val close: ImageButton
    internal val params: WindowManager.LayoutParams

    private var expanded = false
    private var latest: VoiceUiState? = null
    private var activePointer = -1
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var dragMoved = false
    private var collapseJob: Job? = null

    var onMuteMic: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null

    /** True while the bubble is added to the window manager. */
    val shown: Boolean get() = overlay.isAttachedToWindow

    private fun rounded(radiusDp: Float, color: Int, density: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radiusDp * density
            setColor(color)
        }

    init {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        fun iconButton(icon: Int, description: String) = ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = description
            background = rounded(20f, 0x33FFFFFF, density)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginStart = dp(10)
            }
        }

        overlay = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(26f, 0xE6101014.toInt(), density)
            elevation = dp(6).toFloat()
        }
        dot = View(context).apply {
            background = rounded(5f, Color.WHITE, density)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).also { it.gravity = Gravity.CENTER_VERTICAL }
        }
        label = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.gravity = Gravity.CENTER_VERTICAL; it.marginStart = dp(8) }
        }
        mute = iconButton(R.drawable.bubble_mic_off, "Mute microphone")
        resume = iconButton(R.drawable.bubble_mic, "Resume voice")
        close = iconButton(R.drawable.bubble_close, "End voice conversation")
        overlay.addView(dot)
        overlay.addView(label)
        overlay.addView(mute)
        overlay.addView(resume)
        overlay.addView(close)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(160)
        }

        mute.setOnClickListener { onMuteMic?.invoke(); collapse() }
        resume.setOnClickListener { onResume?.invoke(); collapse() }
        close.setOnClickListener { onEnd?.invoke(); collapse() }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        overlay.setOnTouchListener(@SuppressLint("ClickableViewAccessibility")
        object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointer = event.getPointerId(0)
                        downLocalX = event.getX(0)
                        downLocalY = event.getY(0)
                        startWindowX = params.x
                        startWindowY = params.y
                        dragMoved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointer).takeIf { it >= 0 } ?: return true
                        val dx = (event.getX(index) - downLocalX).toInt()
                        val dy = (event.getY(index) - downLocalY).toInt()
                        if (!dragMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragMoved = true
                        }
                        if (dragMoved) {
                            // Every move event repositions: the window follows the finger until lift.
                            params.x = clampBubbleAxis(startWindowX + dx, bubbleAxisLimit(horizontal = true))
                            params.y = clampBubbleAxis(startWindowY + dy, bubbleAxisLimit(horizontal = false))
                            if (shown) window.updateViewLayout(overlay, params)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.getPointerId(event.actionIndex) == activePointer) {
                            // The dragging finger lifted but others remain: stop the drag so the
                            // bubble doesn't jump, and require a fresh DOWN to move again.
                            activePointer = -1
                            dragMoved = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragMoved) {
                            if (expanded) collapse() else setExpanded(true)
                        }
                        activePointer = -1
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        activePointer = -1
                    }
                }
                return true
            }
        })
        applyVisibility()
    }

    /** Writes the dot color, phase word and mute button; called whenever voice state changes. */
    fun update(state: VoiceUiState) {
        latest = state
        val (color, word) = when {
            state.micMuted -> 0xFF9A9AA6.toInt() to "Muted"
            else -> when (state.phase) {
                VoicePhase.LISTENING -> 0xFF7BD38B.toInt() to "Listening"
                VoicePhase.THINKING -> 0xFF9DB8F5.toInt() to "Thinking"
                VoicePhase.SPEAKING, VoicePhase.CONFIRMING -> 0xFFF5C77D.toInt() to
                    if (state.phase == VoicePhase.SPEAKING) "Speaking" else "Confirming"
                VoicePhase.STARTING -> 0xFFB6B6C0.toInt() to "Starting"
                VoicePhase.PAUSED -> 0xFF80808A.toInt() to "Paused"
            }
        }
        (dot.background as? GradientDrawable)?.setColor(color)
        label.text = word
        val paused = state.phase == VoicePhase.PAUSED
        mute.setImageResource(if (state.micMuted || paused) R.drawable.bubble_mic else R.drawable.bubble_mic_off)
        mute.contentDescription = if (paused) "Resume voice" else
            if (state.micMuted) "Unmute microphone" else "Mute microphone"
        applyVisibility()
        if (shown) window.updateViewLayout(overlay, params)
    }

    fun show() {
        if (shown) return
        window.addView(overlay, params)
    }

    fun hide() {
        if (!shown) return
        window.removeView(overlay)
        expanded = false
        applyVisibility()
    }

    private fun collapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(150)
            if (shown) setExpanded(false)
        }
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        applyVisibility()
        if (shown) window.updateViewLayout(overlay, params)
    }

    companion object {
        /** Keeps the bubble fully on screen: never negative, never past [limit]. */
        internal fun clampBubbleAxis(position: Int, limit: Int): Int =
            position.coerceIn(0, limit.coerceAtLeast(0))
    }

    /** Furthest the bubble's top-left may travel before it would leave the display. */
    internal fun bubbleAxisLimit(horizontal: Boolean): Int {
        val metrics = overlay.resources.displayMetrics
        val display = if (horizontal) metrics.widthPixels else metrics.heightPixels
        val size = if (horizontal) overlay.width else overlay.height
        if (size <= 0) return display // not laid out yet: only the lower bound applies
        return (display - size).coerceAtLeast(0)
    }

    /** Collapsed shows the dot and phase word; expanded hides the word and shows the controls. */
    private fun applyVisibility() {
        val paused = latest?.phase == VoicePhase.PAUSED
        label.visibility = if (expanded) View.GONE else View.VISIBLE
        mute.visibility = if (expanded && !paused) View.VISIBLE else View.GONE
        resume.visibility = if (expanded && paused) View.VISIBLE else View.GONE
        close.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    fun close() {
        scope.cancel()
        hide()
    }
}
