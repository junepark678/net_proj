package dev.junepark.oniontalkie.simulator

import dev.junepark.oniontalkie.audio.AudioFormat
import dev.junepark.oniontalkie.call.CallEndReason
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.call.JvmOnionCallRuntime
import dev.junepark.oniontalkie.identity.PairingPayload
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.tor.JvmControlTransport
import dev.junepark.oniontalkie.tor.TorConfig
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorEvent
import dev.junepark.oniontalkie.tor.TorProcess
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Runs the same two-engine call through two independent local Tor daemons and onion services. */
internal suspend fun runTorRealSimulation(
    options: SimulatorOptions,
    outputDirectory: File,
    scope: CoroutineScope,
) {
    val privateRoot = Files.createTempDirectory("onion-dial-tor-real-")
    var clientA: TorClient? = null
    var clientB: TorClient? = null
    var runtimeA: JvmOnionCallRuntime? = null
    var runtimeB: JvmOnionCallRuntime? = null
    val eventWatchers = mutableListOf<Job>()
    try {
        val frameLimit = options.seconds * 1_000 / 20
        val sourceWav = options.wavInput ?: createToneWav(File(outputDirectory, "source.wav"), frameLimit)
        val clients = coroutineScope {
            val first = async { startTorClient(scope, privateRoot.resolve("tor-a"), File(outputDirectory, "tor-a.log")) }
            val second = async { startTorClient(scope, privateRoot.resolve("tor-b"), File(outputDirectory, "tor-b.log")) }
            var readyA: TorClient? = null
            try {
                readyA = first.await()
                readyA to second.await()
            } catch (failure: Throwable) {
                readyA?.close()
                throw failure
            }
        }
        val torA = clients.first
        val torB = clients.second
        clientA = torA
        clientB = torB
        val descriptorsA = Channel<TorEvent.HiddenServiceDescriptor>(Channel.UNLIMITED)
        val descriptorsB = Channel<TorEvent.HiddenServiceDescriptor>(Channel.UNLIMITED)
        eventWatchers += scope.launch {
            torA.control.events.collect { event ->
                logTorEvent("A", event)
                if (event is TorEvent.HiddenServiceDescriptor) descriptorsA.send(event)
            }
        }
        eventWatchers += scope.launch {
            torB.control.events.collect { event ->
                logTorEvent("B", event)
                if (event is TorEvent.HiddenServiceDescriptor) descriptorsB.send(event)
            }
        }

        val callRuntimeA = JvmOnionCallRuntime.open(
            control = torA.control,
            socksPort = torA.process.socksPort,
            dataDirectory = torA.process.dataDir,
            displayName = "Tor Simulator A",
            scope = scope,
            inputFactory = { WavAudioInput(sourceWav, frameLimit) },
            outputFactory = { WavAudioOutput(File(outputDirectory, "decoded-a.wav")) },
        )
        runtimeA = callRuntimeA
        val callRuntimeB = JvmOnionCallRuntime.open(
            control = torB.control,
            socksPort = torB.process.socksPort,
            dataDirectory = torB.process.dataDir,
            displayName = "Tor Simulator B",
            scope = scope,
            inputFactory = { WavAudioInput(sourceWav, frameLimit) },
            outputFactory = { WavAudioOutput(File(outputDirectory, "decoded-b.wav")) },
        )
        runtimeB = callRuntimeB
        callRuntimeA.goOnline()
        callRuntimeB.goOnline()

        val pairingA = checkNotNull(callRuntimeA.pairingText.value) { "Tor A pairing payload was not published" }
        val pairingB = checkNotNull(callRuntimeB.pairingText.value) { "Tor B pairing payload was not published" }
        val peerA = (PairingPayload.parse(pairingA) as? PairingResult.Valid)?.payload
            ?: error("Tor A pairing payload is invalid")
        val peerB = (PairingPayload.parse(pairingB) as? PairingResult.Valid)?.payload
            ?: error("Tor B pairing payload is invalid")

        check(callRuntimeA.importPairing(pairingB) is PairingResult.Valid) { "Could not pair Tor A with Tor B" }
        check(callRuntimeB.importPairing(pairingA) is PairingResult.Valid) { "Could not pair Tor B with Tor A" }
        // ADD_ONION returns before the descriptor has reached the HSDirs. Explicitly fetch
        // each paired descriptor before starting the call, retrying transient NOT_FOUNDs.
        awaitPeerDescriptor(torA.control, descriptorsA, peerB.onion, "A")
        awaitPeerDescriptor(torB.control, descriptorsB, peerA.onion, "B")

        coroutineScope {
            val acceptIncoming = async {
                withTimeout(240_000) { callRuntimeB.callState.first { it is CallState.Ringing } }
                check(callRuntimeB.accept()) { "Tor B could not accept the incoming call" }
            }
            var connected = false
            val connectDeadline = System.nanoTime() + CONNECT_RETRY_WINDOW_NANOS
            var attempt = 0
            while (!connected) {
                attempt += 1
                println(
                    "Tor call attempt $attempt: A=${callRuntimeA.callState.value::class.simpleName}, " +
                        "B=${callRuntimeB.callState.value::class.simpleName}",
                )
                val previousState = callRuntimeA.callState.value
                check(callRuntimeA.dial(peerB.onion)) {
                    "Tor A was not online to dial (state=${callRuntimeA.callState.value::class.simpleName})"
                }
                println("Tor call attempt $attempt admitted")
                val admittedState = withTimeout(45_000) {
                    callRuntimeA.callState.first { state ->
                        state is CallState.Dialing || state is CallState.InCall ||
                            (state is CallState.PeerOffline && state != previousState) ||
                            (state is CallState.Online && state.lastEnd != null && state != previousState)
                    }
                }
                val state = if (admittedState is CallState.Dialing) withTimeout(45_000) {
                    callRuntimeA.callState.first {
                        it is CallState.InCall || it is CallState.PeerOffline ||
                            (it is CallState.Online && it.lastEnd != null)
                    }
                } else admittedState
                when (state) {
                    is CallState.InCall -> connected = true
                    is CallState.PeerOffline -> {
                        check(System.nanoTime() < connectDeadline) {
                            "Tor onion service remained unavailable after repeated attempts"
                        }
                        delay(5_000)
                    }
                    is CallState.Online -> {
                        check(state.lastEnd != CallEndReason.NORMAL) {
                            "Tor call ended normally before media started"
                        }
                        check(System.nanoTime() < connectDeadline) {
                            "Tor onion service remained unavailable after repeated attempts"
                        }
                        println("Tor dial attempt failed (${state.lastEnd}); waiting before retry")
                        delay(5_000)
                    }
                    else -> error("Unexpected Tor call state: $state")
                }
            }
            acceptIncoming.await()
            withTimeout(options.seconds * 1_000 + 30_000) {
                callRuntimeB.callState.first { it is CallState.InCall }
                delay(options.seconds * 1_000)
                // EOF normally closes the media call at the requested duration. If Tor
                // backpressure has not drained the capture queue yet, end the call explicitly.
                if (callRuntimeA.callState.value is CallState.InCall) callRuntimeA.hangUp()
                val endedA = callRuntimeA.callState.first { it is CallState.Online || it is CallState.PeerOffline }
                val endedB = callRuntimeB.callState.first { it is CallState.Online || it is CallState.PeerOffline }
                check(endedA == CallState.Online(CallEndReason.NORMAL)) { "Tor caller ended unexpectedly: $endedA" }
                check(endedB == CallState.Online(CallEndReason.NORMAL)) { "Tor receiver ended unexpectedly: $endedB" }
            }
        }
    } finally {
        eventWatchers.forEach { it.cancel() }
        try { runtimeA?.shutdown() } catch (_: Exception) { }
        try { runtimeB?.shutdown() } catch (_: Exception) { }
        try { clientA?.close() } catch (_: Exception) { }
        try { clientB?.close() } catch (_: Exception) { }
        try {
            copyMetrics(privateRoot.resolve("tor-a/calls"), outputDirectory.toPath().resolve("tor-a/calls"))
            copyMetrics(privateRoot.resolve("tor-b/calls"), outputDirectory.toPath().resolve("tor-b/calls"))
        } finally {
            deletePrivateTree(privateRoot)
        }
    }
}

/** Runs a desktop peer against the real Onion Dial app on an Android device or emulator. */
internal suspend fun runTorPeerSimulation(
    options: SimulatorOptions,
    outputDirectory: File,
    scope: CoroutineScope,
) {
    val privateRoot = Files.createTempDirectory("onion-dial-tor-peer-")
    var client: TorClient? = null
    var runtime: JvmOnionCallRuntime? = null
    var eventWatcher: Job? = null
    try {
        val frameLimit = options.seconds * 1_000 / 20
        val sourceWav = options.wavInput ?: createToneWav(File(outputDirectory, "source.wav"), frameLimit)
        val bootstrapStartedNanos = System.nanoTime()
        val tor = startTorClient(
            scope,
            privateRoot.resolve("tor-desktop"),
            File(outputDirectory, "tor-desktop.log"),
        )
        println("DESKTOP_TOR_BOOTSTRAP_MS=${(System.nanoTime() - bootstrapStartedNanos) / 1_000_000L}")
        client = tor
        eventWatcher = scope.launch {
            tor.control.events.collect { event -> logTorEvent("desktop", event) }
        }

        val desktopRuntime = JvmOnionCallRuntime.open(
            control = tor.control,
            socksPort = tor.process.socksPort,
            dataDirectory = tor.process.dataDir,
            displayName = "Onion-Dial-Desktop",
            scope = scope,
            inputFactory = { WavAudioInput(sourceWav, frameLimit) },
            outputFactory = { WavAudioOutput(File(outputDirectory, "decoded-desktop.wav")) },
        )
        runtime = desktopRuntime
        desktopRuntime.goOnline()
        val localPairing = checkNotNull(desktopRuntime.pairingText.value) {
            "Desktop pairing payload was not published"
        }
        println("DESKTOP_PAIRING_TEXT=$localPairing")
        println("Paste the Android pairing payload, then press Enter:")
        val androidPairing = withContext(Dispatchers.IO) { System.`in`.bufferedReader().readLine() }
            ?: error("No Android pairing payload was provided")
        check(PairingPayload.parse(androidPairing) is PairingResult.Valid) {
            "Android pairing payload is invalid"
        }
        check(desktopRuntime.importPairing(androidPairing) is PairingResult.Valid) {
            "Could not authorize the Android peer"
        }
        println("ANDROID_PEER_AUTHORIZED")
        println("Waiting for an incoming Android call (up to 10 minutes)")

        withTimeout(10 * 60_000L) { desktopRuntime.callState.first { it is CallState.Ringing } }
        println("ANDROID_CALL_RINGING")
        val acceptanceStartedNanos = System.nanoTime()
        check(desktopRuntime.accept()) { "Could not accept the Android call" }
        withTimeout(45_000L) { desktopRuntime.callState.first { it is CallState.InCall } }
        println("ANDROID_DESKTOP_CALL_CONNECTED")
        println("DESKTOP_ACCEPT_TO_INCALL_MS=${(System.nanoTime() - acceptanceStartedNanos) / 1_000_000L}")

        val ended = withTimeout(options.seconds * 1_000 + 60_000) {
            desktopRuntime.callState.first { it is CallState.Online || it is CallState.PeerOffline }
        }
        check(ended == CallState.Online(CallEndReason.NORMAL)) {
            "Desktop call ended unexpectedly: $ended"
        }
    } finally {
        eventWatcher?.cancelAndJoin()
        try { runtime?.shutdown() } catch (_: Exception) { }
        try { client?.close() } catch (_: Exception) { }
        try {
            copyMetrics(
                privateRoot.resolve("tor-desktop/calls"),
                outputDirectory.toPath().resolve("desktop/calls"),
            )
        } finally {
            deletePrivateTree(privateRoot)
        }
    }
}

private fun logTorEvent(client: String, event: TorEvent) {
    if (event is TorEvent.HiddenServiceDescriptor &&
        event.action in setOf("CREATED", "REQUESTED", "FAILED", "RECEIVED")
    ) {
        println(
            "Tor $client HS_DESC action=${event.action} auth=${event.authType} " +
                "reason=${event.fields["REASON"] ?: "none"}",
        )
    }
}

private suspend fun awaitPeerDescriptor(
    control: TorControl,
    events: Channel<TorEvent.HiddenServiceDescriptor>,
    onion: String,
    client: String,
) {
    val expectedAddress = onion.removeSuffix(".onion")
    val deadline = System.nanoTime() + DESCRIPTOR_FETCH_WINDOW_NANOS
    var attempt = 0
    while (true) {
        attempt++
        control.command("HSFETCH $expectedAddress")
        val result = withTimeoutOrNull<TorEvent.HiddenServiceDescriptor>(DESCRIPTOR_FETCH_ATTEMPT_TIMEOUT_MS) {
            events.receiveAsFlow().first {
                it.address == expectedAddress && it.action in setOf("RECEIVED", "FAILED")
            }
        }
        if (result?.action == "RECEIVED") {
            println("Tor $client fetched the paired hidden-service descriptor on attempt $attempt")
            return
        }
        val reason = result?.fields?.get("REASON") ?: "no descriptor event"
        check(reason in setOf("NOT_FOUND", "QUERY_NO_HSDIR", "QUERY_RATE_LIMITED", "no descriptor event")) {
            "Tor $client could not fetch the paired hidden-service descriptor (reason=$reason)"
        }
        check(System.nanoTime() < deadline) {
            "Tor $client could not fetch the paired hidden-service descriptor after repeated attempts"
        }
        println("Tor $client descriptor fetch attempt $attempt unavailable ($reason); waiting before retry")
        delay(DESCRIPTOR_FETCH_RETRY_DELAY_MS)
    }
}

private fun createToneWav(file: File, frameLimit: Long): File {
    val output = WavAudioOutput(file)
    output.start()
    try {
        repeat(frameLimit.toInt()) { frame ->
            val pcm = ShortArray(AudioFormat.SAMPLES_PER_FRAME) { index ->
                val sampleIndex = frame.toLong() * AudioFormat.SAMPLES_PER_FRAME + index
                (10_000.0 * kotlin.math.sin(
                    2.0 * Math.PI * 440.0 * sampleIndex / AudioFormat.SAMPLE_RATE_HZ,
                )).toInt().toShort()
            }
            output.write(pcm)
        }
    } finally {
        output.stop()
    }
    return file
}

private suspend fun startTorClient(scope: CoroutineScope, dataDirectory: Path, logFile: File): TorClient {
    var lastFailure: Throwable? = null
    repeat(BOOTSTRAP_ATTEMPTS) { attemptIndex ->
        val attempt = attemptIndex + 1
        val process = TorProcess(TorConfig(dataDir = dataDirectory.toString(), startupTimeoutMillis = 60_000))
        val logLines = Collections.synchronizedList(mutableListOf<String>())
        var control: TorControl? = null
        var logJob: Job? = null
        try {
            val stream = process.start()
            logJob = scope.launch(Dispatchers.IO) {
                stream.collect { line ->
                    synchronized(logLines) {
                        logLines += line.text
                        if (logLines.size > MAX_RETAINED_TOR_LINES) logLines.removeAt(0)
                    }
                }
            }
            control = TorControl(
                JvmControlTransport.connect(port = process.controlPort),
                scope,
            )
            val cookie = Files.readAllBytes(Path.of(process.dataDir, "control_auth_cookie"))
            control.authenticate(cookie)
            withTimeout(BOOTSTRAP_TIMEOUT_MS) { control.bootstrap.first { it == 100 } }
            return TorClient(process, control, logJob, logFile, attempt, logLines)
        } catch (failure: Throwable) {
            lastFailure = failure
            control?.close()
            withContext(kotlinx.coroutines.NonCancellable) {
                logJob?.cancelAndJoin()
                runCatching { process.stop() }
                appendAttemptEvidence(logFile, attempt, failure, logLines)
            }
            if (failure is CancellationException) currentCoroutineContext().ensureActive()
            if (attempt < BOOTSTRAP_ATTEMPTS) delay(1_000)
        }
    }
    throw IllegalStateException(
        "Tor failed to bootstrap after $BOOTSTRAP_ATTEMPTS attempts; see ${logFile.absolutePath}",
        lastFailure,
    )
}

private class TorClient(
    val process: TorProcess,
    val control: TorControl,
    private val logJob: Job,
    private val logFile: File,
    private val attempt: Int,
    private val logLines: List<String>,
) {
    suspend fun close() = withContext(kotlinx.coroutines.NonCancellable) {
        control.close()
        try {
            process.stop()
        } finally {
            logJob.join()
            appendAttemptEvidence(logFile, attempt, null, logLines)
        }
    }
}

private suspend fun appendAttemptEvidence(
    file: File,
    attempt: Int,
    failure: Throwable?,
    lines: List<String>,
) = withContext(Dispatchers.IO) {
    file.parentFile?.mkdirs()
    val evidence = buildString {
        appendLine("Tor startup attempt $attempt")
        failure?.let { appendLine("Failure: ${it::class.simpleName}: ${it.message}") }
        synchronized(lines) { lines.takeLast(MAX_RETAINED_TOR_LINES).forEach(::appendLine) }
        appendLine()
    }
    Files.writeString(
        file.toPath(), evidence,
        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
    )
}

private suspend fun copyMetrics(source: Path, destination: Path) = withContext(Dispatchers.IO) {
    if (!Files.isDirectory(source)) return@withContext
    Files.walk(source).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path) &&
                (path.fileName.toString().endsWith(".csv") || path.fileName.toString() == "summary.json")
        }.forEach { path ->
            val target = destination.resolve(source.relativize(path))
            Files.createDirectories(target.parent)
            Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private suspend fun deletePrivateTree(root: Path) = withContext(Dispatchers.IO) {
    if (!Files.exists(root)) return@withContext
    Files.walk(root).use { paths ->
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private const val BOOTSTRAP_ATTEMPTS = 2
private const val MAX_RETAINED_TOR_LINES = 500
private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
private const val CONNECT_RETRY_WINDOW_NANOS = 240_000_000_000L
private const val DESCRIPTOR_FETCH_WINDOW_NANOS = 240_000_000_000L
private const val DESCRIPTOR_FETCH_ATTEMPT_TIMEOUT_MS = 30_000L
private const val DESCRIPTOR_FETCH_RETRY_DELAY_MS = 5_000L
