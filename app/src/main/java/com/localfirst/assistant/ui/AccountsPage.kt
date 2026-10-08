package com.localfirst.assistant.ui

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** Secrets and auth codes stay in ephemeral state; never rememberSaveable or chat history. */
@Composable
internal fun AccountsPage(state: ChatUiState, vm: ChatViewModel) {
    val context = LocalContext.current
    var setup by remember { mutableStateOf<String?>(null) }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    var mailRead by remember { mutableStateOf(true) }
    var calendarRead by remember { mutableStateOf(true) }
    var mailSend by remember { mutableStateOf(false) }
    var mailSetup by remember { mutableStateOf(false) }
    val resolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) vm.googleAccountResult(null)
        else runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
            .onSuccess { authorization -> vm.googleAccountResult(authorization.serverAuthCode) }
            .onFailure { vm.googleAccountResult(null) }
    }
    LaunchedEffect(Unit) { vm.refreshAccounts() }
    val flow = state.oauthFlow
    LaunchedEffect(flow?.optString("flow_id")) {
        if (flow == null || !vm.consumeAccountLaunch(flow.getString("flow_id"))) return@LaunchedEffect
        if (flow.optString("provider") == "microsoft") {
            val uri = Uri.parse(flow.getString("authorization_url"))
            if (uri.scheme != "https" || uri.host != "login.microsoftonline.com" || uri.userInfo != null) vm.failAccountSignIn()
            else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { vm.failAccountSignIn() }
        } else {
            val activity = context.activity()
            if (activity == null) vm.failAccountSignIn()
            else {
                val scopes = flow.getJSONArray("scopes")
                @Suppress("DEPRECATION")
                val request = AuthorizationRequest.builder().setRequestedScopes((0 until scopes.length()).map { Scope(scopes.getString(it)) })
                    .requestOfflineAccess(flow.getString("client_id"), true).build()
                Identity.getAuthorizationClient(activity).authorize(request)
                    .addOnSuccessListener { result ->
                        if (vm.activeAccountFlow(flow.getString("flow_id"))) {
                            if (result.hasResolution()) result.pendingIntent?.let { vm.markAccountResolution(flow.getString("flow_id")); resolution.launch(IntentSenderRequest.Builder(it.intentSender).build()) } ?: vm.failAccountSignIn()
                            else result.serverAuthCode?.let { vm.finishAccountSignIn(it, expectedFlowId = flow.getString("flow_id")) } ?: vm.failAccountSignIn("Google did not return an authorization code.")
                        }
                    }.addOnFailureListener { if (vm.activeAccountFlow(flow.getString("flow_id"))) vm.failAccountSignIn() }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Accounts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text("Credentials live in your PC’s encrypted vault. Sign-in uses the provider’s screen. Nothing is sent without a separate draft approval.")
            TextButton(onClick = vm::refreshAccounts, enabled = !state.workspaceBusy) { Text("Refresh") }
            if (flow != null) TextButton(onClick = { vm.failAccountSignIn("Sign-in canceled.") }) { Text(if (state.oauthCompleting) "Cancel pending sign-in" else "Cancel sign-in") }
            Row { Text("Read email", Modifier.weight(1f)); Switch(mailRead, { mailRead = it }) }
            Row { Text("Read calendar", Modifier.weight(1f)); Switch(calendarRead, { calendarRead = it }) }
            Row { Text("Allow approved sending", Modifier.weight(1f)); Switch(mailSend, { mailSend = it }) }
            Text("Sending is not connected yet. You can choose its provider permission now; it still requires a later exact-payload approval.", style = MaterialTheme.typography.bodySmall)
        }
        items(listOf("google", "microsoft")) { provider ->
            val configured = state.accountProviders.any { it.optString("provider") == provider && it.optBoolean("configured") }
            val title = if (provider == "google") "Google" else "Microsoft"
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!configured) Text("OAuth app registration needed before sign-in.")
                    Button(onClick = { vm.beginAccountSignIn(provider, listOfNotNull("mail_read".takeIf { mailRead }, "calendar_read".takeIf { calendarRead }, "mail_send".takeIf { mailSend })) }, enabled = configured && (mailRead || calendarRead || mailSend) && flow == null && !state.workspaceBusy) { Text("Sign in with $title") }
                    TextButton(onClick = { setup = provider; clientId = ""; secret = "" }, enabled = flow == null && !state.workspaceBusy) { Text("Configure OAuth application") }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Other email provider", style = MaterialTheme.typography.titleMedium)
                    Text("Connect a public IMAP server with verified TLS on port 993. Use a provider app password if required. SMTP settings can be saved for future approved sending.")
                    Button(onClick = { mailSetup = true }, enabled = flow == null && !state.workspaceBusy) { Text("Connect mail account") }
                }
            }
        }
        if (state.connectedAccounts.isEmpty()) item { Text("No accounts connected.") }
        items(state.connectedAccounts, key = { it.getString("id") }) { account ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(account.optString("label"), style = MaterialTheme.typography.titleMedium)
                    Text(account.optString("provider"))
                    Row { TextButton(onClick = { vm.readAccountMail(account.getString("id")) }, enabled = !state.workspaceBusy) { Text("Inbox") }
                        if (account.optString("provider") != "imap") TextButton(onClick = { vm.readAccountCalendar(account.getString("id")) }, enabled = !state.workspaceBusy) { Text("Next 7 days") }
                        TextButton(onClick = { remove = account }, enabled = !state.workspaceBusy) { Text("Remove") } }
                }
            }
        }
        state.accountContent?.let { content ->
            content.optJSONObject("message")?.let { message -> item {
                Text(message.optString("subject"), style = MaterialTheme.typography.titleMedium)
                Text("From: ${message.optString("from")}", style = MaterialTheme.typography.bodySmall)
                Text(message.optString("body"))
                if (message.optBoolean("truncated")) Text("Message preview is truncated.")
            } }
            content.optJSONArray("messages")?.let { values -> items((0 until values.length()).map { values.getJSONObject(it) }, key = { it.getString("id") }) { message ->
                OutlinedCard(onClick = { vm.readAccountMessage(content.getString("account_id"), message.getString("id")) }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(message.optString("subject"), style = MaterialTheme.typography.titleSmall); Text(message.optString("from")); Text(message.optString("preview"))
                } }
            } }
            content.optJSONArray("events")?.let { values -> items((0 until values.length()).map { values.getJSONObject(it) }, key = { it.optString("id") }) { event ->
                Text(event.optString("title"), style = MaterialTheme.typography.titleSmall)
                Text(event.optJSONObject("start")?.let { it.optString("dateTime").ifBlank { it.optString("date") } }.orEmpty())
                Text(event.optString("location"))
            } }
            item { Text(content.optString("detail"), style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (mailSetup) MailAccountDialog(onDismiss = { mailSetup = false }, onConnect = { email, username, password, imap, smtp, port ->
        vm.connectMailAccount(email, username, password, imap, smtp, port); mailSetup = false
    })
    setup?.let { provider ->
        AlertDialog(onDismissRequest = { setup = null; secret = "" }, title = { Text("${provider.replaceFirstChar(Char::uppercase)} application setup") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Use your registered FRIDAY application. Google also needs the Android package/signing registration and a backend web client; Microsoft needs its Android redirect registration.")
                OutlinedTextField(clientId, { clientId = it.take(300) }, label = { Text(if (provider == "google") "Web client ID" else "Application client ID") })
                if (provider == "google") OutlinedTextField(secret, { secret = it.take(500) }, label = { Text("Web client secret · saved only on PC") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            } }, confirmButton = { TextButton(onClick = { vm.configureAccountProvider(provider, clientId, secret.takeIf { provider == "google" }); secret = ""; setup = null }, enabled = clientId.isNotBlank() && (provider != "google" || secret.isNotBlank())) { Text("Save on PC") } },
            dismissButton = { TextButton(onClick = { setup = null; secret = "" }) { Text("Cancel") } })
    }
    remove?.let { account -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove this account?") },
        text = { Text("Remove ${account.optString("label")} and its saved credential from FRIDAY. Provider grants can also be revoked in its account settings.") },
        confirmButton = { TextButton(onClick = { vm.removeAccount(account.getString("id")); remove = null }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } }) }
}

@Composable
private fun MailAccountDialog(onDismiss: () -> Unit, onConnect: (String, String, String, String, String, Int) -> Unit) {
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var imap by remember { mutableStateOf("") }
    var smtp by remember { mutableStateOf("") }
    var startTls by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { password = ""; onDismiss() }, title = { Text("Connect mail account") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This checks your IMAP login and saves encrypted credentials on the PC. Messages remain unread. SMTP login and sending are not enabled. Use OAuth above for Google and Microsoft.")
            OutlinedTextField(email, { email = it.take(320) }, label = { Text("Email address") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(username, { username = it.take(320) }, label = { Text("Login username · blank uses email") }, singleLine = true)
            OutlinedTextField(password, { password = it.take(1024) }, label = { Text("Password or app password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(imap, { imap = it.take(253) }, label = { Text("IMAP hostname · TLS port 993") }, singleLine = true)
            OutlinedTextField(smtp, { smtp = it.take(253) }, label = { Text("SMTP hostname · optional") }, singleLine = true)
            if (smtp.isNotBlank()) {
                Row { Text("SMTP STARTTLS on 587", Modifier.weight(1f)); Switch(startTls, { startTls = it }) }
                Text(if (startTls) "Port 587 requires TLS before authentication." else "SMTP uses implicit TLS on port 465.", style = MaterialTheme.typography.bodySmall)
                Text("Future SMTP use shares this username and password. Settings are saved without checking SMTP login.", style = MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            onConnect(email, username.ifBlank { email }, password, imap, smtp, if (startTls) 587 else 465); password = ""
        }, enabled = email.isNotBlank() && password.isNotBlank() && imap.isNotBlank()) { Text("Check login and save on PC") } },
        dismissButton = { TextButton(onClick = { password = ""; onDismiss() }) { Text("Cancel") } })
}
