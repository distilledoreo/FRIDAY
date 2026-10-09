package com.localfirst.assistant.presentation

import com.localfirst.assistant.json.JsonCodec
import kotlinx.serialization.json.*

/** Small, known native catalog; values come only from a completed tool result. */
data class ResultEntry(val title:String,val details:List<String>)
data class ToolResponse(val entries:List<ResultEntry>,val total:Int,val fallback:String)

object ToolResponses {
    private val supported=setOf("upcoming_events","list_followups","read_account_calendar","read_account_inbox","list_connected_accounts","list_tasks","get_agent_report","read_daily_brief")
    private val titles=listOf("title","summary","subject","label","prompt","name")
    private val fields=listOf("start","end","location","due","status","provider","from","date","timezone","detail")
    fun parse(name:String,content:String):ToolResponse? {
        if(name !in supported || content.isBlank())return null
        if(content.length>100_000)return ToolResponse(emptyList(),0,content)
        val root=runCatching { JsonCodec.json.parseToJsonElement(content) }.getOrNull()
        val collection=when(root) {
            is JsonArray -> root
            is JsonObject -> listOf("events","messages","accounts","items","tasks","sections").firstNotNullOfOrNull { root[it] as? JsonArray } ?: JsonArray(listOf(root))
            else -> null
        } ?: return ToolResponse(emptyList(),0,content)
        val entries=collection.take(40).mapNotNull { value ->
            val row=value as? JsonObject ?: return@mapNotNull null
            fun scalar(key:String)=(row[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it!="null" }
            val title=titles.firstNotNullOfOrNull(::scalar) ?: scalar("kind") ?: return@mapNotNull null
            val detail=fields.mapNotNull { key -> scalar(key)?.let { "$key: $it" } }.take(6)
            ResultEntry(title,detail)
        }
        return ToolResponse(entries,collection.size,content)
    }
}
