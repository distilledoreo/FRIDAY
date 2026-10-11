package com.localfirst.assistant.phone

import android.graphics.Rect
import com.localfirst.assistant.voice.ScreenContextService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test

/**
 * Tap accuracy depends on the image→display mapping being symmetric and unbiased:
 * the model reads coordinates from a resized screenshot and FRIDAY maps them back.
 * These pins keep the round trip within half a display pixel and refuse skewed views.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneScreenCoordinateMapTest {
    private fun map(
        imageWidth: Int, imageHeight: Int,
        displayWidth: Int, displayHeight: Int,
        viewport: Rect = Rect(0, 0, displayWidth, displayHeight),
    ) = PhoneScreenCoordinateMap(imageWidth, imageHeight, displayWidth, displayHeight, viewport, usesScreenshotPixels = true)

    @Test
    fun pixelCentersMapRoundHalfUpSoEdgesLandEvenly() {
        // 1000 image px -> 1440 display px: no simple common divisor, so naive
        // truncation drifts up to a full display pixel on one axis.
        val m = map(imageWidth = 1000, imageHeight = 1000, displayWidth = 1440, displayHeight = 1440)
        // Center of the image lands within a display pixel of the display center.
        assertEquals(true, m.toDisplay(500, 500).y in 719..720)
        assertEquals(true, m.toDisplay(500, 500).x in 719..720)
        // Every mapping stays inside the display.
        for (x in listOf(0, 1, 999)) assertEquals(true, m.toDisplay(x, 0).x in 0 until 1440)
        // Edge pixel-centers are within one display pixel of the frame edge.
        assertEquals(true, m.toDisplay(0, 0).y <= 1)
        assertEquals(true, m.toDisplay(999, 999).y >= 1438)
    }

    @Test
    fun mappingIsMonotonicWithNoBackwardJumpsWhenDownscaling() {
        val m = map(imageWidth = 1080, imageHeight = 2400, displayWidth = 1080, displayHeight = 2400)
        var previous = Int.MIN_VALUE
        for (y in 0 until 2400) {
            val mapped = m.toDisplay(540, y).y
            assertEquals(true, mapped >= previous) // never moves backwards
            previous = mapped
        }
        // First and last rows map to the first and last display rows (center convention, ≤1px).
        assertEquals(true, m.toDisplay(540, 0).y <= 1)
        assertEquals(true, m.toDisplay(540, 2399).y >= 2398)
    }

    @Test
    fun viewportOffsetsShiftTheMappingWithoutChangingScale() {
        // A cropped app window: the image covers only part of the display, offset by (0,120).
        val m = map(
            imageWidth = 540, imageHeight = 1080,
            displayWidth = 1080, displayHeight = 2400,
            viewport = Rect(0, 120, 1080, 2280),
        )
        // Viewport edge lands on the viewport edge (center convention, ≤1px).
        assertEquals(true, m.toDisplay(0, 0).y in 120..121)
        // Center image column lands in the middle of the display area.
        assertEquals(true, m.toDisplay(269, 0).x in 538..542)
        // Bottom viewport edge never spills past the viewport.
        assertEquals(true, m.toDisplay(539, 1079).y <= 2280)
    }

    @Test
    fun imageBoundsReportedToTheModelContainTheDisplayElement() {
        // read_screen reports bounds in image space; they must over-cover, never under-cover,
        // because the model taps their computed center.
        val m = map(imageWidth = 720, imageHeight = 1560, displayWidth = 1080, displayHeight = 2340)
        val displayElement = Rect(531, 900, 549, 930) // ~18x30 display pixels, e.g. a check box
        val imageBounds = m.toImageBounds(displayElement)
        val center = m.toDisplay((imageBounds.left + imageBounds.right) / 2, (imageBounds.top + imageBounds.bottom) / 2)
        assertTrue(displayElement.contains(center.x, center.y))
    }

    @Test
    fun edgeInsetKeepsTapsOffSystemBars() {
        assertEquals(12 until 1068, PhoneScreenCoordinateMap.insetRange(1080, 12))
        assertEquals(0 until 1080, PhoneScreenCoordinateMap.insetRange(1080, 0))
        // A margin that would swallow the screen degrades to the full range, never empty.
        assertEquals(0 until 100, PhoneScreenCoordinateMap.insetRange(100, 60))
        // Bottom-row model taps get nudged inside the app area, not the nav bar.
        val y = 2399.coerceIn(PhoneScreenCoordinateMap.insetRange(2400, 36))
        assertEquals(2400 - 1 - 36, y)
    }

    @Test
    fun tapTargetNamesDeepestLabeledControl() {
        val row = TapCandidate(Rect(100, 500, 980, 620), label = "Conversation", clickable = true)
        val button = TapCandidate(Rect(860, 530, 950, 590), label = "Archive", clickable = true)
        // Point inside both: the deeper, smaller control is named.
        assertEquals("Archive", tapTargetLabel(900, 560, listOf(row, button)))
    }

    @Test
    fun tapTargetIgnoresUnlabeledCanvas() {
        // An in-game keyboard is one big unlabeled node: the tap is reported as landing on
        // nothing labeled, so the model trusts its own coordinates instead of a wrong snap.
        val keyboard = TapCandidate(Rect(0, 1500, 1080, 2400), label = null, clickable = true)
        val enter = TapCandidate(Rect(880, 2250, 1070, 2390), label = "Enter", clickable = true)
        assertEquals(null, tapTargetLabel(300, 2100, listOf(keyboard, enter)))
        assertEquals("Enter", tapTargetLabel(970, 2320, listOf(keyboard, enter)))
    }

    @Test
    fun tapTargetOffAnythingIsNull() {
        val button = TapCandidate(Rect(860, 530, 950, 590), label = "Archive", clickable = true)
        assertEquals(null, tapTargetLabel(100, 100, listOf(button)))
        assertEquals(null, tapTargetLabel(100, 100, emptyList()))
    }

    @Test
    fun visionFrameSizeFitsTheEncoderBudgetOnAGrid() {
        // Typical tall phone: fits the token budget and lands on the 32px token grid, so the
        // encoder's smart-resize is a no-op and stated pixels == perceived pixels.
        val (w, h) = ScreenContextService.visionFrameSize(1080, 2400)
        assertEquals(0, w % ScreenContextService.VISION_PATCH_GRID)
        assertEquals(0, h % ScreenContextService.VISION_PATCH_GRID)
        val tokens = (w / ScreenContextService.VISION_PATCH_GRID) * (h / ScreenContextService.VISION_PATCH_GRID)
        assertTrue(tokens <= ScreenContextService.VISION_MAX_TOKENS)
        assertTrue(tokens >= ScreenContextService.VISION_MAX_TOKENS / 2) // still legible, not postage-stamp
        // Aspect stays close to the display's so the layout the model sees matches the phone.
        val displayAspect = 1080.0 / 2400
        val frameAspect = w.toDouble() / h
        assertTrue(kotlin.math.abs(frameAspect - displayAspect) / displayAspect < 0.02)
    }

    @Test
    fun visionFramesPassTheEncoderResizeUnchanged() {
        // llama.cpp's smart resize for Qwen3-VL (mtmd-image.cpp calc_size_preserved_ratio): align to
        // 32px, then shrink to fit max_tokens * 32^2 pixels. A frame it would resize again would no
        // longer match the dimensions the attachment note gives the model for its taps.
        val align = 16 * 2 // Qwen3.8's mmproj: patch_size 16, spatial_merge_size 2
        val maxPixels = 1024 * align * align // both servers' image token limit
        fun encoderSize(w: Int, h: Int): Pair<Int, Int> {
            var wBar = maxOf(align, Math.round(w.toFloat() / align) * align)
            var hBar = maxOf(align, Math.round(h.toFloat() / align) * align)
            if (wBar * hBar > maxPixels) {
                val beta = kotlin.math.sqrt(h.toFloat() * w / maxPixels)
                hBar = maxOf(align, kotlin.math.floor(h / beta / align).toInt() * align)
                wBar = maxOf(align, kotlin.math.floor(w / beta / align).toInt() * align)
            }
            return wBar to hBar
        }
        for ((dw, dh) in listOf(1080 to 2400, 1080 to 2340, 1440 to 3120, 1440 to 3200, 720 to 1600, 1220 to 2712, 2400 to 1080)) {
            val frame = ScreenContextService.visionFrameSize(dw, dh)
            assertEquals("display ${dw}x$dh", frame, encoderSize(frame.first, frame.second))
        }
    }

    @Test
    fun visionFrameSizeLeavesSmallScreensAlone() {
        val (w, h) = ScreenContextService.visionFrameSize(560, 1120)
        assertTrue(w <= 560 && h <= 1120)
        assertEquals(0, w % ScreenContextService.VISION_PATCH_GRID)
        assertEquals(0, h % ScreenContextService.VISION_PATCH_GRID)
    }

    @Test
    fun ambiguousOrCroppedFramesAreRefused() {
        // Aspect matches neither the full display nor a plausible window: refuse rather than tap wild.
        val frame = SharedFrameGeometry(imageWidth = 800, imageHeight = 600, sourceWidth = 800, sourceHeight = 600)
        val refused = try {
            PhoneScreenCoordinateMap.forSharedFrame(frame, displayWidth = 1080, displayHeight = 2400, activeWindow = null)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(refused)
    }
}
