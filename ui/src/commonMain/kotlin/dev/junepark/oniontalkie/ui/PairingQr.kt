package dev.junepark.oniontalkie.ui

/** Immutable module matrix returned by the platform QR encoder. */
data class QrMatrix(val size: Int, val darkModules: BooleanArray)

/** Encodes pairing text as a QR matrix on Android and desktop using ZXing core. */
expect fun pairingQrMatrix(text: String): QrMatrix
