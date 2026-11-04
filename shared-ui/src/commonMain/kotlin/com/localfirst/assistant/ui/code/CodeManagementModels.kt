package com.localfirst.assistant.ui.code

import com.localfirst.assistant.ui.wire.JSONObject

data class CodeQueuedMessage(val id: Long, val prompt: String, val state: String, val error: String) {
    companion object { fun from(j: JSONObject) = CodeQueuedMessage(j.getLong("id"), j.optString("prompt"), j.optString("state"), j.optString("error").takeUnless { it == "null" }.orEmpty()) }
}
data class CodeQueue(val paused: Boolean = false, val messages: List<CodeQueuedMessage> = emptyList()) {
    companion object { fun from(j: JSONObject?) = if (j == null) CodeQueue() else CodeQueue(j.optBoolean("paused"), rows(j.optJSONArray("messages")).map(CodeQueuedMessage::from)) }
}
data class CodeConflicts(val merging: Boolean = false, val files: List<String> = emptyList()) {
    companion object { fun from(j: JSONObject?): CodeConflicts {
        val files = j?.optJSONArray("files")
        return CodeConflicts(j?.optBoolean("merging") == true, files?.let { (0 until it.length()).map(it::optString) }.orEmpty())
    } }
}
data class CodeReviewThread(val id: String, val path: String, val line: String, val body: String, val resolved: Boolean, val canResolve: Boolean) {
    companion object { fun from(j: JSONObject) = CodeReviewThread(j.getString("id"), j.optString("path"), j.optString("line").takeUnless { it == "null" }.orEmpty(),
        j.optString("body"), j.optBoolean("resolved"), j.optBoolean("can_resolve", true)) }
}
data class CodeCiJob(val name: String, val state: String, val url: String) {
    companion object { fun from(j: JSONObject) = CodeCiJob(j.optString("name"), j.optString("state"), j.optString("url")) }
}
data class CodeCollaboration(val head: String, val threads: List<CodeReviewThread>, val jobs: List<CodeCiJob>, val truncated: Boolean) {
    companion object { fun from(j: JSONObject) = CodeCollaboration(j.optString("head"), rows(j.optJSONArray("threads")).map(CodeReviewThread::from),
        rows(j.optJSONArray("jobs")).map(CodeCiJob::from), j.optBoolean("truncated")) }
}
