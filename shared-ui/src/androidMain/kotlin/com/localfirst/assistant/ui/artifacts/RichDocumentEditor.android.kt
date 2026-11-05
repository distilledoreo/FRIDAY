package com.localfirst.assistant.ui.artifacts

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream

private class EditorBridge(private val event: (String)->Unit) {
    @JavascriptInterface fun post(message: String) { if(message.length <= 1500000) event(message) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun RichDocumentEditor(content: JsonObject, session: String, onEvent: (String) -> Unit, close: () -> Unit) {
    val event by rememberUpdatedState(onEvent)
    Dialog(onDismissRequest=close, properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize()) {
            TextButton(close){Text("Done editing — keep selection")}
            key(session) { AndroidView(modifier=Modifier.fillMaxWidth().weight(1f),factory={ctx->
                WebView(ctx).apply {
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=false;settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.setGeolocationEnabled(false);settings.setSupportMultipleWindows(false)
                    val handler=android.os.Handler(android.os.Looper.getMainLooper())
                    addJavascriptInterface(EditorBridge { data-> handler.post { event(data) } },"FridayEditor")
                    webViewClient=object:WebViewClient(){
                        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse = WebResourceResponse("text/plain","utf-8",403,"Offline",emptyMap(),ByteArrayInputStream(ByteArray(0)))
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=true
                        override fun onPageFinished(view:WebView,url:String){
                            view.evaluateJavascript("window.fridayInit(${content},${JsonPrimitive(session)});",null)
                        }
                    }
                    loadDataWithBaseURL("https://editor.invalid/",ctx.assets.open("artifact-editor.html").bufferedReader().use{it.readText()},"text/html","utf-8",null)
                }
            },onRelease={it.removeJavascriptInterface("FridayEditor");it.destroy()}) }
        } }
    }
}

@Composable
actual fun ArtifactDraftGuard(dirty: Boolean) { /* Android recovery is stored by the host in app-private files. */ }
