package com.localfirst.assistant

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.localfirst.assistant.car.CarMessaging
import com.localfirst.assistant.settings.ServerSettingsStore
import com.localfirst.assistant.ui.ChatViewModel
import com.localfirst.assistant.ui.ChatViewModelFactory

class AssistantApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** One chat for the whole app, so a reply from Android Auto reaches it even with no screen open. */
    fun chat(): ChatViewModel = ViewModelProvider(this, ChatViewModelFactory(this))[ChatViewModel::class.java]

    override fun onCreate() {
        super.onCreate()
        CarMessaging.watch(this) { ServerSettingsStore(this).load().androidAuto }
    }
}
