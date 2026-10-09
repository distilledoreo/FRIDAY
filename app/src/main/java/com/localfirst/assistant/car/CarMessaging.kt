package com.localfirst.assistant.car

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.localfirst.assistant.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The assistant as a conversation in Android Auto. Android Auto shows messaging
 * notifications on the car screen, reads them aloud and lets the driver reply
 * by voice; replies come back through [CarReplyReceiver].
 */
object CarMessaging {
    const val ACTION_REPLY = "com.localfirst.assistant.car.REPLY"
    const val ACTION_READ = "com.localfirst.assistant.car.READ"
    const val KEY_TEXT = "text"
    private const val CHANNEL = "android_auto"
    private const val NOTIFICATION_ID = 950
    private const val MAX_LINES = 6

    private class Line(val fromUser: Boolean, val text: String, val time: Long = System.currentTimeMillis())
    private val lines = ArrayDeque<Line>()

    private val _connected = MutableStateFlow(false)

    /** True while the phone is connected to Android Auto. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Follows the Android Auto connection: greets when it starts so the car has a conversation to reply to. */
    fun watch(app: Application, enabled: () -> Boolean) {
        CarConnection(app).type.observeForever { type ->
            val projecting = type == CarConnection.CONNECTION_TYPE_PROJECTION
            if (projecting == _connected.value) return@observeForever
            _connected.value = projecting
            if (projecting && enabled()) greet(app) else if (!projecting) clear(app)
        }
    }

    fun greet(context: Context) {
        synchronized(lines) {
            lines.clear()
            lines += Line(false, "Hi! Reply to ask me anything.")
        }
        post(context, alert = false)
    }

    /** Shows the driver's message; Android Auto waits for this before it stops showing "sending". */
    fun showSent(context: Context, text: String) = add(context, Line(true, text), alert = false)

    fun showReply(context: Context, text: String) = add(context, Line(false, text), alert = true)

    /** Read aloud: keep the conversation for the next reply, without alerting again. */
    fun markRead(context: Context) = post(context, alert = false)

    fun clear(context: Context) {
        synchronized(lines) { lines.clear() }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun add(context: Context, line: Line, alert: Boolean) {
        synchronized(lines) {
            lines += line
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        post(context, alert)
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, alert: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH).setName("Android Auto").build(),
        )
        val me = Person.Builder().setName("You").setKey("user").build()
        val assistant = Person.Builder().setName("Assistant").setKey("assistant").build()
        val style = NotificationCompat.MessagingStyle(me)
        synchronized(lines) { lines.toList() }.forEach { style.addMessage(it.text, it.time, if (it.fromUser) me else assistant) }

        val reply = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, CarReplyReceiver::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )
            .addRemoteInput(RemoteInput.Builder(KEY_TEXT).setLabel("Ask the assistant").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val read = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Mark as read",
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, CarReplyReceiver::class.java).setAction(ACTION_READ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .addAction(reply)
            .addInvisibleAction(read)
            .setSilent(!alert)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
