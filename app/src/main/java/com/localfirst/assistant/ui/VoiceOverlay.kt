package com.localfirst.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState

@Composable
internal fun VoiceOverlay(
    voice: VoiceUiState,
    approval: PendingApproval?,
    bargeIn: Boolean,
    onOrbTap: () -> Unit,
    onAnswerApproval: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "End voice mode") }
            }

            Spacer(Modifier.weight(1f))
            Orb(voice = voice, onTap = onOrbTap)
            Spacer(Modifier.height(28.dp))
            Text(
                text = statusText(voice, bargeIn),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            voice.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            if (approval != null) {
                ApprovalCard(approval = approval, onAnswer = onAnswerApproval)
                Spacer(Modifier.height(16.dp))
            }
            val heard = voice.heard.takeIf { it.isNotBlank() }
            val spoken = voice.speaking.takeIf { it.isNotBlank() && voice.phase != VoicePhase.LISTENING }
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                heard?.let {
                    Text(
                        text = "“$it”",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                spoken?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (voice.phase == VoicePhase.PAUSED) {
                FilledTonalIconButton(onClick = onOrbTap, modifier = Modifier.size(64.dp)) {
                    Icon(AppIcons.Mic, contentDescription = "Talk", modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun Orb(voice: VoiceUiState, onTap: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val listeningScale by animateFloatAsState(1f + voice.level * 0.35f, label = "level")
    val scale = when (voice.phase) {
        VoicePhase.LISTENING, VoicePhase.CONFIRMING -> listeningScale
        VoicePhase.SPEAKING -> pulse
        else -> 1f
    }
    val colors = MaterialTheme.colorScheme
    val brush = remember(voice.phase, colors) {
        val (a, b) = when (voice.phase) {
            VoicePhase.LISTENING, VoicePhase.CONFIRMING -> colors.primary to colors.tertiary
            VoicePhase.SPEAKING -> colors.tertiary to colors.primary
            VoicePhase.PAUSED -> colors.surfaceContainerHigh to colors.surfaceContainerHighest
            else -> colors.secondary to colors.primary
        }
        Brush.linearGradient(listOf(a, b))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(180.dp)
            .scale(scale)
            .background(brush, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    ) {
        if (voice.phase == VoicePhase.THINKING || voice.phase == VoicePhase.STARTING) {
            CircularProgressIndicator(color = colors.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
        }
    }
}

private fun statusText(voice: VoiceUiState, bargeIn: Boolean): String = when (voice.phase) {
    VoicePhase.STARTING -> "Starting…"
    VoicePhase.LISTENING -> "Listening…"
    VoicePhase.THINKING -> "Thinking…"
    VoicePhase.SPEAKING -> if (bargeIn) "Speaking — talk or tap to interrupt" else "Speaking — tap to interrupt"
    VoicePhase.CONFIRMING -> "Say yes or no"
    VoicePhase.PAUSED -> "Paused"
}
