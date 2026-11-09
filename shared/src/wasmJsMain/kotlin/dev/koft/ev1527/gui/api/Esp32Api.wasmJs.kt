package dev.koft.ev1527.gui.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.window

actual fun createPlatformHttpClient(authToken: String): HttpClient = HttpClient(Js) {
    configureEsp32Client(authToken)
}

/**
 * The dev server and the API run on the same machine, so reusing the page's
 * hostname also works when the browser opens the app through a LAN address.
 * The Kotlin/Native server is HTTP-only in this scaffold; front it with a
 * reverse proxy for HTTPS production deployments.
 */
actual fun apiBaseUrl(): String {
    return window.location.origin
}
