package com.localfirst.assistant.web.data

import com.localfirst.assistant.client.*
import kotlinx.browser.localStorage
import com.localfirst.assistant.platform.PlatformMessageDigest

/** Explicit migration only. Canonical history is on the PC, not in localStorage. */
fun legacyChats(): List<LegacyConversation> = runCatching { decodeLegacyChats(localStorage.getItem("friday.conversations")) }.getOrDefault(emptyList())
fun importId(legacyId: String): String {
    val hex = PlatformMessageDigest.sha1(("friday-web-legacy:$legacyId").encodeToByteArray()).take(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
}
/** getRandomValues also works on private LAN HTTP origins where randomUUID is unavailable. */
fun randomConversationId(): String {
    val bytes = js("new Uint8Array(16)")
    if (js("!!(globalThis.crypto && globalThis.crypto.getRandomValues)").unsafeCast<Boolean>()) js("globalThis.crypto.getRandomValues(bytes)")
    else for (i in 0 until 16) bytes[i] = kotlin.random.Random.nextInt(256)
    bytes[6] = (bytes[6].unsafeCast<Int>() and 15) or 64
    bytes[8] = (bytes[8].unsafeCast<Int>() and 63) or 128
    val hex = (0 until 16).joinToString("") { bytes[it].unsafeCast<Int>().toString(16).padStart(2, '0') }
    return "${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20,32)}"
}
fun removeLegacyToken() { localStorage.removeItem("friday.server.apiKey") }

/** The sign-in the user asked this browser to remember; local storage, cleared by Lock. */
/** Unsent draft text kept across a page reload (see DraftRecovery): in local storage while the sign-in is remembered,
 *  otherwise in session storage, which lasts only as long as the tab. */
object BrowserDraftRecovery : DraftRecovery {
    private const val KEY = "friday.drafts"
    private fun storage(): org.w3c.dom.Storage = if (BrowserSavedSignIn.load() != null) localStorage else kotlinx.browser.sessionStorage
    private fun all(): MutableMap<String, String> = runCatching {
        val saved = kotlinx.serialization.json.Json.parseToJsonElement(storage().getItem(KEY) ?: return mutableMapOf()) as kotlinx.serialization.json.JsonObject
        saved.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }.toMutableMap()
    }.getOrElse { mutableMapOf() }
    override fun load(key: String): String? = all()[key]
    override fun save(key: String, text: String?) {
        val drafts = all()
        drafts.remove(key)
        if (text != null) drafts[key] = text.take(20000)  // re-added last: the newest
        // The newest 50 drafts at most.
        val kept = drafts.entries.toList().takeLast(50)
        runCatching {
            if (kept.isEmpty()) storage().removeItem(KEY)
            else storage().setItem(KEY, kotlinx.serialization.json.JsonObject(kept.associate { it.key to kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
        }
    }
    override fun clear() { runCatching { localStorage.removeItem(KEY) }; runCatching { kotlinx.browser.sessionStorage.removeItem(KEY) } }
}

object BrowserSavedSignIn : SavedSignIn {
    private const val KEY = "friday.signin"
    override fun load(): Pair<String, String>? = runCatching {
        val value = localStorage.getItem(KEY) ?: return null
        val saved = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
        val base = (saved["base"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val token = (saved["token"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        if (token.isBlank()) null else base to token
    }.getOrNull()
    override fun save(baseUrl: String, token: String) {
        runCatching { localStorage.setItem(KEY, kotlinx.serialization.json.buildJsonObject { put("base", kotlinx.serialization.json.JsonPrimitive(baseUrl)); put("token", kotlinx.serialization.json.JsonPrimitive(token)) }.toString()) }
    }
    override fun clear() { runCatching { localStorage.removeItem(KEY) } }
}
