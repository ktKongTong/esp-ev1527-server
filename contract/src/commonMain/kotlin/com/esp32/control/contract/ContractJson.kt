package com.esp32.control.contract

import kotlinx.serialization.json.Json

val ContractJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
    prettyPrint = false
    classDiscriminator = "type"
}
