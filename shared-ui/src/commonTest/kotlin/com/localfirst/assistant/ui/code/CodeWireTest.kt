package com.localfirst.assistant.ui.code
import com.localfirst.assistant.ui.wire.JSONObject
import com.localfirst.assistant.ui.wire.JSONArray
import kotlin.test.*
class CodeWireTest {
    @Test fun nestedWireEditsStayAttachedToTheirParent() {
        val json = JSONObject("""{"session":{},"model_choice":"local"}""")
        json.getJSONObject("session").put("timing", JSONObject("""{"elapsed_seconds":965.533,"models":["local"]}"""))
        val reparsed = JSONObject(json.toString())
        assertEquals(965.533, reparsed.getJSONObject("session").getJSONObject("timing").optDouble("elapsed_seconds"))
        val rows = JSONArray().put(json)
        json.put("model_choice", "auto")
        assertEquals("auto", rows.getJSONObject(0).optString("model_choice"))
        assertEquals("auto", JSONArray(rows.toString()).getJSONObject(0).optString("model_choice"))
    }
    @Test fun absentTimingAndRealNumbersMatchOnJvmAndJs() {
        val json = JSONObject("""{"whole":1,"fraction":0.5,"flag":true,"nothing":null}""")
        assertTrue(json.optDouble("missing").isNaN())
        assertEquals(1, json.optInt("whole"))
        assertEquals(.5, json.optDouble("fraction"))
        assertTrue(json.optBoolean("flag"))
        assertEquals("fallback", json.optString("nothing", "fallback"))
    }
}
