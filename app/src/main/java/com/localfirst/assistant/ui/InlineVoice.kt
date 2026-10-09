package com.localfirst.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.theme.LocalFridayPalette
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sin

/** Voice mode inside the conversation: a live waveform, what was heard, and round controls. */
@Composable
internal fun InlineVoice(
    voice: VoiceUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onInterrupt: () -> Unit,
    onClose: () -> Unit,
    sharingScreen: Boolean = false,
    onCamera: (() -> Unit)? = null,
    onShareScreen: (() -> Unit)? = null,
) {
    val palette = LocalFridayPalette.current
    val paused = voice.phase == VoicePhase.PAUSED
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        VoiceWave(voice.phase, voice.level)
        Text(
            when (voice.phase) {
                VoicePhase.STARTING -> "Starting microphone…"
                VoicePhase.LISTENING -> "Listening"
                VoicePhase.THINKING -> "Thinking"
                VoicePhase.SPEAKING -> "Speaking"
                VoicePhase.CONFIRMING -> "Your approval is needed"
                VoicePhase.PAUSED -> "Voice paused"
            },
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val words = when (voice.phase) {
            VoicePhase.LISTENING, VoicePhase.CONFIRMING -> voice.heard
            VoicePhase.SPEAKING -> voice.speaking
            else -> ""
        }
        if (words.isNotBlank()) {
            Text(
                words,
                style = MaterialTheme.typography.titleMedium,
                color = if (voice.phase == VoicePhase.SPEAKING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        voice.note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            VoiceControl(if (paused) "Resume" else "Pause", if (paused) onResume else onPause) {
                if (paused) Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
                else PauseGlyph(MaterialTheme.colorScheme.onSurface)
            }
            if (voice.phase == VoicePhase.SPEAKING || voice.phase == VoicePhase.THINKING) {
                VoiceControl("Interrupt", onInterrupt) { Icon(AppIcons.Mic, null, Modifier.size(22.dp)) }
            }
            VoiceControl("End", onClose, description = "End voice conversation",
                container = if (palette.dark) Color(0xFF4A2626) else Color(0xFFF7DEDB), content = if (palette.dark) Color(0xFFF2B8B5) else Color(0xFF8C1D18)) {
                Icon(Icons.Filled.Close, null, Modifier.size(22.dp))
            }
        }
        if (onCamera != null || onShareScreen != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                onCamera?.let { take ->
                    VoiceControl("Show FRIDAY", take, description = "Take a photo to include with your next message") {
                        Icon(LineIcons.Camera, null, Modifier.size(22.dp))
                    }
                }
                onShareScreen?.let { share ->
                    VoiceControl(if (sharingScreen) "Stop sharing" else "Share screen", share,
                        description = if (sharingScreen) "Stop including your screen" else "Include your screen with your next message") {
                        Icon(LineIcons.Screen, null, Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceControl(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    container: Color? = null,
    content: Color? = null,
    icon: @Composable () -> Unit,
) {
    val dark = LocalFridayPalette.current.dark
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = container ?: if (dark) Color.White.copy(alpha = .08f) else Color.Black.copy(alpha = .06f),
            contentColor = content ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(52.dp),
        ) { Box(contentAlignment = Alignment.Center) { icon() } }
        Text(label, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PauseGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width * .2f
        drawRoundRect(color, Offset(size.width * .22f, size.height * .15f), Size(w, size.height * .7f), CornerRadius(w / 2))
        drawRoundRect(color, Offset(size.width * .58f, size.height * .15f), Size(w, size.height * .7f), CornerRadius(w / 2))
    }
}

/** Bars that follow the mic while listening and move softly while FRIDAY thinks or speaks. */
@Composable
private fun VoiceWave(phase: VoicePhase, level: Float) {
    val palette = LocalFridayPalette.current
    var time by remember { mutableFloatStateOf(0f) }
    val moving = phase != VoicePhase.PAUSED && !palette.reducedMotion
    LaunchedEffect(moving) { while (moving && isActive) { delay(33); time += .033f } }
    val heard by animateFloatAsState(level.coerceIn(0f, 1f), tween(120), label = "voice level")
    val energy = when (phase) {
        VoicePhase.LISTENING -> .18f + .82f * heard
        VoicePhase.SPEAKING -> .55f
        VoicePhase.THINKING -> .22f
        else -> .1f
    }
    Canvas(Modifier.width(220.dp).height(44.dp)) {
        val bars = 25
        val gap = size.width / bars
        for (i in 0 until bars) {
            val center = abs(i - bars / 2f) / (bars / 2f)
            val shape = (1f - center * center).coerceAtLeast(.08f)
            val wobble = .55f + .45f * sin(time * (if (phase == VoicePhase.THINKING) 2.2f else 7f) + i * .7f)
            val h = size.height * (.08f + .92f * shape * energy * wobble).coerceIn(.08f, 1f)
            val x = gap * (i + .5f)
            drawRoundRect(
                palette.accent.copy(alpha = .35f + .65f * (1f - center)),
                Offset(x - gap * .2f, (size.height - h) / 2f), Size(gap * .4f, h), CornerRadius(gap * .2f),
            )
        }
    }
}
