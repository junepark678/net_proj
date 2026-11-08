package dev.junepark.oniontalkie.ui

import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallHistoryEntry
import dev.junepark.oniontalkie.metrics.CallStats
import kotlinx.coroutines.flow.StateFlow

/** Platform QR input action that returns decoded pairing text to the shared UI. */
data class PairingQrAction(
    val label: String,
    val labelKo: String,
    val launch: (onDecoded: (String) -> Unit) -> Unit,
)

/** Optional platform action for requesting battery-optimization exemption after an explanation. */
data class BatteryOptimizationAction(
    val isExempt: Boolean,
    val launch: () -> Unit,
)

/** Desktop audio endpoint option; an empty list means the platform selects audio automatically. */
data class AudioDeviceOption(val id: String, val label: String)

/**
 * Fakeable state-and-action contract for the shared Onion Dial UI.
 *
 * App consumes only this interface and shared call, contact, and metrics models. Platform shells
 * adapt Tor, pairing, audio, and persistence runtimes behind it, so the UI can be previewed with a
 * deterministic fake without starting Tor or opening an audio device.
 */
interface OnionDialViewModel : AppPresenceController {
    val callState: StateFlow<CallState>
    val contacts: StateFlow<List<Contact>>
    val onionAddress: StateFlow<String?>
    val pairingText: StateFlow<String?>
    val metricsStats: StateFlow<CallStats?>
    val metricsCsvPath: StateFlow<String?>
    val muted: StateFlow<Boolean>
    val speakerphoneSelected: StateFlow<Boolean>
    val supportsSpeakerphone: Boolean
    val callHistory: StateFlow<List<CallHistoryEntry>>
    val audioInputs: List<AudioDeviceOption>
    val audioOutputs: List<AudioDeviceOption>
    val selectedAudioInput: StateFlow<String?>
    val selectedAudioOutput: StateFlow<String?>

    suspend fun importPairing(text: String): PairingResult?
    suspend fun removeContact(peerOnion: String): Boolean
    suspend fun dial(peerOnion: String): Boolean
    suspend fun acceptCall(): Boolean
    suspend fun declineCall(): Boolean
    suspend fun hangUp(): Boolean
    fun setMuted(muted: Boolean)
    fun setSpeakerphoneEnabled(enabled: Boolean): Boolean
    fun selectAudioInput(id: String?)
    fun selectAudioOutput(id: String?)
    suspend fun refreshCallHistory()
    fun shareMetrics(csvPath: String? = null)
}
