package dev.junepark.oniontalkie.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallHistoryEntry
import dev.junepark.oniontalkie.metrics.CallStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnionDialAppUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homePresenceToggleStartsAndStopsTor() {
        val viewModel = FakeOnionDialViewModel()
        compose.setContent { App(viewModel) }

        compose.onNodeWithText("Go online").assertIsDisplayed().performClick()
        assertEquals(1, viewModel.startTorCalls)

        viewModel.setPresence(AppPresenceState.TorReady)
        compose.waitForIdle()
        compose.onNodeWithText("Go offline").assertIsDisplayed().performClick()
        assertEquals(1, viewModel.stopTorCalls)
    }

    @Test
    fun inCallStateNavigatesToCallPageAndRendersControls() {
        val peer = "peer-onion-address.onion"
        val viewModel = FakeOnionDialViewModel(
            initialPresence = AppPresenceState.TorReady,
            initialCallState = CallState.InCall(peer),
        )
        compose.setContent { App(viewModel) }

        compose.onNodeWithText("In call").assertIsDisplayed()
        compose.onNodeWithText(peer).assertIsDisplayed()
        compose.onNodeWithText("00:00").assertIsDisplayed()
        compose.onNodeWithText("Hang up").assertIsDisplayed()
    }

    @Test
    fun peerOfflineStateIsShownAsARecoverableCallOutcome() {
        val peer = "peer-onion-address.onion"
        val viewModel = FakeOnionDialViewModel(
            initialPresence = AppPresenceState.TorReady,
            initialCallState = CallState.PeerOffline(peer),
        )
        compose.setContent { App(viewModel) }

        compose.onNodeWithText("Contact is offline").assertIsDisplayed()
        compose.onNodeWithText(peer).assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Go offline").assertIsDisplayed()
    }
}

private class FakeOnionDialViewModel(
    initialPresence: AppPresenceState = AppPresenceState.Offline,
    initialCallState: CallState = CallState.Offline,
) : OnionDialViewModel {
    private val mutablePresence = MutableStateFlow(initialPresence)
    private val mutableCallState = MutableStateFlow(initialCallState)
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableOnionAddress = MutableStateFlow<String?>(null)
    private val mutablePairingText = MutableStateFlow<String?>(null)
    private val mutableMetricsStats = MutableStateFlow<CallStats?>(null)
    private val mutableMetricsCsvPath = MutableStateFlow<String?>(null)
    private val mutableCallHistory = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
    private val mutableMuted = MutableStateFlow(false)
    private val mutableSpeakerphoneSelected = MutableStateFlow(false)
    private val mutableSelectedAudioInput = MutableStateFlow<String?>(null)
    private val mutableSelectedAudioOutput = MutableStateFlow<String?>(null)

    var startTorCalls = 0
        private set
    var stopTorCalls = 0
        private set

    override val state: StateFlow<AppPresenceState> = mutablePresence
    override val callState: StateFlow<CallState> = mutableCallState
    override val contacts: StateFlow<List<Contact>> = mutableContacts
    override val onionAddress: StateFlow<String?> = mutableOnionAddress
    override val pairingText: StateFlow<String?> = mutablePairingText
    override val metricsStats: StateFlow<CallStats?> = mutableMetricsStats
    override val metricsCsvPath: StateFlow<String?> = mutableMetricsCsvPath
    override val muted: StateFlow<Boolean> = mutableMuted
    override val speakerphoneSelected: StateFlow<Boolean> = mutableSpeakerphoneSelected
    override val supportsSpeakerphone: Boolean = false
    override val callHistory: StateFlow<List<CallHistoryEntry>> = mutableCallHistory
    override val audioInputs: List<AudioDeviceOption> = emptyList()
    override val audioOutputs: List<AudioDeviceOption> = emptyList()
    override val selectedAudioInput: StateFlow<String?> = mutableSelectedAudioInput
    override val selectedAudioOutput: StateFlow<String?> = mutableSelectedAudioOutput

    fun setPresence(presence: AppPresenceState) {
        mutablePresence.value = presence
    }

    override fun startTor() {
        startTorCalls += 1
    }

    override fun stopTor() {
        stopTorCalls += 1
    }

    override suspend fun importPairing(text: String): PairingResult? = null
    override suspend fun removeContact(peerOnion: String): Boolean = false
    override suspend fun dial(peerOnion: String): Boolean = false
    override suspend fun acceptCall(): Boolean = false
    override suspend fun declineCall(): Boolean = false
    override suspend fun hangUp(): Boolean = false
    override fun setMuted(muted: Boolean) = Unit
    override fun setSpeakerphoneEnabled(enabled: Boolean): Boolean = false
    override fun selectAudioInput(id: String?) = Unit
    override fun selectAudioOutput(id: String?) = Unit
    override suspend fun refreshCallHistory() = Unit
    override fun shareMetrics(csvPath: String?) = Unit
}
