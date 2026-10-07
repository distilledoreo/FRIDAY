package com.localfirst.assistant.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.localfirst.assistant.AssistantApp

/** Replies and read receipts from Android Auto. */
class CarReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarMessaging.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CarMessaging.KEY_TEXT)?.toString()?.trim()
                if (text.isNullOrEmpty()) return
                CarMessaging.showSent(context, text)
                CarTurnService.start(context)
                (context.applicationContext as AssistantApp).chat().receiveCarMessage(text)
            }
            CarMessaging.ACTION_READ -> CarMessaging.markRead(context)
        }
    }
}
