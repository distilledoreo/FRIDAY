package com.localfirst.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.localfirst.assistant.phone.PermissionBroker
import com.localfirst.assistant.ui.ChatScreen
import com.localfirst.assistant.ui.ChatViewModel
import com.localfirst.assistant.ui.ChatViewModelFactory
import com.localfirst.assistant.ui.theme.AssistantTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PermissionBroker.attach(this)
        viewModel = ViewModelProvider(this, ChatViewModelFactory(application))[ChatViewModel::class.java]
        if (savedInstanceState == null) handleLaunch(intent)
        setContent {
            AssistantTheme {
                ChatScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onStop() {
        super.onStop()
        // Android blocks the mic for apps in the background.
        if (!isChangingConfigurations) viewModel.pauseVoice()
    }

    override fun onDestroy() {
        PermissionBroker.detach()
        super.onDestroy()
    }

    /** Opened as the phone's assistant or from a headset's voice button: start a voice chat. */
    private fun handleLaunch(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> viewModel.startAssistantSession()
        }
    }
}
