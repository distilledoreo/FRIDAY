package com.localfirst.assistant.ui.workspace

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.localfirst.assistant.client.ModelConnection

@Composable
fun ModelConnectionFields(value: ModelConnection, change: (ModelConnection) -> Unit, enabled: Boolean = true) {
    OutlinedTextField(value.address, { change(value.copy(address = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Server address") }, placeholder = { Text("http://localhost:8080/v1") }, singleLine = true)
    OutlinedTextField(value.model, { change(value.copy(model = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Model name") }, singleLine = true)
    OutlinedTextField(value.apiKey, { change(value.copy(apiKey = it)) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("API key (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(value.timeout, { change(value.copy(timeout = it.filter(Char::isDigit))) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("Timeout seconds") }, singleLine = true)
}
