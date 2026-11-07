package dev.junepark.oniontalkie.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.junepark.oniontalkie.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Onion Talkie") {
        App()
    }
}
