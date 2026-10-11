package com.localfirst.assistant.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * The few Material icons the app needs that are not in material-icons-core.
 * Path data is from Google's Material icons (Apache 2.0); copying them avoids
 * pulling in the very large material-icons-extended artifact.
 */
internal object AppIcons {
    val Image: ImageVector by lazy {
        materialIcon(name = "Filled.Image") {
            materialPath {
                moveTo(21f, 19f); verticalLineTo(5f); curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f); horizontalLineTo(5f)
                curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f); verticalLineTo(19f); curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
                horizontalLineTo(19f); curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f); close()
                moveTo(8.5f, 13.5f); lineTo(11f, 16.51f); lineTo(14.5f, 12f); lineTo(19f, 18f); horizontalLineTo(5f); close()
            }
        }
    }

    /** Material "Smartphone", for phone-use activity. */
    val PhoneUse: ImageVector by lazy {
        materialIcon(name = "Filled.Smartphone") {
            materialPath {
                moveTo(17f, 1.01f); lineTo(7f, 1f); curveTo(5.9f, 1f, 5f, 1.9f, 5f, 3f)
                verticalLineTo(21f); curveTo(5f, 22.1f, 5.9f, 23f, 7f, 23f); horizontalLineTo(17f)
                curveTo(18.1f, 23f, 19f, 22.1f, 19f, 21f); verticalLineTo(3f); curveTo(19f, 1.9f, 18.1f, 1.01f, 17f, 1.01f); close()
                moveTo(17f, 19f); horizontalLineTo(7f); verticalLineTo(5f); horizontalLineTo(17f); close()
            }
        }
    }

    val Computer: ImageVector by lazy {
        materialIcon(name = "Filled.Computer") {
            materialPath {
                moveTo(20f, 18f); curveTo(21.1f, 18f, 21.99f, 17.1f, 21.99f, 16f)
                lineTo(22f, 6f); curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
                horizontalLineTo(4f); curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(16f); curveTo(2f, 17.1f, 2.9f, 18f, 4f, 18f)
                horizontalLineTo(0f); verticalLineTo(20f); horizontalLineTo(24f); verticalLineTo(18f); close()
                moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(16f); horizontalLineTo(4f); close()
            }
        }
    }

    val Folder: ImageVector by lazy {
        materialIcon(name = "Filled.Folder") {
            materialPath {
                moveTo(10f, 4f); horizontalLineTo(4f)
                curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                verticalLineTo(18f); curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                horizontalLineTo(20f); curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(8f); curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                horizontalLineTo(12f); close()
            }
        }
    }

    val ArrowUpward: ImageVector by lazy {
        materialIcon(name = "Filled.ArrowUpward") {
            materialPath {
                moveTo(4.0f, 12.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 7.83f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.83f)
                lineToRelative(5.58f, 5.59f)
                lineTo(20.0f, 12.0f)
                lineToRelative(-8.0f, -8.0f)
                close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        materialIcon(name = "Filled.Stop") {
            materialPath {
                moveTo(6.0f, 6.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(12.0f)
                horizontalLineTo(6.0f)
                close()
            }
        }
    }

    val ContentCopy: ImageVector by lazy {
        materialIcon(name = "Outlined.ContentCopy") {
            materialPath {
                moveTo(16.0f, 1.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(12.0f)
                verticalLineTo(1.0f)
                close()
                moveTo(19.0f, 5.0f)
                horizontalLineTo(8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(11.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(19.0f, 21.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(7.0f)
                horizontalLineToRelative(11.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        materialIcon(name = "Filled.VolumeUp") {
            materialPath {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                verticalLineTo(4.0f)
                lineTo(7.0f, 9.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
            }
        }
    }

    /** A simple torch outline, drawn here because Material's flashlight icon is extended-only. */
    val Flashlight: ImageVector by lazy {
        materialIcon(name = "Filled.Flashlight") {
            materialPath {
                moveTo(6.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(6.0f)
                close()
                moveTo(6.0f, 6.5f)
                lineToRelative(2.0f, 3.5f)
                verticalLineToRelative(12.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(10.0f)
                lineToRelative(2.0f, -3.5f)
                close()
            }
        }
    }

    val Mic: ImageVector by lazy {
        materialIcon(name = "Filled.Mic") {
            addPath(
                pathData = addPathNodes(
                    "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                        "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28" +
                        "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
                ),
                fill = SolidColor(Color.Black),
            )
        }
    }

    val MicOff: ImageVector by lazy {
        materialIcon(name = "Filled.MicOff") {
            addPath(
                pathData = addPathNodes(
                    "M19,11h-1.7c0,0.74 -0.16,1.43 -0.43,2.05l1.23,1.23c0.56,-0.98 0.9,-2.09 0.9,-3.28z" +
                        "M14.98,11.17c0,-0.06 0.02,-0.11 0.02,-0.17L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v0.18l5.98,5.99z" +
                        "M4.27,3L3,4.27l6.01,6.01L9.01,11c0,1.66 1.33,3 2.99,3 0.22,0 0.44,-0.03 0.65,-0.08l1.66,1.66c-0.71,0.33 -1.5,0.52 -2.31,0.52 " +
                        "-2.76,0 -5.3,-2.1 -5.3,-5.1L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c0.91,-0.13 1.77,-0.45 2.54,-0.9L19.73,21 21,19.73 4.27,3z",
                ),
                fill = SolidColor(Color.Black),
            )
        }
    }
}
