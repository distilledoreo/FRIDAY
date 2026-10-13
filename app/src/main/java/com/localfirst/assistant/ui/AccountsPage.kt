package com.localfirst.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.localfirst.assistant.client.string
import kotlinx.serialization.json.*

private fun Context.activity(): Activity? = when(this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** Only Android authorization effects live here; account forms and previews are shared. */
@Composable
internal fun AccountsPage(state: ChatUiState, vm: ChatViewModel) {
    val context = LocalContext.current
    val feature by vm.features.state.collectAsState()
    var resolutionFlow by remember { mutableStateOf<JsonObject?>(null) }
    var launched by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val flow = resolutionFlow
        resolutionFlow = null
        if (flow != null && vm.features.state.value.oauth?.string("flow_id") == flow.string("flow_id")) {
            val code = if (result.resultCode == Activity.RESULT_OK && result.data != null) runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).serverAuthCode }.getOrNull() else null
            if (code.isNullOrBlank()) vm.features.cancelOAuth() else vm.features.finishOAuth(code, flow.string("state"), flow.string("flow_id"))
        }
    }
    LaunchedEffect(feature.oauth) {
        val flow = feature.oauth ?: return@LaunchedEffect
        if (flow.string("provider") != "google" || flow.string("flow_id") == launched) return@LaunchedEffect
        launched = flow.string("flow_id")
        val activity = context.activity() ?: return@LaunchedEffect
        @Suppress("DEPRECATION")
        val request = AuthorizationRequest.builder().setRequestedScopes((flow["scopes"] as? JsonArray).orEmpty().map { Scope(it.jsonPrimitive.content) }).requestOfflineAccess(flow.string("client_id"), true).build()
        Identity.getAuthorizationClient(activity).authorize(request).addOnSuccessListener { authorization ->
            if (vm.features.state.value.oauth?.string("flow_id") == flow.string("flow_id")) {
                if (authorization.hasResolution()) authorization.pendingIntent?.let { resolutionFlow = flow; launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } ?: vm.features.cancelOAuth()
                else authorization.serverAuthCode?.let { vm.features.finishOAuth(it, flow.string("state"), flow.string("flow_id")) } ?: vm.features.cancelOAuth()
            }
        }.addOnFailureListener { if (vm.features.state.value.oauth?.string("flow_id") == flow.string("flow_id")) vm.features.cancelOAuth() }
    }
    com.localfirst.assistant.ui.workspace.AccountsScreen(vm.features)
}
