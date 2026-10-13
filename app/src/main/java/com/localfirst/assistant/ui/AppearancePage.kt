package com.localfirst.assistant.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localfirst.assistant.settings.*
import com.localfirst.assistant.ui.theme.*
import kotlinx.serialization.json.*
@Composable
internal fun AppearancePage() {
    val preferences = LocalAppearance.current
    val save = LocalSaveAppearance.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        AppearanceControls(preferences) { key, value, _ -> save(preferences.withPreference(key, value)) }
    }
}
