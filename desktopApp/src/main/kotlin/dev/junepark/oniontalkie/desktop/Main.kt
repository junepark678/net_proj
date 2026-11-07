package dev.junepark.oniontalkie.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.junepark.oniontalkie.ui.App
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.ui.AppPresenceState
import dev.junepark.oniontalkie.ui.PairingQrAction
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import java.awt.SystemTray

fun main() = application {
    val controller = remember { DesktopTorController() }
    val presence by controller.state.collectAsState()
    val callState by controller.callState.collectAsState()
    val traySupported = remember { SystemTray.isSupported() }
    var windowVisible by remember { mutableStateOf(true) }
    val starting = presence is AppPresenceState.Starting || presence is AppPresenceState.Bootstrapping
    val running = presence == AppPresenceState.TorReady || starting
    val (status, tint) = when {
        callState is CallState.InCall -> "In call" to Color(0xFF29B67A)
        presence == AppPresenceState.TorReady -> "Online" to Color(0xFF29B67A)
        starting -> "Connecting to Tor" to Color(0xFFE2A63B)
        presence == AppPresenceState.Failed -> "Tor failed" to Color(0xFFE25555)
        else -> "Offline" to Color(0xFF888894)
    }

    if (traySupported) {
        Tray(
            icon = rememberVectorPainter(trayIcon(tint)),
            tooltip = "Onion Dial · $status",
            onAction = { windowVisible = true },
            menu = {
                Item(if (running) "Go offline" else "Go online", enabled = !starting) {
                    if (running) controller.stopTor() else controller.startTor()
                }
                Separator()
                Item("Open Onion Dial") { windowVisible = true }
                Item("Quit") {
                    controller.close()
                    exitApplication()
                }
            },
        )
    }

    Window(
        visible = windowVisible,
        onCloseRequest = {
            if (traySupported) {
                windowVisible = false
            } else {
                controller.close()
                exitApplication()
            }
        },
        title = "Onion Dial",
        state = rememberWindowState(size = DpSize(1000.dp, 680.dp)),
    ) {
        App(
            controller = controller,
            pairingQrAction = PairingQrAction(
                label = "Import QR image",
                labelKo = "QR 이미지 가져오기",
                launch = ::importPairingQr,
            ),
        )
    }
}

private fun trayIcon(status: Color): ImageVector = ImageVector.Builder(
    name = "OnionDialTray",
    defaultWidth = 32.dp,
    defaultHeight = 32.dp,
    viewportWidth = 32f,
    viewportHeight = 32f,
).apply {
    path(fill = SolidColor(Color(0xFF6558D3))) {
        moveTo(16f, 2f)
        curveTo(23.732f, 2f, 30f, 8.268f, 30f, 16f)
        curveTo(30f, 23.732f, 23.732f, 30f, 16f, 30f)
        curveTo(8.268f, 30f, 2f, 23.732f, 2f, 16f)
        curveTo(2f, 8.268f, 8.268f, 2f, 16f, 2f)
        close()
    }
    path(fill = SolidColor(status)) {
        moveTo(29f, 24f)
        curveTo(29f, 26.761f, 26.761f, 29f, 24f, 29f)
        curveTo(21.239f, 29f, 19f, 26.761f, 19f, 24f)
        curveTo(19f, 21.239f, 21.239f, 19f, 24f, 19f)
        curveTo(26.761f, 19f, 29f, 21.239f, 29f, 24f)
        close()
    }
}.build()

private fun importPairingQr(onDecoded: (String) -> Unit) {
    val korean = Locale.getDefault().language == "ko"
    val chooser = JFileChooser().apply {
        dialogTitle = if (korean) "페어링 QR 이미지 선택" else "Select a pairing QR image"
        fileFilter = FileNameExtensionFilter("PNG, JPEG, BMP", "png", "jpg", "jpeg", "bmp")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return

    try {
        val image = requireNotNull(ImageIO.read(File(chooser.selectedFile.toURI()))) {
            "Unsupported image format"
        }
        require(image.width in 1..4096 && image.height in 1..4096) { "Image is too large" }
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text
        onDecoded(decoded)
    } catch (_: Exception) {
        JOptionPane.showMessageDialog(
            null,
            if (korean) "이미지에서 QR 코드를 읽을 수 없습니다." else "Could not read a QR code from this image.",
            "Onion Dial",
            JOptionPane.ERROR_MESSAGE,
        )
    }
}
