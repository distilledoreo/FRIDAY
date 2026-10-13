package com.localfirst.assistant.ui
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.conversation.Attachment
@Composable
fun AttachmentStrip(attachments: List<Attachment>, enabled: Boolean, remove: (String) -> Unit) {
 Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
  attachments.forEach { file -> InputChip(false, { }, label = { Text(file.name) }, trailingIcon = { IconButton({ remove(file.id) }, enabled = enabled) { Icon(LineIcons.Close, "Remove ${file.name}") } }) }
 }
}
