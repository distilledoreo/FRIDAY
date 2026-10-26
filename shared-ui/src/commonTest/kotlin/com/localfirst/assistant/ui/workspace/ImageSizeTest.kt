package com.localfirst.assistant.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageSizeTest {
    @Test fun longestSideAndShapeGiveMultiplesOf32() {
        assertEquals(1344 to 1344, imageSize(1 to 1, 1344))
        assertEquals(1344 to 768, imageSize(16 to 9, 1344))
        assertEquals(768 to 1344, imageSize(9 to 16, 1344))
        assertEquals(1984 to 1120, imageSize(16 to 9, 1984))
        assertEquals(1024 to 768, imageSize(4 to 3, 1024))
        for ((w, h) in IMAGE_ASPECTS) for (l in IMAGE_LONGEST) imageSize(w to h, l).let { (x, y) -> assertTrue(x % 32 == 0 && y % 32 == 0 && maxOf(x, y) == l) }
    }

    @Test fun timeLabelsGrowWithSize() {
        assertEquals("about 70 s", imageTimeLabel(1024, 1024))
        assertEquals("about 4.5 min", imageTimeLabel(1984, 1984))
        assertTrue(imageTimeLabel(1984, 1984).endsWith("min"))
    }
}
