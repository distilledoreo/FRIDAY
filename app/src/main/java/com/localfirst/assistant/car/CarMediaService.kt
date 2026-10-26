package com.localfirst.assistant.car

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.car.app.HostInfo
import androidx.car.app.validation.HostValidator
import androidx.media.MediaBrowserServiceCompat
import com.localfirst.assistant.AssistantApp
import com.localfirst.assistant.MainActivity
import com.localfirst.assistant.voice.VoicePhase
import com.localfirst.assistant.voice.VoiceUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal const val CAR_VOICE_ITEM = "friday_voice"
internal const val CAR_TALK_ACTION = "com.localfirst.assistant.car.TALK"
private const val ROOT = "friday_media"

/** Experimental sideloadable controls; audio still belongs to the microphone foreground service. */
class CarMediaService : MediaBrowserServiceCompat() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSessionCompat
    private lateinit var clients: TrustedCarMediaClients
    private var ownsVoiceSession = false
    private val chat get() = (application as AssistantApp).chat()

    override fun onCreate() {
        super.onCreate()
        clients = TrustedCarMediaClients(this)
        session = MediaSessionCompat(this, "FRIDAY car voice").apply {
            setSessionActivity(PendingIntent.getActivity(this@CarMediaService, 70,
                Intent(this@CarMediaService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            setCallback(CarMediaControls(
                allowed = { chat.state.value.settings.androidAuto && clients.allowsUid(currentControllerInfo.uid) },
                play = { ownsVoiceSession = true; chat.startCarVoice() },
                talk = {
                    ownsVoiceSession = true
                    if (chat.carVoiceActive && chat.voiceState.value?.phase in setOf(VoicePhase.SPEAKING, VoicePhase.THINKING)) {
                        chat.interruptVoice()
                    } else chat.startCarVoice()
                },
                pause = { if (chat.carVoiceActive) chat.pauseVoiceFromChat() },
                stop = { chat.stopCarVoice() },
            ))
            setPlaybackState(carMediaPlayback(null, null, enabled = true))
            setMetadata(carMediaMetadata("Ready • phone microphone"))
            isActive = false
        }
        sessionToken = session.sessionToken
        scope.launch {
            combine(chat.state, chat.voiceState) { state, voice ->
                CarMediaSnapshot(state.settings.androidAuto, voice.takeIf { chat.carVoiceActive }?.let {
                    // Partial speech, audio levels, and chat content never enter media metadata.
                    it.copy(heard = "", speaking = "", level = 0f)
                }, state.voiceError)
            }.distinctUntilChanged().collect { snapshot ->
                if (!snapshot.enabled) chat.stopCarVoice()
                session.isActive = snapshot.enabled && snapshot.voice != null
                session.setPlaybackState(carMediaPlayback(snapshot.voice, snapshot.error, snapshot.enabled))
                session.setMetadata(carMediaMetadata(if (snapshot.enabled)
                    carVoiceStatus(snapshot.voice, snapshot.error) else "Android Auto is off in FRIDAY settings"))
                notifyChildrenChanged(ROOT)
            }
        }
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!clients.allows(clientPackageName, clientUid)) return null
        return BrowserRoot(ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (parentId == ROOT && chat.state.value.settings.androidAuto) {
            items += MediaBrowserCompat.MediaItem(MediaDescriptionCompat.Builder()
                .setMediaId(CAR_VOICE_ITEM).setTitle("FRIDAY voice")
                .setSubtitle("Phone microphone • select to start or resume")
                .setIconUri(android.net.Uri.parse("android.resource://$packageName/${com.localfirst.assistant.R.drawable.ic_car_talk}"))
                .build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
        result.sendResult(items)
    }

    override fun onDestroy() {
        scope.cancel()
        if (ownsVoiceSession) chat.stopCarVoice()
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}

private data class CarMediaSnapshot(val enabled: Boolean, val voice: VoiceUiState?, val error: String?)

/** Do not expose microphone commands to arbitrary apps that discover the exported browser. */
internal class TrustedCarMediaClients(private val context: Context) {
    private val validator = HostValidator.Builder(context)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()

    fun allows(packageName: String, uid: Int): Boolean =
        context.packageManager.getPackagesForUid(uid)?.contains(packageName) == true && allowsUid(uid)

    fun allowsUid(uid: Int): Boolean {
        if (uid == context.applicationInfo.uid || uid == Process.SYSTEM_UID) return true
        return context.packageManager.getPackagesForUid(uid).orEmpty().any { name ->
            runCatching { validator.isValidHost(HostInfo(name, uid)) }.getOrDefault(false)
        }
    }
}

internal class CarMediaControls(
    private val allowed: () -> Boolean,
    private val play: () -> Unit,
    private val talk: () -> Unit,
    private val pause: () -> Unit,
    private val stop: () -> Unit,
) : MediaSessionCompat.Callback() {
    override fun onPlay() { if (allowed()) play() }
    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) { if (mediaId == CAR_VOICE_ITEM) onPlay() }
    override fun onPlayFromSearch(query: String?, extras: Bundle?) { onPlay() }
    override fun onPause() { if (allowed()) pause() }
    override fun onStop() { if (allowed()) stop() }
    override fun onCustomAction(action: String?, extras: Bundle?) {
        if (action == CAR_TALK_ACTION && allowed()) talk()
    }
    // Browsing/connecting/preparing must never activate the microphone.
}

internal fun carMediaPlayback(voice: VoiceUiState?, error: String?, enabled: Boolean): PlaybackStateCompat {
    val state = when {
        !enabled || error != null -> PlaybackStateCompat.STATE_ERROR
        voice == null -> PlaybackStateCompat.STATE_STOPPED
        voice.phase == VoicePhase.SPEAKING || voice.phase == VoicePhase.CONFIRMING -> PlaybackStateCompat.STATE_PLAYING
        voice.phase == VoicePhase.STARTING || voice.phase == VoicePhase.THINKING -> PlaybackStateCompat.STATE_BUFFERING
        else -> PlaybackStateCompat.STATE_PAUSED
    }
    return PlaybackStateCompat.Builder().apply {
        setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
        if (enabled) {
            setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
            addCustomAction(PlaybackStateCompat.CustomAction.Builder(CAR_TALK_ACTION, "Talk to FRIDAY",
                com.localfirst.assistant.R.drawable.ic_car_talk).build())
        }
        if (error != null) setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, error)
        else if (!enabled) setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, "Enable Android Auto in FRIDAY settings")
    }.build()
}

private fun carMediaMetadata(status: String): MediaMetadataCompat = MediaMetadataCompat.Builder()
    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, CAR_VOICE_ITEM)
    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "FRIDAY voice")
    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "FRIDAY voice")
    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, status.take(220))
    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, status.take(220))
    .build()
