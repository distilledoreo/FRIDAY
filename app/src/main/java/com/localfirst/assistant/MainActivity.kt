package com.localfirst.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localfirst.assistant.ui.ChatScreen
import com.localfirst.assistant.ui.ChatViewModel
import com.localfirst.assistant.ui.ChatViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val factory = remember { ChatViewModelFactory(application) }
                val viewModel: ChatViewModel = viewModel(factory = factory)
                ChatScreen(viewModel)
            }
        }
    }
}
