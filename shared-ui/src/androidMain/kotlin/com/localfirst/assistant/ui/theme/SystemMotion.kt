package com.localfirst.assistant.ui.theme
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
@Composable
internal actual fun rememberSystemReducedMotion(): Boolean {
    val resolver=LocalContext.current.contentResolver
    fun systemReduced()=runCatching { Settings.Global.getFloat(resolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f)==0f }.getOrDefault(false)
    var systemReduced by remember { mutableStateOf(systemReduced()) }
    DisposableEffect(resolver) {
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange:Boolean) { systemReduced=systemReduced() } }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return systemReduced
}
