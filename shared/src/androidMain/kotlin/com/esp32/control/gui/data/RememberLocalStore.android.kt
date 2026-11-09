package com.esp32.control.gui.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private var sharedLocalStore: LocalStore? = null

@Composable
internal actual fun rememberLocalStore(): LocalStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        sharedLocalStore ?: createRoomLocalStore(context).also { sharedLocalStore = it }
    }
}
