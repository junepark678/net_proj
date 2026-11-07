package dev.junepark.oniontalkie.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.junepark.oniontalkie.ui.App
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
