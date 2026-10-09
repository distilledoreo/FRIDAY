package com.localfirst.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localfirst.assistant.ui.theme.LocalFridayPalette

/** A round, softly filled header button, with an optional status dot. */
@Composable
internal fun HeaderCircleButton(
    description: String,
    onClick: () -> Unit,
    dot: Color? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalFridayPalette.current
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (palette.dark) Color.White.copy(alpha = .07f) else Color.Black.copy(alpha = .05f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(44.dp).semantics { contentDescription = description },
        ) { Box(contentAlignment = Alignment.Center) { content() } }
        if (dot != null) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp).size(9.dp).clip(CircleShape).background(dot)
                    .border(1.5.dp, MaterialTheme.colorScheme.background, CircleShape),
            )
        }
    }
}

/** Nine small dots, like an app grid. */
@Composable
internal fun DotsGridIcon(size: Dp = 18.dp, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(Modifier.size(size)) {
        val step = this.size.width / 3f
        for (row in 0 until 3) for (col in 0 until 3) {
            drawCircle(color, radius = step * .17f, center = Offset(step * (col + .5f), step * (row + .5f)))
        }
    }
}

/** Five rounded bars, like a voice waveform. */
@Composable
internal fun WaveformIcon(size: Dp = 20.dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val heights = listOf(.28f, .62f, 1f, .62f, .28f)
        val gap = this.size.width / heights.size
        heights.forEachIndexed { i, h ->
            val x = gap * (i + .5f)
            val half = this.size.height * h / 2f
            drawLine(color, Offset(x, center.y - half), Offset(x, center.y + half), strokeWidth = gap * .38f, cap = StrokeCap.Round)
        }
    }
}

/** The profile circle: your initial (or a person glyph) and the app menu. */
@Composable
internal fun ProfileButton(name: String, dot: Color?, actions: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderCircleButton("Profile and menu", { open = true }, dot) {
            val initial = name.trim().firstOrNull()?.uppercaseChar()
            if (initial != null) Text(initial.toString(), fontSize = 17.sp, fontWeight = FontWeight.Normal)
            else Icon(LineIcons.Person, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            actions.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}
