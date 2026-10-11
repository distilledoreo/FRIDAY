package com.localfirst.assistant.phone

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Signals from the phone-use banner to the chat. */
object PhoneUseSignals {
    private val stops = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The user tapped Stop on the banner. */
    val stop: SharedFlow<Unit> = stops.asSharedFlow()
    internal fun requestStop() { stops.tryEmit(Unit) }
}

/**
 * The banner Codex shows while it uses an app: "FRIDAY is using Gmail" with a Stop button, plus a brief
 * mark where FRIDAY touches. Both are accessibility overlays, so they need no extra permission, and
 * both step aside for screenshots and gestures so the model never sees or taps them.
 */
internal class PhoneUseOverlay(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var banner: View? = null
    private var bannerParams: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var touchMark: View? = null
    private var hidden = 0

    fun show(appLabel: String) {
        val text = "FRIDAY is using $appLabel"
        label?.let { it.text = text; return }
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(Color.argb(235, 24, 24, 27)) }
            setPadding(dp(14f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
            elevation = dp(6f)
            addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 196, 61)) }
                layoutParams = LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply { marginEnd = dp(8f).toInt() }
            })
            val title = TextView(context).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            }
            label = title
            addView(title)
            addView(TextView(context).apply {
                this.text = "Stop"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12f).toInt(), dp(4f).toInt(), dp(12f).toInt(), dp(4f).toInt())
                background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(Color.argb(60, 255, 255, 255)) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10f).toInt() }
                contentDescription = "Stop FRIDAY"
                setOnClickListener { PhoneUseSignals.requestStop(); hide() }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(4f).toInt()
        }
        runCatching { windows.addView(view, params) }.onSuccess {
            banner = view
            bannerParams = params
            if (hidden > 0) setVisible(false)
        }.onFailure { label = null }
    }

    fun hide() {
        banner?.let { runCatching { windows.removeView(it) } }
        banner = null
        bannerParams = null
        label = null
        clearTouchMark()
    }

    /** Runs [block] with the overlays out of the way: invisible and not touchable. */
    suspend fun <T> whileHidden(block: suspend () -> T): T {
        val wasShown = banner != null && hidden == 0
        hidden++
        clearTouchMark()
        if (wasShown) {
            setVisible(false)
            delay(48) // let a frame without the banner reach the screen
        }
        return try {
            block()
        } finally {
            hidden--
            if (hidden == 0) setVisible(true)
        }
    }

    private fun setVisible(visible: Boolean) {
        val view = banner ?: return
        val params = bannerParams ?: return
        view.alpha = if (visible) 1f else 0f
        params.flags = if (visible) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windows.updateViewLayout(view, params) }
    }

    /** A short fading ring where FRIDAY touched, in display pixels. */
    fun markTouch(x: Int, y: Int) {
        clearTouchMark()
        val radius = dp(22f)
        val view = object : View(service) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.rgb(255, 196, 61) }
            override fun onDraw(canvas: Canvas) = canvas.drawCircle(radius + paint.strokeWidth, radius + paint.strokeWidth, radius, paint)
        }
        val size = ((radius + dp(3f)) * 2).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
        }
        if (runCatching { windows.addView(view, params) }.isFailure) return
        touchMark = view
        view.animate().alpha(0f).scaleX(1.4f).scaleY(1.4f).setDuration(450).setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { if (touchMark === view) clearTouchMark() }
        }).start()
    }

    private fun clearTouchMark() {
        touchMark?.let { mark -> mark.animate().cancel(); runCatching { windows.removeView(mark) } }
        touchMark = null
    }

    private fun dp(value: Float) = value * service.resources.displayMetrics.density
}
