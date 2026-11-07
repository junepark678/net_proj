package dev.junepark.oniontalkie.desktop

import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.call.JvmOnionCallRuntime
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.appDataDir
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallStats
import dev.junepark.oniontalkie.tor.JvmControlTransport
import dev.junepark.oniontalkie.tor.TorConfig
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorProcess
import dev.junepark.oniontalkie.ui.OnionDialController
import dev.junepark.oniontalkie.ui.AppPresenceState
import java.nio.file.Files
import java.nio.file.Paths
import java.awt.Desktop
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DesktopTorController : OnionDialController, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AppPresenceState>(AppPresenceState.Offline)
    private var lifecycleJob: Job? = null
    private var process: TorProcess? = null
    private var control: TorControl? = null
    private var callRuntime: JvmOnionCallRuntime? = null
    private val mutableCallState = MutableStateFlow<CallState>(CallState.Offline)
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableOnionAddress = MutableStateFlow<String?>(null)
    private val mutablePairingText = MutableStateFlow<String?>(null)
    private val mutableMetricsStats = MutableStateFlow<CallStats?>(null)
    private val mutableMetricsCsvPath = MutableStateFlow<String?>(null)
    private val runtimeJobs = mutableListOf<Job>()

    override val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()
    override val callState: StateFlow<CallState> = mutableCallState.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = mutableContacts.asStateFlow()
    override val onionAddress: StateFlow<String?> = mutableOnionAddress.asStateFlow()
    override val pairingText: StateFlow<String?> = mutablePairingText.asStateFlow()
    override val metricsStats: StateFlow<CallStats?> = mutableMetricsStats.asStateFlow()
    override val metricsCsvPath: StateFlow<String?> = mutableMetricsCsvPath.asStateFlow()

    override fun startTor() {
        if (lifecycleJob?.isActive == true) return
        mutableState.value = AppPresenceState.Starting
        lifecycleJob = scope.launch { runTor() }
    }

    override fun stopTor() {
        val running = lifecycleJob
        scope.launch {
            running?.cancelAndJoin()
            releaseResources()
            mutableState.value = AppPresenceState.Offline
            lifecycleJob = null
        }
    }

    private suspend fun runTor() {
        try {
            val config = TorConfig(dataDir = Paths.get(appDataDir(), "tor").toString())
            val launched = TorProcess(config)
            process = launched
            launched.start()

            val connected = TorControl(JvmControlTransport.connect(port = launched.controlPort), scope)
            control = connected
            val cookiePath = Paths.get(config.dataDir, "control_auth_cookie")
            connected.authenticate(Files.readAllBytes(cookiePath))
            connected.bootstrap.collect { progress ->
                if (progress >= BOOTSTRAP_COMPLETE && callRuntime == null) {
                    val runtime = JvmOnionCallRuntime.open(
                        control = connected,
                        socksPort = launched.socksPort,
                        dataDirectory = config.dataDir,
                        displayName = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "Onion Dial",
                        scope = scope,
                    )
                    callRuntime = runtime
                    runtimeJobs += scope.launch { runtime.callState.collect { mutableCallState.value = it } }
                    runtimeJobs += scope.launch { runtime.contacts.collect { mutableContacts.value = it } }
                    runtimeJobs += scope.launch { runtime.onionAddress.collect { mutableOnionAddress.value = it } }
                    runtimeJobs += scope.launch { runtime.pairingText.collect { mutablePairingText.value = it } }
                    runtimeJobs += scope.launch { runtime.metricsStats.collect { mutableMetricsStats.value = it } }
                    runtimeJobs += scope.launch { runtime.metricsCsvPath.collect { mutableMetricsCsvPath.value = it } }
                    runtime.goOnline()
                    mutableState.value = AppPresenceState.TorReady
                } else if (progress < BOOTSTRAP_COMPLETE) {
                    mutableState.value = AppPresenceState.Bootstrapping(progress)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = AppPresenceState.Failed
        } finally {
            releaseResources()
        }
    }

    private suspend fun releaseResources() {
        try {
            callRuntime?.shutdown()
        } catch (_: Exception) {
            // Continue closing the Tor control port and process if a call resource fails to close.
        }
        callRuntime = null
        runtimeJobs.forEach(Job::cancel)
        runtimeJobs.clear()
        mutableCallState.value = CallState.Offline
        mutableContacts.value = emptyList()
        mutableOnionAddress.value = null
        mutablePairingText.value = null
        mutableMetricsStats.value = null
        mutableMetricsCsvPath.value = null
        control?.close()
        control = null
        val running = process ?: return
        try {
            running.stop()
            process = null
        } catch (_: Exception) {
            // The process runtime already attempted graceful and forced termination within its bound.
        }
    }

    override fun close() {
        runBlocking { stopTorNow() }
        scope.cancel()
    }

    private suspend fun stopTorNow() {
        lifecycleJob?.cancelAndJoin()
        releaseResources()
        mutableState.value = AppPresenceState.Offline
        lifecycleJob = null
    }

    override suspend fun importPairing(text: String): PairingResult? = callRuntime?.importPairing(text)
    override suspend fun removeContact(peerOnion: String): Boolean = callRuntime?.removeContact(peerOnion) ?: false
    override suspend fun dial(peerOnion: String): Boolean = callRuntime?.dial(peerOnion) ?: false
    override suspend fun acceptCall(): Boolean = callRuntime?.accept() ?: false
    override suspend fun declineCall(): Boolean = callRuntime?.decline() ?: false
    override suspend fun hangUp(): Boolean = callRuntime?.hangUp() ?: false
    override fun shareMetrics() {
        val file = mutableMetricsCsvPath.value?.let(::File) ?: return
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.parentFile)
        } catch (_: Exception) {
            // The CSV path remains visible and copyable in the UI.
        }
    }

    private companion object {
        const val BOOTSTRAP_COMPLETE = 100
    }
}
