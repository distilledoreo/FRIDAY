package com.localfirst.assistant.phone

import android.graphics.Point
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One on-screen element that could receive a tap, in Android display pixels. */
internal data class TapCandidate(
    val bounds: Rect,
    val label: String?,
    val clickable: Boolean,
)

/**
 * Names the labeled element under a tap point, if any. This is reported to the model, never
 * acted on: silently moving taps ("snapping") turned small aim errors into wrong-control taps
 * (e.g. an unlabeled keyboard canvas retargeting every keypress), while the model went on
 * reasoning from coordinates it never actually touched. Observation keeps the history honest
 * and still compensates for layout shift, because a mismatch is visible instead of mysterious.
 * Deepest control wins over its container.
 */
internal fun tapTargetLabel(x: Int, y: Int, candidates: List<TapCandidate>): String? =
    candidates
        .filter { it.label != null && it.bounds.contains(x, y) }
        .minWithOrNull(compareBy({ if (it.clickable) 0 else 1 }, { area(it.bounds) }))
        ?.label

private fun area(bounds: Rect): Long =
    bounds.width().toLong() * bounds.height().toLong()

/** Geometry of the image attached to the model request, before and after image resizing. */
internal data class SharedFrameGeometry(
    val imageWidth: Int,
    val imageHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/** Converts coordinates in the exact attached image into Android's display coordinate space. */
internal data class PhoneScreenCoordinateMap(
    val imageWidth: Int,
    val imageHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    /** Portion of the display represented by the screenshot. Includes status/navigation bars for full-display capture. */
    val viewport: Rect,
    val usesScreenshotPixels: Boolean,
) {
    fun toDisplay(x: Int, y: Int): Point {
        require(x in 0 until imageWidth && y in 0 until imageHeight) {
            "Screenshot coordinates ($x,$y) are outside the ${imageWidth}x$imageHeight image."
        }
        // +0.5 samples the pixel's center, floor maps each image-pixel center onto the closest
        // display pixel below it: unbiased, monotonic, and symmetric with toImageBounds, so a
        // model's coordinates land on the element it read after the resize.
        val mappedX = viewport.left + floor((x + 0.5) * viewport.width().toDouble() / imageWidth).toInt()
        val mappedY = viewport.top + floor((y + 0.5) * viewport.height().toDouble() / imageHeight).toInt()
        return Point(mappedX.coerceIn(0, displayWidth - 1), mappedY.coerceIn(0, displayHeight - 1))
    }

    fun toImageBounds(displayBounds: Rect): Rect {
        // Outward rounding (floor left/top, ceil right/bottom): the reported image-space bounds
        // always contain at least the element's true display area, so a center-of-bounds tap
        // computed from them still lands inside the element after rescaling.
        val left = floor((displayBounds.left - viewport.left).toDouble() * imageWidth / viewport.width()).toInt()
        val top = floor((displayBounds.top - viewport.top).toDouble() * imageHeight / viewport.height()).toInt()
        val right = ceil((displayBounds.right - viewport.left).toDouble() * imageWidth / viewport.width()).toInt()
        val bottom = ceil((displayBounds.bottom - viewport.top).toDouble() * imageHeight / viewport.height()).toInt()
        return Rect(
            left.coerceIn(0, imageWidth), top.coerceIn(0, imageHeight),
            right.coerceIn(0, imageWidth), bottom.coerceIn(0, imageHeight),
        )
    }

    fun coordinatesDescription(): String = if (usesScreenshotPixels) {
        "Screenshot pixels ${imageWidth}x$imageHeight (origin top-left)"
    } else {
        "Android display pixels ${displayWidth}x$displayHeight (origin top-left)"
    }

    fun diagnostic(inputX: Int, inputY: Int, point: Point): String =
        "input=($inputX,$inputY) ${coordinatesDescription()}; Android touch=(${point.x},${point.y}) display=${displayWidth}x$displayHeight; viewport=${viewport.left},${viewport.top},${viewport.width()}x${viewport.height()}"

    companion object {
        fun displayPixels(width: Int, height: Int) = PhoneScreenCoordinateMap(
            imageWidth = width, imageHeight = height,
            displayWidth = width, displayHeight = height,
            viewport = Rect(0, 0, width, height), usesScreenshotPixels = false,
        )

        /**
         * Resolve full-display versus app-window capture. MediaProjection reports dimensions but not
         * a cropped app's origin. Use active-window bounds when they match; reject ambiguous crops.
         */
        fun forSharedFrame(
            frame: SharedFrameGeometry,
            displayWidth: Int,
            displayHeight: Int,
            activeWindow: Rect?,
        ): PhoneScreenCoordinateMap {
            val candidates = buildList {
                add(Rect(0, 0, displayWidth, displayHeight))
                activeWindow?.takeIf { !it.isEmpty && it.left >= 0 && it.top >= 0 && it.right <= displayWidth && it.bottom <= displayHeight }
                    ?.takeIf { candidate -> none { it == candidate } }
                    ?.let(::add)
            }
            val sourceWidth = frame.sourceWidth
            val sourceHeight = frame.sourceHeight
            val aspectError = candidates.map { candidate ->
                candidate to abs(sourceWidth.toDouble() / sourceHeight - candidate.width().toDouble() / candidate.height()) /
                    (candidate.width().toDouble() / candidate.height())
            }
            val exact = aspectError.firstOrNull { (rect, _) -> rect.width() == sourceWidth && rect.height() == sourceHeight }
            val matching = aspectError.filter { it.second <= MAX_ASPECT_ERROR }
            val selected = when {
                exact != null -> exact.first
                matching.isEmpty() -> throw IllegalArgumentException(
                    "Shared image ${frame.imageWidth}x${frame.imageHeight} (source ${sourceWidth}x$sourceHeight) cannot be aligned safely to Android display ${displayWidth}x$displayHeight. The capture may be cropped."
                )
                matching.size == 1 -> matching.single().first
                else -> {
                    val ordered = matching.sortedBy { it.second }
                    if (ordered[1].second - ordered[0].second < AMBIGUOUS_ASPECT_ERROR) {
                        throw IllegalArgumentException(
                            "Shared image ${sourceWidth}x$sourceHeight matches more than one display viewport; refusing to guess the screenshot offset."
                        )
                    }
                    ordered.first().first
                }
            }
            return PhoneScreenCoordinateMap(
                imageWidth = frame.imageWidth,
                imageHeight = frame.imageHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
                viewport = selected,
                usesScreenshotPixels = true,
            )
        }

        /**
         * A tap that lands on the outermost pixels of the display usually hits Android's system
         * gesture/navigation bars instead of the app beneath, looking like a wildly wrong tap
         * (and a retap just navigates again). Keep this many display pixels clear of each edge.
         */
        fun insetRange(size: Int, margin: Int): IntRange =
            if (margin <= 0 || margin * 2 >= size) 0 until size else margin until size - margin

        private const val MAX_ASPECT_ERROR = 0.015
        private const val AMBIGUOUS_ASPECT_ERROR = 0.001
    }
}
