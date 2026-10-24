# Android Auto phone microphone

The sideloaded APK exposes an **experimental FRIDAY voice media source** using
MediaBrowserServiceCompat and MediaSessionCompat. Select its voice item (or Play)
to start/resume a session. The microphone custom action is **Talk to FRIDAY**; it
interrupts a reply to listen again. Standard Pause and Stop control the whole voice
session. The host may place Talk in its overflow menu.

Talk uses the computer voice service (Parakeet/Kokoro), requests the built-in phone
microphone, and verifies the actual input route before sending captured speech.
A rejected or changed route stops listening with an error. Replies use media-style
playback. Connecting or browsing does not start recording, and an idle FRIDAY
session stays inactive so it does not take over another media app's controls.
Only trusted car hosts, the Android system, and FRIDAY itself can issue commands.
Chat text and partial transcripts are never published in media metadata.

## Try it while parked

1. Install 0.25.4 or newer. Enable Android Auto in FRIDAY's **Voice and server**
   settings, save a working search/voice service connection, and grant microphone access.
2. Enable **Unknown sources** in Android Auto's developer settings. Disconnect
   and reconnect after installing; check **Customize launcher** for **FRIDAY voice**.
3. Open **FRIDAY voice** under the car's media apps, select its voice item, then try
   the microphone action. Pause/Play pause and resume; Stop ends the session.
4. If Android blocks microphone startup, open FRIDAY on the phone and tap
   **Prepare car voice** while parked, then return to the car media controls.
5. Test phone-mic speech, stereo replies, Talk during a reply, Pause/Play, Stop,
   locked-phone operation, other music apps, and disconnect. Disconnect ends a
   car voice session without ending independently started phone voice.

The notification Reply action still uses Android Auto's system microphone route.
The computer voice service must be reachable; this mode does not fall back to
Android speech recognition because its input device cannot be pinned.

## Limits and the earlier templated screen

The richer Car App Library messaging screen is retained in source but disabled
in this APK; its `template` declaration and minimum Car API metadata are removed
from the sideloaded manifest. **Unknown sources does not enable Car App Library
screens** in real vehicles. Such screens need a trusted installation source.

Android Auto documents custom controls for media playback and allows unknown-source
media apps. Using that interface for assistant voice is an experimental adaptation,
not a verified distribution category or a guarantee of compatibility. The media
adapter still needs testing with the actual phone and stereo; unit tests and an
APK build cannot establish discovery, routing, or background microphone eligibility.

References:
- https://developer.android.com/training/cars/testing#real-vehicles
- https://developer.android.com/training/cars/media/enable-playback
- https://developer.android.com/training/cars/communication/templated-messaging
