package dev.junepark.oniontalkie.call

import dev.junepark.oniontalkie.audio.AudioRouteController
import dev.junepark.oniontalkie.audio.AudioInput
import dev.junepark.oniontalkie.audio.AudioOutput
import dev.junepark.oniontalkie.audio.NoOpAudioRouteController
import dev.junepark.oniontalkie.audio.createAudioInput
import dev.junepark.oniontalkie.audio.createAudioOutput
import dev.junepark.oniontalkie.call.CallState.Offline
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.ContactStore
import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.PairingPayload
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.identity.Storage
import dev.junepark.oniontalkie.identity.directoryStorage
import dev.junepark.oniontalkie.metrics.CallStats
import dev.junepark.oniontalkie.metrics.CallHistoryEntry
import dev.junepark.oniontalkie.metrics.FileMetricsSink
import dev.junepark.oniontalkie.metrics.loadCallHistory
import dev.junepark.oniontalkie.onion.AuthenticatedCallHandshake
import dev.junepark.oniontalkie.onion.OnionKeyStore
import dev.junepark.oniontalkie.onion.OnionServiceKeyProtector
import dev.junepark.oniontalkie.onion.createOnionServiceKeyProtector
import dev.junepark.oniontalkie.tor.Socks5TorDialer
import dev.junepark.oniontalkie.tor.TorControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Owns the shared call stack after a platform has started Tor and authenticated its control port.
 * The first online action publishes the service, reloads ClientOnionAuthDir, then starts CallEngine.
 */
class JvmOnionCallRuntime private constructor(
    private val control: TorControl,
    socksPort: Int,
    private val dataDirectory: String,
    private val displayName: String,
    private val scope: CoroutineScope,
    val contactStore: ContactStore,
    storage: Storage,
    keyProtector: OnionServiceKeyProtector,
    private val audioRouteController: AudioRouteController,
    inputFactory: () -> AudioInput,
    outputFactory: () -> AudioOutput,
) : AutoCloseable {
    private val lifecycle = Mutex()
    private val identities = IdentityStore(storage)
    private val presence: JvmOnionCallPresence
    private val authenticator: AuthenticatedCallHandshake
    private val dialer: JvmOnionCallDialer
    private val mutableCallState = MutableStateFlow<CallState>(Offline)
    private val mutableAddress = MutableStateFlow<String?>(null)
    private val mutablePairing = MutableStateFlow<String?>(null)
    private val mutableMetricsStats = MutableStateFlow<CallStats?>(null)
    private val mutableMetricsCsvPath = MutableStateFlow<String?>(null)
    private val mutableCallHistory = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
    private val mutableSpeakerphoneSelected = MutableStateFlow(audioRouteController.isSpeakerphoneSelected())
    private val sessions = OpusCallSessionFactory(
        inputFactory = inputFactory,
        outputFactory = outputFactory,
        routeController = audioRouteController,
        metricsSinkFactory = { peerOnion ->
            val peerShort = peerOnion.removeSuffix(".onion").take(12)
            FileMetricsSink(
                dataDirectory,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                peerShort,
            ).also { mutableMetricsCsvPath.value = it.csvPath.toString() }
        },
        metricsStatsListener = { _, stats -> mutableMetricsStats.value = stats },
    )
    private var engine: CallEngine? = null
    private var stateJob: Job? = null
    private var closed = false

    val callState: StateFlow<CallState> = mutableCallState.asStateFlow()
    val onionAddress: StateFlow<String?> = mutableAddress.asStateFlow()
    val pairingText: StateFlow<String?> = mutablePairing.asStateFlow()
    val metricsStats: StateFlow<CallStats?> = mutableMetricsStats.asStateFlow()
    val metricsCsvPath: StateFlow<String?> = mutableMetricsCsvPath.asStateFlow()
    val callHistory: StateFlow<List<CallHistoryEntry>> = mutableCallHistory.asStateFlow()
    val muted: StateFlow<Boolean> = sessions.muted
    val speakerphoneSelected: StateFlow<Boolean> = mutableSpeakerphoneSelected.asStateFlow()
    val supportsSpeakerphone: Boolean = audioRouteController.supportsSpeakerphone

    init {
        presence = JvmOnionCallPresence(
            control = control,
            identities = identities,
            keyStore = OnionKeyStore(storage, keyProtector),
            contacts = contactStore,
            dataDirectory = dataDirectory,
            displayName = displayName,
        )
        authenticator = AuthenticatedCallHandshake(identities, contactStore)
        dialer = JvmOnionCallDialer(Socks5TorDialer(socksPort = socksPort), authenticator)
    }

    /** Creates the durable stores and Tor-backed presence adapter for the platform runtime. */
    companion object {
        suspend fun open(
            control: TorControl,
            socksPort: Int,
            dataDirectory: String,
            displayName: String,
            scope: CoroutineScope,
            audioRouteController: AudioRouteController = NoOpAudioRouteController,
            inputFactory: () -> AudioInput = { createAudioInput() },
            outputFactory: () -> AudioOutput = { createAudioOutput() },
            storage: Storage = directoryStorage(dataDirectory),
            keyProtector: OnionServiceKeyProtector = createOnionServiceKeyProtector(),
        ): JvmOnionCallRuntime {
            val contacts = ContactStore.open(storage)
            return JvmOnionCallRuntime(
                control, socksPort, dataDirectory, displayName, scope, contacts, storage, keyProtector,
                audioRouteController, inputFactory, outputFactory,
            ).also { it.refreshCallHistory() }
        }
    }

    /** Publishes the local identity and starts accepting only after the verified service is ready. */
    suspend fun goOnline() = lifecycle.withLock {
        check(!closed) { "Call runtime is closed" }
        val onion = presence.prepare()
        mutableAddress.value = onion
        val identity = identities.loadOrCreate { error("Onion service identity was not created") }
        mutablePairing.value = identity.pairingPayload().encode()
        val active = engine ?: CallEngine(
            localOnion = onion,
            displayName = displayName,
            presence = presence,
            dialer = dialer,
            sessions = sessions,
            scope = scope,
        ).also { created ->
            engine = created
            stateJob = scope.launch {
                var wasInCall = false
                created.state.collect { state ->
                    mutableCallState.value = state
                    if (state !is CallState.InCall) {
                        sessions.setMuted(false)
                        mutableSpeakerphoneSelected.value = audioRouteController.isSpeakerphoneSelected()
                    }
                    if (wasInCall && state !is CallState.InCall) refreshCallHistory()
                    wasInCall = state is CallState.InCall
                }
            }
        }
        active.goOnline()
    }

    suspend fun goOffline() = lifecycle.withLock {
        engine?.goOffline()
        mutableCallState.value = Offline
        refreshCallHistory()
    }

    suspend fun dial(peerOnion: String): Boolean = engine?.call(peerOnion) ?: false
    suspend fun accept(): Boolean = engine?.accept() ?: false
    suspend fun decline(): Boolean = engine?.decline() ?: false
    suspend fun hangUp(): Boolean = engine?.hangUp() ?: false

    fun setMuted(muted: Boolean) = sessions.setMuted(muted)

    fun setSpeakerphone(enabled: Boolean): Boolean {
        if (!supportsSpeakerphone) return false
        val applied = audioRouteController.setSpeakerphone(enabled)
        if (applied) mutableSpeakerphoneSelected.value = audioRouteController.isSpeakerphoneSelected()
        return applied
    }

    suspend fun refreshCallHistory() {
        mutableCallHistory.value = withContext(Dispatchers.IO) {
            loadCallHistory(dataDirectory, contactStore.contacts.value)
        }
    }

    val contacts: StateFlow<List<Contact>>
        get() = contactStore.contacts

    suspend fun importPairing(text: String): PairingResult = lifecycle.withLock {
        val parsed = PairingPayload.parse(text)
        when (parsed) {
            is PairingResult.Valid -> {
                contactStore.add(parsed.payload)
                if (mutableAddress.value != null) presence.refreshContacts()
            }
            is PairingResult.Invalid -> Unit
        }
        parsed
    }

    suspend fun addContact(payload: PairingPayload) = lifecycle.withLock {
        contactStore.add(payload)
        if (mutableAddress.value != null) presence.refreshContacts()
    }

    suspend fun removeContact(peerOnion: String): Boolean = lifecycle.withLock {
        val removed = contactStore.remove(peerOnion)
        if (removed && mutableAddress.value != null) presence.refreshContacts()
        removed
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { shutdown() }
    }

    suspend fun shutdown() {
        lifecycle.withLock {
            if (closed) return@withLock
            engine?.close()
            refreshCallHistory()
            stateJob?.cancel()
            stateJob = null
            engine = null
            closed = true
        }
    }
}
