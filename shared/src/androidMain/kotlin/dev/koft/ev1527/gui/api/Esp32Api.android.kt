package dev.koft.ev1527.gui.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

actual fun createPlatformHttpClient(authToken: String): HttpClient = HttpClient(CIO) {
    configureEsp32Client(authToken)
}

/**
 * 10.0.2.2 is the Android emulator alias for the host machine.
 * For a physical device, replace this with your computer's LAN IP.
 */
actual fun apiBaseUrl(): String = ""
