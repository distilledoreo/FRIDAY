package com.localfirst.assistant.ui.genui
import com.localfirst.assistant.platform.PlatformNet
internal actual fun mapUri(lat: Double?, lng: Double?, query: String): String = "https://www.google.com/maps/search/?api=1&query=" + PlatformNet.encodeUrl(if (lat != null && lng != null) "$lat,$lng" else query)
