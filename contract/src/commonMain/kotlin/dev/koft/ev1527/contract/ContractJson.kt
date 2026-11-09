package dev.koft.ev1527.contract

import kotlinx.serialization.json.Json

val ContractJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    isLenient = false
    prettyPrint = false
    classDiscriminator = "type"
}
