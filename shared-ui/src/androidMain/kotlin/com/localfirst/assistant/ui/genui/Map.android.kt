package com.localfirst.assistant.ui.genui
import com.localfirst.assistant.platform.PlatformNet
internal actual fun mapUri(lat: Double?, lng: Double?, query: String): String = if (lat != null && lng != null) "geo:$lat,$lng?q=$lat,$lng(${PlatformNet.encodeUrl(query)})" else "geo:0,0?q=" + PlatformNet.encodeUrl(query)
