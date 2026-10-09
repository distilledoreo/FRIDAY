package com.localfirst.assistant.phone

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.localfirst.assistant.tools.phone.AlarmRequest
import com.localfirst.assistant.tools.phone.AppMatcher
import com.localfirst.assistant.tools.phone.BatteryStatus
import com.localfirst.assistant.tools.phone.CalendarEntry
import com.localfirst.assistant.tools.phone.CalendarEventRequest
import com.localfirst.assistant.tools.phone.ContactMatch
import com.localfirst.assistant.tools.phone.InstalledApp
import com.localfirst.assistant.tools.phone.MediaAction
import com.localfirst.assistant.tools.phone.MusicKind
import com.localfirst.assistant.tools.phone.MusicRequest
import com.localfirst.assistant.tools.phone.NowPlaying
import com.localfirst.assistant.tools.phone.PhoneActionException
import com.localfirst.assistant.tools.phone.PhoneActions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The Android side of the phone tools. Every action goes through an explicit Android API or intent. */
class AndroidPhoneActions(context: Context) : PhoneActions,
    com.localfirst.assistant.tools.phone.PhoneAccessibilityActions by PhoneControlAccessibilityService.Actions() {
    private val context = context.applicationContext
    private val pm: PackageManager get() = context.packageManager

    // ---- apps, web, maps -------------------------------------------------------

    override suspend fun openApp(name: String): String {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = withContext(Dispatchers.IO) {
            pm.queryIntentActivities(launcher, 0)
                .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
        }
        val app = AppMatcher.best(name, apps)
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: throw PhoneActionException("${app.label} can't be opened from here.")
        start(intent)
        return "Opened ${app.label}."
    }

    override suspend fun openUrl(url: String): String {
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        return "Opened ${Uri.parse(url).host ?: url}."
    }

    override suspend fun openMaps(query: String, navigate: Boolean): String {
        val googleMaps = isInstalled(GOOGLE_MAPS)
        if (navigate && googleMaps) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(query))).setPackage(GOOGLE_MAPS))
            return "Started navigation to $query in Google Maps."
        }
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))))
        return if (navigate) {
            "Opened $query in the maps app; tap Directions to start navigation."
        } else {
            "Showing $query in the maps app."
        }
    }

    // ---- media & Spotify -------------------------------------------------------

    override suspend fun mediaControl(action: MediaAction): String {
        activeController()?.let { controller ->
            val controls = controller.transportControls
            val app = appLabel(controller.packageName)
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            return when (action) {
                MediaAction.PLAY -> controls.play().let { "Resumed $app." }
                MediaAction.PAUSE -> controls.pause().let { "Paused $app." }
                MediaAction.TOGGLE -> if (playing) controls.pause().let { "Paused $app." } else controls.play().let { "Resumed $app." }
                MediaAction.NEXT -> controls.skipToNext().let { "Skipped to the next track in $app." }
                MediaAction.PREVIOUS -> controls.skipToPrevious().let { "Went back a track in $app." }
            }
        }
        // Without notification access, a media key goes to whichever player is active.
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.TOGGLE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Sent ${action.wireName.replace('_', '/')} to the active media player. " +
            "The phone can't confirm which app received it."
    }

    override suspend fun playMusic(request: MusicRequest): String {
        if (!isInstalled(SPOTIFY)) throw PhoneActionException("Spotify isn't installed on this phone.")
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY).apply {
            putExtra(SearchManager.QUERY, request.query)
            when (request.kind) {
                MusicKind.ANY -> putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                MusicKind.TRACK -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, request.query)
                }
                MusicKind.ARTIST -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, request.query)
                }
                MusicKind.ALBUM -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ALBUM, request.query)
                }
                MusicKind.PLAYLIST -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                    putExtra(EXTRA_MEDIA_PLAYLIST, request.query)
                }
            }
        }
        try {
            start(intent, translateMissing = false)
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(request.query))))
            return "Spotify couldn't start playback directly, so I opened a Spotify search for \"${request.query}\"."
        }
        return "Asked Spotify to play \"${request.query}\". Use now_playing to check what started."
    }

    override suspend fun nowPlaying(): NowPlaying? {
        if (!MediaListenerService.isEnabled(context)) {
            throw PhoneActionException(
                "To see what's playing, the user needs to allow notification access for Assistant: " +
                    "open the app's Settings and tap \"Allow notification access\".",
            )
        }
        val controller = activeController() ?: return null
        val metadata = controller.metadata
        return NowPlaying(
            app = appLabel(controller.packageName),
            title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    // ---- clock & device ----------------------------------------------------------

    override suspend fun setAlarm(alarm: AlarmRequest): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            alarm.label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            if (alarm.days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(alarm.days.map(::calendarDay)))
        }
        start(intent)
        val time = LocalDateTime.of(2000, 1, 1, alarm.hour, alarm.minute).format(TIME)
        val repeat = if (alarm.days.isEmpty()) "" else ", repeating ${alarm.days.joinToString { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }}"
        val label = alarm.label?.let { " ($it)" }.orEmpty()
        return "Set an alarm for $time$label$repeat in the Clock app."
    }

    override suspend fun setTimer(seconds: Int, label: String?): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        }
        start(intent)
        return "Started a timer for ${seconds / 60} min ${seconds % 60} sec in the Clock app."
    }

    override suspend fun setFlashlight(on: Boolean): String {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull { id ->
            val c = cameras.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: throw PhoneActionException("This phone has no flashlight the app can use.")
        try {
            cameras.setTorchMode(id, on)
        } catch (e: CameraAccessException) {
            throw PhoneActionException("The flashlight is in use by another app, such as the camera.")
        }
        return "Turned the flashlight ${if (on) "on" else "off"}."
    }

    override suspend fun batteryStatus(): BatteryStatus {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: throw PhoneActionException("Couldn't read the battery.")
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val source = when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> null
        }
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val minutes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && charging) {
            context.getSystemService(BatteryManager::class.java).computeChargeTimeRemaining()
                .takeIf { it > 0 }?.let { (it / 60_000).toInt() }
        } else {
            null
        }
        return BatteryStatus(percent = level * 100 / scale, charging = charging, source = source, minutesToFull = minutes)
    }

    // ---- contacts, calls, texts --------------------------------------------------

    override suspend fun searchContacts(query: String, limit: Int): List<ContactMatch> {
        require(Manifest.permission.READ_CONTACTS, "contacts")
        return withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            )
            val out = mutableListOf<ContactMatch>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && out.size < limit * 3) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        cursor.getInt(2),
                        cursor.getString(3),
                    ).toString().lowercase(Locale.getDefault())
                    out += ContactMatch(name, number, type)
                }
            }
            out.distinctBy { it.name to it.number.filter(Char::isDigit) }
                .groupBy { it.name }
                .entries.take(limit)
                .flatMap { it.value }
        }
    }

    override suspend fun placeCall(number: String): String {
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) throw PhoneActionException("This device can't make phone calls.")
        require(Manifest.permission.CALL_PHONE, "phone calls")
        start(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        return "Calling $number."
    }

    override suspend fun sendText(number: String, message: String): String {
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) throw PhoneActionException("This device can't send texts.")
        require(Manifest.permission.SEND_SMS, "sending texts")
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(message)
        val action = "${context.packageName}.SMS_SENT.${requestCodes.incrementAndGet()}"
        val results = CompletableDeferred<List<Int>>()
        val codes = mutableListOf<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                synchronized(codes) {
                    codes += resultCode
                    if (codes.size == parts.size) results.complete(codes.toList())
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(
                parts.indices.map { i ->
                    PendingIntent.getBroadcast(
                        context,
                        requestCodes.incrementAndGet(),
                        Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                    )
                },
            )
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            val outcome = withTimeoutOrNull(30_000) { results.await() }
                ?: return "The text to $number was handed to the phone, but it hasn't confirmed sending yet."
            if (outcome.all { it == Activity.RESULT_OK }) return "Sent the text to $number."
            throw PhoneActionException("The phone couldn't send the text to $number (no signal, or the carrier refused it).")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    // ---- calendar ----------------------------------------------------------------

    override suspend fun addCalendarEvent(event: CalendarEventRequest): String {
        require(Manifest.permission.WRITE_CALENDAR, "the calendar", Manifest.permission.READ_CALENDAR)
        return withContext(Dispatchers.IO) {
            val (calendarId, calendarName) = writableCalendar()
            val zone = ZoneId.systemDefault()
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, event.title)
                event.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                event.notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                val allDayDate = event.allDayDate
                if (allDayDate != null) {
                    val start = allDayDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    put(CalendarContract.Events.DTSTART, start)
                    put(CalendarContract.Events.DTEND, start + DAY_MILLIS)
                    put(CalendarContract.Events.ALL_DAY, 1)
                    put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                } else {
                    put(CalendarContract.Events.DTSTART, event.start!!.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.DTEND, event.end!!.atZone(zone).toInstant().toEpochMilli())
                    put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                }
            }
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw PhoneActionException("The calendar didn't accept the event.")
            val start = event.start
            val whenText = event.allDayDate?.let { "${it.format(DAY)} (all day)" }
                ?: "${start!!.format(DAY)}, ${start.format(TIME)}–${event.end!!.format(TIME)}"
            "Added \"${event.title}\" on $whenText to the $calendarName calendar."
        }
    }

    override suspend fun upcomingEvents(from: LocalDateTime, to: LocalDateTime): List<CalendarEntry> {
        require(Manifest.permission.READ_CALENDAR, "the calendar")
        return withContext(Dispatchers.IO) {
            val zone = ZoneId.systemDefault()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from.atZone(zone).toInstant().toEpochMilli())
                ContentUris.appendId(it, to.atZone(zone).toInstant().toEpochMilli())
            }.build()
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            )
            val out = mutableListOf<CalendarEntry>()
            context.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 50) {
                    val allDay = c.getInt(3) == 1
                    // All-day events are stored at UTC midnight.
                    val eventZone = if (allDay) ZoneOffset.UTC else zone
                    fun time(millis: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), eventZone)
                    out += CalendarEntry(
                        title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(no title)",
                        start = time(c.getLong(1)),
                        end = if (allDay) null else time(c.getLong(2)),
                        allDay = allDay,
                        location = c.getString(4),
                    )
                }
            }
            out
        }
    }

    // ---- helpers -----------------------------------------------------------------

    private fun writableCalendar(): Pair<Long, String> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val calendars = mutableListOf<Triple<Long, String, Boolean>>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) calendars += Triple(c.getLong(0), c.getString(1) ?: "default", c.getInt(2) == 1)
        }
        val pick = calendars.firstOrNull { it.third } ?: calendars.firstOrNull()
            ?: throw PhoneActionException("This phone has no calendar the app can add events to.")
        return pick.first to pick.second
    }

    private fun activeController(): MediaController? {
        if (!MediaListenerService.isEnabled(context)) return null
        val sessions = try {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(MediaListenerService.component(context))
        } catch (e: SecurityException) {
            return null
        }
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }

    private suspend fun require(permission: String, what: String, vararg also: String) {
        if (!PermissionBroker.ensure(context, permission, *also)) {
            throw PhoneActionException(
                "Access to $what wasn't granted. The user can allow it in Android Settings → Apps → Assistant → Permissions.",
            )
        }
    }

    private fun isInstalled(packageName: String) = pm.getLaunchIntentForPackage(packageName) != null

    private fun appLabel(packageName: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    private suspend fun start(intent: Intent, translateMissing: Boolean = true) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) {
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                if (!translateMissing) throw e
                throw PhoneActionException("No app on this phone can do that.")
            } catch (e: SecurityException) {
                throw PhoneActionException("Android blocked that action: ${e.message ?: "permission denied"}.")
            }
        }
    }

    private fun calendarDay(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> Calendar.MONDAY
        DayOfWeek.TUESDAY -> Calendar.TUESDAY
        DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
        DayOfWeek.THURSDAY -> Calendar.THURSDAY
        DayOfWeek.FRIDAY -> Calendar.FRIDAY
        DayOfWeek.SATURDAY -> Calendar.SATURDAY
        DayOfWeek.SUNDAY -> Calendar.SUNDAY
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        const val EXTRA_MEDIA_PLAYLIST = "android.intent.extra.playlist"
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH)
        val requestCodes = AtomicInteger(1000)
    }
}
