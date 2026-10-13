@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.localfirst.assistant.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.ui.wire.*
private fun pcRows(j: JSONObject, key: String) = j.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull(a::optJSONObject) }.orEmpty()
private fun pcStrings(j: JSONObject, key: String) = j.optJSONArray(key)?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty()
@Composable
fun PcPermissionCard(request:JSONObject,enabled:Boolean,onReply:(String,String,Boolean)->Unit) {
    var rememberRule by remember(request.optString("id")) { mutableStateOf(false) }
    val patterns=pcStrings(request,"always")
    val id=request.getString("id")
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(if(request.optBoolean("sudo"))"Approve sudo command" else "FRIDAY needs your permission",style=MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(request.optString("detail"),style=MaterialTheme.typography.bodyMedium) }
            request.optString("diff").takeIf { it.isNotBlank()&&it!="null" }?.let { SelectionContainer { Text(it,style=MaterialTheme.typography.bodySmall) } }
            if(patterns.isNotEmpty()) {
                Text("Always allows: ${patterns.joinToString(", ")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(request.optString("kind") in listOf("bash","friday-computer_action"))Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(rememberRule,{rememberRule=it},enabled=enabled,modifier=Modifier.semantics { contentDescription="Remember for future tasks" })
                    Text("Remember for future tasks",style=MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onReply(id,"once",false)},enabled=enabled,shape=MaterialTheme.shapes.small) { Text("Allow once") }
                if(patterns.isNotEmpty())TextButton(onClick={onReply(id,"always",rememberRule)},enabled=enabled) { Text("Always for task") }
                TextButton(onClick={onReply(id,"reject",false)},enabled=enabled) { Text("Deny") }
            }
        }
    }
}

@Composable
fun PcQuestionCard(request:JSONObject,enabled:Boolean,onAnswer:(String,JSONArray?)->Unit) {
    val questions=pcRows(request,"questions")
    var answers by remember(request.optString("id")) { mutableStateOf(List(questions.size) { emptyList<String>() }) }
    var custom by remember(request.optString("id")) { mutableStateOf(List(questions.size) { "" }) }
    FridayCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            questions.forEachIndexed { index,question ->
                Text(question.optString("question"),style=MaterialTheme.typography.titleSmall)
                pcRows(question,"options").forEach { option ->
                    val label=option.optString("label")
                    val checked=label in answers[index]
                    Row(Modifier.fillMaxWidth().clickable(enabled=enabled) {
                        answers=answers.toMutableList().apply { this[index]=if(question.optBoolean("multiple"))if(checked)this[index]-label else this[index]+label else listOf(label) }
                    }.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked,onCheckedChange=null)
                        Column(Modifier.weight(1f)) { Text(label);option.optString("description").takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                }
                if(question.optBoolean("custom",true))OutlinedTextField(custom[index],{text->custom=custom.toMutableList().apply { this[index]=text.take(2000) }},label={Text("Your answer")},modifier=Modifier.fillMaxWidth(),enabled=enabled)
            }
            Row {
                Button(onClick={onAnswer(request.getString("id"),JSONArray(answers.indices.map { i->JSONArray(if(custom[i].isNotBlank())listOf(custom[i].trim()) else answers[i]) }))},enabled=enabled&&answers.indices.all { answers[it].isNotEmpty()||custom[it].isNotBlank() },shape=MaterialTheme.shapes.small) { Text("Answer") }
                TextButton(onClick={onAnswer(request.getString("id"),null)},enabled=enabled) { Text("Dismiss") }
            }
        }
    }
}
