package com.localfirst.assistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InlineVoice(voice:VoiceUiState,onPause:()->Unit,onResume:()->Unit,onInterrupt:()->Unit,onClose:()->Unit) {
    val accent=com.localfirst.assistant.ui.theme.LocalFridayPalette.current.accent
    Surface(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(when(voice.phase){VoicePhase.STARTING->"Starting microphone…";VoicePhase.LISTENING->"Listening";VoicePhase.THINKING->"Thinking";VoicePhase.SPEAKING->"Speaking";VoicePhase.CONFIRMING->"Your approval is needed";VoicePhase.PAUSED->"Voice paused"},Modifier.weight(1f).semantics { liveRegion=LiveRegionMode.Polite },style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(voice.phase==VoicePhase.LISTENING)androidx.compose.foundation.Canvas(Modifier.width(24.dp).height(16.dp).padding(end=8.dp)) {
                    repeat(3) { index -> val height=size.height*(.2f+voice.level.coerceIn(0f,1f)*(.5f+index*.1f));drawRoundRect(accent,topLeft=androidx.compose.ui.geometry.Offset(index*size.width/3,(size.height-height)/2),size=androidx.compose.ui.geometry.Size(size.width/6,height),cornerRadius=androidx.compose.ui.geometry.CornerRadius(2f)) }
                }
                IconButton(onClick=onClose) { Icon(AppIcons.Stop,"End voice conversation",Modifier.size(16.dp)) }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(shape = MaterialTheme.shapes.small, onClick=if(voice.phase==VoicePhase.PAUSED)onResume else onPause) { Text(if(voice.phase==VoicePhase.PAUSED)"Resume"else"Pause") }
                if(voice.phase==VoicePhase.SPEAKING||voice.phase==VoicePhase.THINKING)TextButton(shape = MaterialTheme.shapes.small, onClick=onInterrupt) { Text("Interrupt") }
            }
            if(voice.phase==VoicePhase.LISTENING&&voice.heard.isNotBlank())Text(voice.heard,Modifier.padding(bottom=8.dp),style=MaterialTheme.typography.bodyMedium)
            voice.note?.let { Text(it,Modifier.padding(bottom=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
