package com.localfirst.assistant.json

import kotlinx.serialization.json.Json

object JsonCodec {
    val json: Json = Json {
        ignoreUnknownKeys = true
    }
}
