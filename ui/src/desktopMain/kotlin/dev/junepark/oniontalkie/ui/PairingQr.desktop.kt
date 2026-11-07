package dev.junepark.oniontalkie.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual fun pairingQrMatrix(text: String): QrMatrix {
    val encoded = MultiFormatWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        1,
        1,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
    val size = encoded.width
    return QrMatrix(size, BooleanArray(size * size) { index -> encoded.get(index % size, index / size) })
}
