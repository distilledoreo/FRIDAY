package com.localfirst.assistant.phone

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Present only so the user can grant notification access, which Android
 * requires before an app may see other apps' media sessions (what's playing in
 * Spotify, and controlling that player directly). It does not read notifications.
 */
class MediaListenerService : NotificationListenerService() {
    companion object {
        fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
