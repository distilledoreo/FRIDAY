package com.localfirst.assistant.web.data

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
