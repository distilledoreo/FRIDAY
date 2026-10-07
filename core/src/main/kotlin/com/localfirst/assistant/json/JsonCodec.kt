package com.localfirst.assistant.json

import kotlinx.serialization.json.Json

internal object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
    }
}
