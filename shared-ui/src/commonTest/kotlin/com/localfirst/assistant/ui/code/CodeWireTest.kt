package com.localfirst.assistant.ui.code
import com.localfirst.assistant.ui.wire.JSONObject
import com.localfirst.assistant.ui.wire.JSONArray
import kotlin.test.*
class CodeWireTest {
    @Test fun localProjectsAndRequiredChecksParseOnBothClients() {
        val project = CodeProject.from(JSONObject("""{"id":"p","repo":"local/demo","kind":"local","checks":[{"name":"Unit","command":"pytest","min_tests":5}]}"""))
        assertEquals("local", project.kind)
        assertEquals(5, project.requiredChecks.single().minTests)
        assertEquals("pytest", CodeRequiredCheck.from(project.requiredChecks.single().json()).command)
        val verification = CodeVerification.from(JSONObject("""{"state":"unverified","required":[{"name":"Unit","state":"missing"}]}"""))
        assertEquals("missing", verification.required.single().state)
        val task = CodeTaskView.from(JSONObject("""{"id":"task","mode":"plan","kind":"local","checkpoints":[{"id":"snapshot","label":"Before turn","files":3}]}"""))
        assertEquals("plan", task.task.mode)
        assertEquals("local", task.task.kind)
        assertEquals(3, task.checkpoints.single().files)
    }
    @Test fun verificationDefaultsAndStaleEvidenceMatchOnBothClients() {
        assertEquals("Not verified", CodeTaskView.from(JSONObject("""{"id":"task"}""")).verification.label)
        val view = CodeTaskView.from(JSONObject("""{"id":"task","verification":{"state":"stale","receipts":[{"command":"pytest","result":"passed","current":false,"exit":0,"tests":3,"output_excerpt":"3 passed"},{"command":"custom check","exit":null,"tests":null}]}}"""))
        assertEquals("Checks are out of date", view.verification.label)
        assertFalse(view.verification.checks[0].current)
        assertEquals(3, view.verification.checks[0].tests)
        assertEquals(0, view.verification.checks[0].exit)
        assertNull(view.verification.checks[1].exit)
        assertNull(view.verification.checks[1].tests)
    }
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
