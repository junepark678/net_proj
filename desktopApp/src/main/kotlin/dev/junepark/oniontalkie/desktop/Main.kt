package dev.junepark.oniontalkie.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.junepark.oniontalkie.ui.App

fun main() = application {
    val controller = remember { DesktopTorController() }
    Window(
        onCloseRequest = {
            controller.close()
            exitApplication()
        },
        title = "Onion Dial",
        state = rememberWindowState(size = DpSize(1000.dp, 680.dp)),
    ) {
        App(controller)
    }
}
