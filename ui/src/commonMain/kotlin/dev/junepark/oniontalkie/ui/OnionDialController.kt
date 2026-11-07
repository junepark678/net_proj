package dev.junepark.oniontalkie.ui

import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallStats
import kotlinx.coroutines.flow.StateFlow

/** Platform QR input action that returns decoded pairing text to the shared UI. */
data class PairingQrAction(
    val label: String,
    val labelKo: String,
    val launch: (onDecoded: (String) -> Unit) -> Unit,
)

/** Runtime calls and pairing actions shared by Android and desktop shells. */
interface OnionDialController : AppPresenceController {
    val callState: StateFlow<CallState>
    val contacts: StateFlow<List<Contact>>
    val onionAddress: StateFlow<String?>
    val pairingText: StateFlow<String?>
    val metricsStats: StateFlow<CallStats?>
    val metricsCsvPath: StateFlow<String?>

    suspend fun importPairing(text: String): PairingResult?
    suspend fun removeContact(peerOnion: String): Boolean
    suspend fun dial(peerOnion: String): Boolean
    suspend fun acceptCall(): Boolean
    suspend fun declineCall(): Boolean
    suspend fun hangUp(): Boolean
    fun shareMetrics()
}
