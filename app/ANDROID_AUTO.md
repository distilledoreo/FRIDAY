# Android Auto phone microphone

FRIDAY's car screen offers **Talk to FRIDAY**, **Pause**, **Resume**, and **Stop voice**.
Talk uses FRIDAY's computer voice service (Parakeet/Kokoro), requests the phone's
built-in microphone, and uses media-style playback for replies. It checks the actual
recording device before sending captured speech. A rejected or changed microphone
route stops listening with an error rather than falling back to the stereo mic.
The existing messaging notification and conversation **Reply** actions still use
Android Auto's own voice capture; use **Talk** for the phone microphone.

## Try it while parked

1. Install the new APK. Enable Android Auto in FRIDAY's **Voice and server** settings,
   save a working search/voice service connection, and grant microphone permission.
2. Enable **Unknown sources** in Android Auto developer settings for the sideloaded app.
   The templated messaging screen requires a host supporting Car App API 7 or newer.
3. Connect Android Auto and open FRIDAY from its app launcher. Tap **Talk to FRIDAY**.
4. If Android blocks background microphone startup, open FRIDAY on the phone and tap
   **Prepare car voice** in **Voice and server** while parked. Then return to the car screen.
5. Verify speech reaches FRIDAY through the phone mic and replies play through the
   stereo. Test Pause/Resume, Stop, phone lock, and disconnect. Disconnect ends a
   car voice session; it does not stop an independently started phone voice session.

The computer voice service must be reachable. Android's platform speech recognizer
is not used as a fallback for this mode because its recording device cannot be pinned.
Routing and microphone foreground-service eligibility vary by phone and Android Auto
host; unit tests and an APK build cannot verify the actual stereo or locked-phone behavior.
Media playback routing remains controlled by Android and the connected stereo.

Templated messaging is currently limited to Google Play internal/closed testing tracks:
https://developer.android.com/training/cars/communication/templated-messaging
This implementation is for testing with the sideloaded app, not a verified Play release.
