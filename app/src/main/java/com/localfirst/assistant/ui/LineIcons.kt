package com.localfirst.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Thin, rounded line icons that match FRIDAY's light wordmark and hairline details, used where the
 * heavier filled Material glyphs looked out of place (the sidebar).
 */
internal object LineIcons {
    private fun icon(name: String, vararg paths: String, dots: List<Pair<Float, Float>> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = addPathNodes(data),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            dots.forEach { (x, y) ->
                addPath(pathData = addPathNodes("M${x - 1.3f},${y}a1.3,1.3 0 1,0 2.6,0a1.3,1.3 0 1,0 -2.6,0z"), fill = SolidColor(Color.Black))
            }
        }.build()

    val Search by lazy { icon("Search", "M10.5,4.5a6,6 0 1,0 0,12a6,6 0 1,0 0,-12z", "M15,15L19.5,19.5") }

    val NewChat by lazy {
        icon("NewChat",
            "M11,4.5H7A2.5,2.5 0 0,0 4.5,7V17A2.5,2.5 0 0,0 7,19.5H17A2.5,2.5 0 0,0 19.5,17V13",
            "M17.6,3.9L20.1,6.4L12.4,14.1L9.3,14.7L9.9,11.6Z")
    }

    /** A hat and glasses, the familiar sign for private browsing. */
    val Incognito by lazy {
        icon("Incognito",
            "M3.5,11.5H20.5",
            "M6,11.5L7.6,5.8A1,1 0 0,1 8.6,5H15.4A1,1 0 0,1 16.4,5.8L18,11.5",
            "M7.8,13.6a2.6,2.6 0 1,0 0,5.2a2.6,2.6 0 1,0 0,-5.2z",
            "M16.2,13.6a2.6,2.6 0 1,0 0,5.2a2.6,2.6 0 1,0 0,-5.2z",
            "M10.4,16.2Q12,15.2 13.6,16.2")
    }

    /** FRIDAY's own space: the same dot grid as the header button. */
    val Friday by lazy {
        icon("Friday", dots = listOf(6f, 12f, 18f).flatMap { y -> listOf(6f, 12f, 18f).map { x -> x to y } })
    }

    val Library by lazy { icon("Library", "M3.5,7.5A2,2 0 0,1 5.5,5.5H9.3L11.3,7.5H18.5A2,2 0 0,1 20.5,9.5V17A2,2 0 0,1 18.5,19H5.5A2,2 0 0,1 3.5,17Z") }

    val Images by lazy {
        icon("Images",
            "M6,4.5H18A2.5,2.5 0 0,1 20.5,7V17A2.5,2.5 0 0,1 18,19.5H6A2.5,2.5 0 0,1 3.5,17V7A2.5,2.5 0 0,1 6,4.5Z",
            "M9,8a1.5,1.5 0 1,0 0,3a1.5,1.5 0 1,0 0,-3z",
            "M4,17L9,12.5L13,16L15.5,13.5L20,17.5")
    }

    /** Sliders read as settings and stay as light as the rest of the set. */
    val Settings by lazy {
        icon("Settings",
            "M4,7.5H13", "M17,7.5H20", "M15,5.5a2,2 0 1,0 0,4a2,2 0 1,0 0,-4z",
            "M4,16.5H7", "M11,16.5H20", "M9,14.5a2,2 0 1,0 0,4a2,2 0 1,0 0,-4z")
    }

    val Add by lazy { icon("Add", "M12,5.5V18.5", "M5.5,12H18.5") }

    val Project by lazy { icon("Project", "M4.5,6.5A2,2 0 0,1 6.5,4.5H17.5A2,2 0 0,1 19.5,6.5V17.5A2,2 0 0,1 17.5,19.5H6.5A2,2 0 0,1 4.5,17.5Z", "M8,9.5H16", "M8,13H13") }

    val More by lazy { icon("More", dots = listOf(12f to 5.5f, 12f to 12f, 12f to 18.5f)) }

    val Person by lazy { icon("Person", "M12,4.5a3.5,3.5 0 1,0 0,7a3.5,3.5 0 1,0 0,-7z", "M5.5,19.5C6.3,16.2 8.9,14.5 12,14.5C15.1,14.5 17.7,16.2 18.5,19.5") }

    val Mic by lazy {
        icon("Mic", "M12,3.8A2.6,2.6 0 0,1 14.6,6.4V11.6A2.6,2.6 0 0,1 9.4,11.6V6.4A2.6,2.6 0 0,1 12,3.8Z",
            "M6.8,11.4A5.2,5.2 0 0,0 17.2,11.4", "M12,16.6V20")
    }

    val Calendar by lazy {
        icon("Calendar", "M6,5.5H18A2,2 0 0,1 20,7.5V18A2,2 0 0,1 18,20H6A2,2 0 0,1 4,18V7.5A2,2 0 0,1 6,5.5Z", "M4,10H20", "M8.5,3.5V7", "M15.5,3.5V7")
    }

    val Bell by lazy {
        icon("Bell", "M6.5,16.5V11A5.5,5.5 0 0,1 17.5,11V16.5L19,18H5Z", "M10.2,20.2A2,2 0 0,0 13.8,20.2")
    }

    val Moon by lazy { icon("Moon", "M19,14.5A7.5,7.5 0 1,1 10,4.8A6,6 0 0,0 19,14.5Z") }

    val Clock by lazy { icon("Clock", "M12,4a8,8 0 1,0 0,16a8,8 0 1,0 0,-16z", "M12,8V12L14.8,13.8") }

    val Place by lazy { icon("Place", "M12,20.5S5.5,14.6 5.5,10A6.5,6.5 0 0,1 18.5,10C18.5,14.6 12,20.5 12,20.5Z", "M12,7.8a2.2,2.2 0 1,0 0,4.4a2.2,2.2 0 1,0 0,-4.4z") }

    val Globe by lazy { icon("Globe", "M12,4a8,8 0 1,0 0,16a8,8 0 1,0 0,-16z", "M4,12H20", "M12,4C9.5,6.5 9.5,17.5 12,20", "M12,4C14.5,6.5 14.5,17.5 12,20") }

    val Weather by lazy { icon("Weather", "M7.5,18.5H16.5A3.5,3.5 0 0,0 16.2,11.5A5,5 0 0,0 6.6,12.6A3,3 0 0,0 7.5,18.5Z") }

    val Checklist by lazy { icon("Checklist", "M4.5,7L6,8.5L8.5,6", "M11.5,7.5H19.5", "M4.5,13L6,14.5L8.5,12", "M11.5,13.5H19.5", "M11.5,19H19.5", "M5,18.5H8") }

    val Send by lazy { icon("Send", "M12,19V5.5", "M6.5,11L12,5.5L17.5,11") }

    val Refresh by lazy { icon("Refresh", "M19,12A7,7 0 1,1 16.9,7", "M17.5,3.8V7.5H13.8") }

    val Close by lazy { icon("Close", "M6.5,6.5L17.5,17.5", "M17.5,6.5L6.5,17.5") }

    val Chat by lazy { icon("Chat", "M5,6.5A2,2 0 0,1 7,4.5H17A2,2 0 0,1 19,6.5V14A2,2 0 0,1 17,16H10L6,19.5V16H7A2,2 0 0,1 5,14Z") }
}
