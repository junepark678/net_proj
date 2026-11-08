package dev.junepark.oniontalkie.simulator

import dev.junepark.oniontalkie.audio.NoOpAudioRouteController
import dev.junepark.oniontalkie.call.CallEngine
import dev.junepark.oniontalkie.call.CallEndReason
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.call.OpusCallSessionFactory
import dev.junepark.oniontalkie.metrics.FileMetricsSink
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val DEFAULT_SECONDS = 60L
private const val SIMULATOR_A_ONION = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"
private const val SIMULATOR_B_ONION = "aebagbafaydqqcikbmga2dqpcaireeyuculbogazdinryhi6d4qcmeqd.onion"

fun main(args: Array<String>) = runBlocking {
    val options = SimulatorOptions.parse(args)
    if (options.help) {
        println(SimulatorOptions.usage())
        return@runBlocking
    }
    val outputBase = options.outputDirectory.toAbsolutePath().normalize().toFile()
    val isLanBaseline = options.preset == "lan" && options.impairment == ImpairmentConfig(
        delayMs = 2, jitterMs = 1, lossPct = 0.0, burstMs = 0, kbps = 0.0, seed = 11,
    )
    val scenarioName = if (isLanBaseline) "E1_lan_loopback" else "sim_${options.preset.replace('-', '_')}"
    val outputDirectory = File(outputBase, "$scenarioName/run-${Instant.now().toEpochMilli()}")
    check(outputDirectory.mkdirs() || outputDirectory.isDirectory) {
        "Cannot create simulation output directory: ${outputDirectory.absolutePath}"
    }

    val engineScope = CoroutineScope(SupervisorJob(coroutineContext[Job]) + Dispatchers.Default)
    try {
        if (options.preset == "tor-real") {
            runTorRealSimulation(options, outputDirectory, engineScope)
        } else if (options.preset == "tor-peer") {
            runTorPeerSimulation(options, outputDirectory, engineScope)
        } else {
            val network = SimulatorNetwork(engineScope, options.impairment)
            val frameLimit = options.seconds * 1_000 / 20
            val engineA = makeEngine(
                name = "Simulator A",
                localOnion = SIMULATOR_A_ONION,
                outputDirectory = File(outputDirectory, "a"),
                decodedWav = File(outputDirectory, "decoded-a.wav"),
                wavInput = options.wavInput,
                frames = frameLimit,
                network = network,
                scope = engineScope,
            )
            val engineB = makeEngine(
                name = "Simulator B",
                localOnion = SIMULATOR_B_ONION,
                outputDirectory = File(outputDirectory, "b"),
                decodedWav = File(outputDirectory, "decoded-b.wav"),
                wavInput = options.wavInput,
                frames = frameLimit,
                network = network,
                scope = engineScope,
            )
            try {
                engineA.goOnline()
                engineB.goOnline()
                check(engineA.call(SIMULATOR_B_ONION)) { "Could not start simulated call" }
                withTimeout(15_000) { engineB.state.first { it is CallState.Ringing } }
                check(engineB.accept()) { "Incoming simulated call could not be accepted" }
                withTimeout(20_000) {
                    engineA.state.first { it is CallState.InCall }
                    engineB.state.first { it is CallState.InCall }
                }
                val waitMillis = options.seconds * 1_000 +
                    2 * (options.impairment.delayMs + options.impairment.jitterMs) + 30_000
                val (finalA, finalB) = withTimeout(waitMillis) {
                    val left = engineA.state.first { it is CallState.Online || it is CallState.PeerOffline }
                    val right = engineB.state.first { it is CallState.Online || it is CallState.PeerOffline }
                    left to right
                }
                check(finalA == CallState.Online(CallEndReason.NORMAL)) { "Caller ended unexpectedly: $finalA" }
                check(finalB == CallState.Online(CallEndReason.NORMAL)) { "Receiver ended unexpectedly: $finalB" }
            } finally {
                runCatching { engineA.close() }
                runCatching { engineB.close() }
                runCatching { network.close() }
            }
        }
    } finally {
        engineScope.cancel()
    }

    val csvFiles = outputDirectory.walkTopDown().filter { it.isFile && it.extension == "csv" }.sorted().toList()
    val expectedCsvCount = if (options.preset == "tor-peer") 1 else 2
    check(csvFiles.size == expectedCsvCount) {
        "Expected $expectedCsvCount metrics CSVs, found ${csvFiles.size}"
    }
    val decodedFiles = if (options.preset == "tor-peer") listOf("decoded-desktop.wav")
    else listOf("decoded-a.wav", "decoded-b.wav")
    check(decodedFiles.all { File(outputDirectory, it).length() > 44 }) {
        "Decoded WAV output is missing or empty: ${decodedFiles.joinToString()}"
    }
    println("Simulation complete: preset=${options.preset}, duration=${options.seconds}s")
    decodedFiles.forEach { println("Decoded WAV: ${File(outputDirectory, it).absolutePath}") }
    csvFiles.forEach { println("Metrics CSV: ${it.absolutePath}") }
}

private fun makeEngine(
    name: String,
    localOnion: String,
    outputDirectory: File,
    decodedWav: File,
    wavInput: File?,
    frames: Long,
    network: SimulatorNetwork,
    scope: CoroutineScope,
): CallEngine {
    val sessions = OpusCallSessionFactory(
        inputFactory = {
            if (wavInput != null) WavAudioInput(wavInput, frames) else ToneAudioInput(frames)
        },
        outputFactory = { WavAudioOutput(decodedWav) },
        routeController = NoOpAudioRouteController,
        audioDispatcher = Dispatchers.IO,
        metricsSinkFactory = { peerOnion ->
            val started = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            FileMetricsSink(outputDirectory.absolutePath, started, peerOnion.removeSuffix(".onion").take(12))
        },
    )
    return CallEngine(
        localOnion = localOnion,
        displayName = name,
        presence = network.presence(localOnion),
        dialer = network.dialer(localOnion),
        sessions = sessions,
        scope = scope,
    )
}

internal data class SimulatorOptions(
    val preset: String,
    val impairment: ImpairmentConfig,
    val seconds: Long,
    val wavInput: File?,
    val outputDirectory: java.nio.file.Path,
    val help: Boolean = false,
) {
    companion object {
        fun parse(args: Array<String>): SimulatorOptions {
            var preset = "lan"
            var delayMs: Long? = null
            var jitterMs: Long? = null
            var lossPct: Double? = null
            var burstMs: Long? = null
            var kbps: Double? = null
            var seconds = DEFAULT_SECONDS
            var wav: File? = null
            var output = java.nio.file.Path.of("build", "simulator")
            var help = false
            var index = 0
            while (index < args.size) {
                val argument = args[index]
                if (argument == "--help" || argument == "-h") {
                    help = true
                    index += 1
                    continue
                }
                require(index + 1 < args.size) { "Missing value after $argument" }
                val value = args[index + 1]
                when (argument) {
                    "--preset" -> preset = value
                    "--delay" -> delayMs = value.toLongOrNull() ?: error("Invalid --delay: $value")
                    "--jitter" -> jitterMs = value.toLongOrNull() ?: error("Invalid --jitter: $value")
                    "--loss" -> lossPct = value.toDoubleOrNull() ?: error("Invalid --loss: $value")
                    "--burst" -> burstMs = value.toLongOrNull() ?: error("Invalid --burst: $value")
                    "--kbps" -> kbps = value.toDoubleOrNull() ?: error("Invalid --kbps: $value")
                    "--seconds" -> seconds = value.toLongOrNull() ?: error("Invalid --seconds: $value")
                    "--wav" -> wav = File(value)
                    "--out" -> output = java.nio.file.Path.of(value)
                    else -> error("Unknown option: $argument")
                }
                index += 2
            }
            val presetConfig = when (preset) {
                "lan" -> ImpairmentConfig(delayMs = 2, jitterMs = 1, seed = 11)
                "tor-typical" -> ImpairmentConfig(delayMs = 400, jitterMs = 150, lossPct = 1.0, burstMs = 100, kbps = 96.0, seed = 17)
                "tor-bad" -> ImpairmentConfig(delayMs = 900, jitterMs = 400, lossPct = 5.0, burstMs = 200, kbps = 64.0, seed = 23)
                "tor-real" -> ImpairmentConfig(delayMs = 0, jitterMs = 0, seed = 31)
                "tor-peer" -> ImpairmentConfig(delayMs = 0, jitterMs = 0, seed = 37)
                else -> error("Unknown preset '$preset'. Choose lan, tor-typical, tor-bad, tor-real, or tor-peer.")
            }
            require(seconds in 1..3_600) { "--seconds must be between 1 and 3600" }
            require(wav == null || wav.isFile) { "WAV input does not exist: ${wav?.path}" }
            if (preset == "tor-real" || preset == "tor-peer") {
                require(delayMs == null && jitterMs == null && lossPct == null && burstMs == null && kbps == null) {
                    "Network impairment options do not apply to real-Tor presets"
                }
            }
            val impairment = presetConfig.copy(
                delayMs = delayMs ?: presetConfig.delayMs,
                jitterMs = jitterMs ?: presetConfig.jitterMs,
                lossPct = lossPct ?: presetConfig.lossPct,
                burstMs = burstMs ?: presetConfig.burstMs,
                kbps = kbps ?: presetConfig.kbps,
            )
            return SimulatorOptions(preset, impairment, seconds, wav, output, help)
        }

        fun usage() = """
            Onion Dial call simulator
            Usage: ./gradlew :tools:simulate --args="[options]"
            Options:
              --preset lan|tor-typical|tor-bad|tor-real|tor-peer   Network profile (default: lan)
              --delay MS                        One-way fixed delay
              --jitter MS                       Uniform +/- one-way jitter
              --loss PERCENT                    Target long-run AUDIO frame loss
              --burst MS                        Mean duration of a contiguous loss burst
              --kbps RATE                       Per-direction bandwidth cap; 0 is unlimited
              --seconds N                       Call duration (default: 60)
              --wav FILE                        Source PCM WAV; expects 48 kHz mono 16-bit or Java Sound conversion
              --out DIRECTORY                   Output directory (default: build/simulator)

            Without --wav, the simulator sends a deterministic 440 Hz tone. Each endpoint writes
            its decoded receive audio to a WAV and its per-call metrics CSV under the output folder.
            tor-peer starts one desktop Tor endpoint, prints its pairing payload, then reads the
            Android pairing payload from stdin and accepts the incoming Android call.
        """.trimIndent()
    }
}
