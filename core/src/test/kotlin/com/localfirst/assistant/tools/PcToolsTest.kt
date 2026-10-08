package com.localfirst.assistant.tools

import com.localfirst.assistant.conversation.ChatPrivacy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PcToolsTest {
    private class Gateway : WorkspaceGateway {
        val calls = mutableListOf<Triple<String,String,String?>>()
        override suspend fun toolRequest(path:String,method:String,body:String?):String {
            calls += Triple(path,method,body)
            return "{\"id\":\"ses_owned\",\"title\":\"Synthetic task\"}"
        }
    }
    @Test fun pcToolsRequireConsentAndCannotReplyToPermissions() = runBlocking {
        val gateway=Gateway()
        val tools=pcTools(gateway)
        val registry=ToolRegistry().apply { tools.forEach(::register) }
        assertEquals(setOf("run_on_pc","get_pc_task"),tools.map { it.name }.toSet())
        assertTrue(tools.all { it.requiresConfirmation })
        assertTrue(tools.none { ChatPrivacy(incognito=true).allowsTool(it.name) })
        val call=ToolCall("owned","run_on_pc","""{"prompt":"Inspect the owned fixture","title":"Synthetic task"}""")
        registry.execute(call,ToolConfirmer { false })
        assertTrue(gateway.calls.isEmpty())
        registry.execute(call,ToolConfirmer { request-> assertTrue(request.prompt.contains("Free cloud"));true })
        val result=gateway.calls.single()
        assertEquals("/workspace/pc/sessions",result.first)
        assertEquals("POST",result.second)
        assertEquals("chat",Json.parseToJsonElement(result.third!!).jsonObject["source"]!!.jsonPrimitive.content)
    }
    @Test fun taskReadRejectsPathInjectionAndEmptyTaskIsRejected() = runBlocking {
        val gateway=Gateway()
        val tools=pcTools(gateway)
        val read=tools.first { it.name=="get_pc_task" }
        read.execute(buildJsonObject { put("id","ses_owned") })
        assertEquals("/workspace/pc/sessions/ses_owned",gateway.calls.single().first)
        for(id in listOf("../permissions/p","ses_owned?approve=true","")) {
            try { read.execute(buildJsonObject { put("id",id) });fail("Unsafe id accepted") } catch(_:IllegalArgumentException) {}
        }
        try { tools.first { it.name=="run_on_pc" }.execute(buildJsonObject { put("prompt"," ") });fail("Empty prompt accepted") } catch(_:IllegalArgumentException) {}
        assertEquals(1,gateway.calls.size)
    }
}
