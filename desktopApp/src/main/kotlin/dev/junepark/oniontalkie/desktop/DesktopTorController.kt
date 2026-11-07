package dev.junepark.oniontalkie.desktop

import dev.junepark.oniontalkie.identity.appDataDir
import dev.junepark.oniontalkie.tor.JvmControlTransport
import dev.junepark.oniontalkie.tor.TorConfig
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorProcess
import dev.junepark.oniontalkie.ui.AppPresenceController
import dev.junepark.oniontalkie.ui.AppPresenceState
import java.nio.file.Files
import java.nio.file.Paths
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

class DesktopTorController : AppPresenceController, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<AppPresenceState>(AppPresenceState.Offline)
    private var lifecycleJob: Job? = null
    private var process: TorProcess? = null
    private var control: TorControl? = null

    override val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()

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
                mutableState.value = if (progress >= BOOTSTRAP_COMPLETE) AppPresenceState.TorReady
                else AppPresenceState.Bootstrapping(progress)
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

    private companion object {
        const val BOOTSTRAP_COMPLETE = 100
    }
}
