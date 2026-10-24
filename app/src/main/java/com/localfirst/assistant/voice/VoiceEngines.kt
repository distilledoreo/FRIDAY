package com.localfirst.assistant.voice

import android.content.Context
import com.localfirst.assistant.settings.ServerSettings

/**
 * Picks the audio setup for a voice session: the computer (Parakeet + Kokoro
 * on the desktop assistant API at the search service address) when selected
 * and reachable, otherwise Android's own speech recognition and text-to-speech.
 */
class VoiceEngines(context: Context) {
    private val app = context.applicationContext
    private val phone by lazy { androidVoiceIo(app) }
    private var computer: Pair<String, VoiceIo>? = null
    @Volatile private var latest = ServerSettings()

    suspend fun select(settings: ServerSettings, forcePhoneMic: Boolean = false): VoiceSelection {
        latest = settings
        if (!forcePhoneMic && settings.voiceEngine != ServerSettings.VOICE_COMPUTER) return VoiceSelection(phone)
        val base = settings.searchBaseUrl.trim()
        if (base.isEmpty()) {
            check(!forcePhoneMic) { "Set the search service address on your phone to use the phone mic in Android Auto." }
            return VoiceSelection(phone, "${VoiceController.FALLBACK_PREFIX}: set the search service address to use your computer's voice.")
        }
        val key = listOf(base, settings.searchApiKey, settings.voiceName, forcePhoneMic.toString()).joinToString("|")
        val io = computer?.takeIf { it.first == key }?.second
            ?: computerVoiceIo(
                app,
                ComputerVoiceClient(base, settings.searchApiKey.trim().ifEmpty { null }),
                settings.voiceName,
                bargeInEnabled = { latest.voiceBargeIn },
                phoneMicDevices = { latest.voicePhoneMicDevices },
                forcePhoneMic = forcePhoneMic,
            ).also { computer = key to it }
        if (io.speaker.prepare()) return VoiceSelection(io)
        check(!forcePhoneMic) { "The computer voice service is unreachable. Check the connection on your phone." }
        return VoiceSelection(phone, "${VoiceController.FALLBACK_PREFIX}: your computer's voice service isn't reachable.")
    }
}
