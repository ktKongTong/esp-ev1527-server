package com.esp32.control.gui.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Room has no usable wasmJs target yet, so the web build keeps the read
// cache in memory and loses it on reload.
private var sharedLocalStore: LocalStore? = null

@Composable
internal actual fun rememberLocalStore(): LocalStore = remember {
    sharedLocalStore ?: InMemoryLocalStore().also { sharedLocalStore = it }
}
