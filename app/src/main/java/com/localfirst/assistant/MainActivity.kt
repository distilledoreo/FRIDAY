package com.localfirst.assistant

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localfirst.assistant.phone.PermissionBroker
import com.localfirst.assistant.ui.ChatScreen
import com.localfirst.assistant.ui.ChatViewModel
import com.localfirst.assistant.ui.theme.AssistantTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PermissionBroker.attach(this)
        viewModel = (application as AssistantApp).chat()
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
        if (!isChangingConfigurations) { viewModel.pauseVoice(); viewModel.onAppLeft() }
    }

    override fun onDestroy() {
        PermissionBroker.detach()
        super.onDestroy()
    }

    /** Opened as the phone's assistant or from a headset's voice button: start a voice chat. */
    private fun handleLaunch(intent: Intent?) {
        when (intent?.action) {
            "com.localfirst.assistant.TASKS" -> viewModel.openWorkspace(com.localfirst.assistant.ui.WorkspaceDestination.TASKS)
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> viewModel.startAssistantSession()
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                } else {
                    listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                }
                val clip = intent.clipData
                val uris = (streams + (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri })
                    .distinct().filter { it.scheme == "content" }
                viewModel.receiveShared(uris, intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            }
        }
    }
}
