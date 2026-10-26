package com.localfirst.assistant.ui
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
@Composable
internal fun Composer(
    draft: String, busy: Boolean, editing: Boolean, onDraftChange: (String) -> Unit,
    onSend: () -> Unit, onStop: () -> Unit, onCancelEdit: () -> Unit,
    onVoice: (() -> Unit)?, voiceActive: Boolean = false, sendEnabled: Boolean,
    attachments: List<DraftAttachment> = emptyList(), editingHasAttachments: Boolean = false,
    onAttach: () -> Unit = {}, onRemoveAttachment: (String) -> Unit = {},
    imageMode: Boolean = false, imageSummary: String = "Auto", onImageSettings: () -> Unit = {},
    onExitImageMode: () -> Unit = {}, onDictate: (() -> Unit)? = null, dictating: Boolean = false,
    thinking: com.localfirst.assistant.settings.ThinkingLevel? = null,
    onThinking: ((com.localfirst.assistant.settings.ThinkingLevel) -> Unit)? = null,
    modifier: Modifier = Modifier,
) = SharedComposer(draft, busy, editing, onDraftChange, onSend, onStop, onCancelEdit,
    onVoice, voiceActive, sendEnabled, hasAttachments = attachments.isNotEmpty(),
    attachmentContent = { if (attachments.isNotEmpty()) DraftAttachmentStrip(attachments, onRemoveAttachment) },
    editingHasAttachments = editingHasAttachments, onAttach = onAttach, imageMode = imageMode,
    imageSummary = imageSummary, onImageSettings = onImageSettings, onExitImageMode = onExitImageMode,
    onDictate = onDictate, dictating = dictating, thinking = thinking, onThinking = onThinking, modifier = modifier)
