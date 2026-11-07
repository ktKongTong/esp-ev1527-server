package com.esp32.control.gui.web

import androidx.compose.ui.window.ComposeViewport
import com.esp32.control.gui.App

fun main() {
    ComposeViewport("composeApp") {
        App()
    }
}
