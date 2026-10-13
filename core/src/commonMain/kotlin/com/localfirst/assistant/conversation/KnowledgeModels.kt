package com.localfirst.assistant.conversation

import kotlinx.serialization.Serializable

@Serializable
data class SavedMemory(val id: String, val text: String, val updatedAt: Long)

@Serializable
data class AssistantProject(val id: String, val name: String, val instructions: String = "", val context: String = "")

@Serializable
data class Knowledge(val memories: List<SavedMemory> = emptyList(), val projects: List<AssistantProject> = emptyList())
