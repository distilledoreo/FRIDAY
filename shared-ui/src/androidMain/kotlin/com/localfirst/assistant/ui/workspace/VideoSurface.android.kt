package com.localfirst.assistant.ui.workspace

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The platform video view with its own controls; it starts playing and loops, and stops when it leaves the screen. */
@Composable
internal actual fun VideoSurface(url: String, modifier: Modifier) {
    AndroidView(factory = { context ->
        VideoView(context).apply {
            setMediaController(MediaController(context).also { it.setAnchorView(this) })
            setOnPreparedListener { player -> player.isLooping = true; start() }
        }
    }, modifier = modifier, update = { view ->
        if (view.tag != url) { view.tag = url; view.setVideoURI(Uri.parse(url)) }
    }, onRelease = { it.stopPlayback() })
}
