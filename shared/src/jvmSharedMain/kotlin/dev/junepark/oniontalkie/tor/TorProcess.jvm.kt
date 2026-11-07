package dev.junepark.oniontalkie.tor

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Desktop defaults to the verified Expert Bundle under build/tor and then `tor` on PATH.
 * Android callers pass `<nativeLibraryDir>/libtor.so` from the pinned tor-android AAR.
 */
actual class TorProcess actual constructor(private val config: TorConfig) {
    private val lifecycle = Mutex()
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: RunningProcess? = null
    private var ports: Ports? = null

    actual val controlPort: Int
        get() = checkNotNull(ports) { "Tor is not running" }.control

    actual val socksPort: Int
        get() = checkNotNull(ports) { "Tor is not running" }.socks

    actual val dataDir: String
        get() = Paths.get(config.dataDir).toAbsolutePath().normalize().toString()

    actual suspend fun start(): Flow<TorLogLine> {
        var launched: RunningProcess? = null
        try {
            val run = withContext(Dispatchers.IO) {
                lifecycle.withLock {
                    check(running == null) { "Tor is already running" }
                    val dataDir = Paths.get(config.dataDir).toAbsolutePath().normalize()
                    val normalizedConfig = config.copy(dataDir = dataDir.toString())
                    val clientAuthDir = Paths.get(normalizedConfig.clientOnionAuthDirectory)
                    Files.createDirectories(dataDir)
                    Files.createDirectories(clientAuthDir)
                    makePrivate(dataDir)
                    makePrivate(clientAuthDir)

                    val controlPortFile = dataDir.resolve(CONTROL_PORT_FILE)
                    val cookieFile = dataDir.resolve(COOKIE_FILE)
                    val torrcFile = dataDir.resolve(TORRC_FILE)
                    Files.deleteIfExists(controlPortFile)
                    Files.deleteIfExists(cookieFile)
                    Files.write(
                        torrcFile,
                        renderTorrc(normalizedConfig, controlPortFile.toString()).toByteArray(Charsets.UTF_8),
                    )
                    makePrivate(torrcFile)

                    val binary = resolveBinary(config.executablePath)
                    val builder = ProcessBuilder(binary.command, "--defaults-torrc", nullDevice(), "-f", torrcFile.toString())
                        .redirectErrorStream(true)
                        .directory(binary.workingDirectory)
                    binary.libraryDirectory?.let { libraryDirectory ->
                        val os = System.getProperty("os.name").orEmpty().lowercase()
                        val variable = when {
                            os.contains("windows") -> "PATH"
                            os.contains("mac") -> "DYLD_LIBRARY_PATH"
                            else -> "LD_LIBRARY_PATH"
                        }
                        val inherited = builder.environment()[variable].orEmpty()
                        builder.environment()[variable] = if (inherited.isBlank()) {
                            libraryDirectory.absolutePath
                        } else "${libraryDirectory.absolutePath}${File.pathSeparator}$inherited"
                    }
                    val process = builder.start()
                    val logLines = Channel<TorLogLine>(
                        capacity = LOG_BUFFER_LINES,
                        onBufferOverflow = BufferOverflow.DROP_OLDEST,
                    )
                    val started = RunningProcess(
                        process = process,
                        controlPortFile = controlPortFile,
                        cookieFile = cookieFile,
                        logs = logLines,
                    )
                    running = started
                    launched = started
                    started.logJob = readerScope.launch(Dispatchers.IO) { pumpLogs(started) }
                    started
                }
            }

            val discoveredPorts = withTimeout(config.startupTimeoutMillis) {
                withContext(Dispatchers.IO) { awaitLocalPorts(run) }
            }
            lifecycle.withLock {
                check(running === run) { "Tor was stopped while it was starting" }
                ports = discoveredPorts
            }
            return run.logs.receiveAsFlow()
        } catch (failure: Throwable) {
            launched?.let { run ->
                try {
                    withContext(NonCancellable) { stop() }
                } catch (stopFailure: Throwable) {
                    failure.addSuppressed(stopFailure)
                }
                run.logs.close()
            }
            if (failure is CancellationException) throw failure
            if (failure is TorProcessException) throw failure
            throw TorProcessException("Tor failed to start: ${failure.message ?: failure::class.simpleName}", failure)
        }
    }

    actual suspend fun stop() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                val run = running ?: return@withLock
                val stopped = withContext(Dispatchers.IO) {
                    destroyAndReap(run.process, config.shutdownTimeoutMillis)
                }
                if (!stopped) {
                    throw TorProcessException("Tor did not exit within ${config.shutdownTimeoutMillis} ms")
                }
                runCatching { run.process.inputStream.close() }
                run.logJob?.cancelAndJoin()
                run.logs.close()
                running = null
                ports = null
            }
        }
    }

    private suspend fun awaitLocalPorts(run: RunningProcess): Ports {
        while (true) {
            if (!run.process.isAlive) {
                throw TorProcessException("Tor exited before its listeners were ready (exit ${run.process.exitValue()})")
            }
            val controlPort = readControlPort(run.controlPortFile)
            val cookie = if (Files.exists(run.cookieFile)) Files.readAllBytes(run.cookieFile) else null
            if (controlPort != null && cookie?.size == COOKIE_SIZE) {
                val control = TorControl(
                    JvmControlTransport.connect(host = "127.0.0.1", port = controlPort),
                    readerScope,
                )
                try {
                    control.authenticate(cookie)
                    val socksPort = parseSocksPort(control.getInfo("net/listeners/socks"))
                    return Ports(controlPort, socksPort)
                } finally {
                    control.close()
                }
            }
            delay(PORT_POLL_INTERVAL_MILLIS)
        }
    }

    private suspend fun pumpLogs(run: RunningProcess) {
        try {
            run.process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    run.logs.trySend(TorLogLine(monotonicMillis(), line))
                }
            }
        } catch (failure: IOException) {
            if (run.process.isAlive) {
                run.logs.trySend(TorLogLine(monotonicMillis(), "Tor log stream failed: ${failure.message}"))
            }
        } finally {
            run.logs.close()
        }
    }

    private fun readControlPort(path: Path): Int? {
        if (!Files.exists(path)) return null
        val entry = Files.readAllLines(path, Charsets.UTF_8)
            .firstOrNull { it.startsWith("PORT=") }
            ?.substringAfter("PORT=")
            ?.trim()
            ?: throw TorProcessException("Tor wrote a malformed control-port file")
        val port = entry.substringAfterLast(':').toIntOrNull()
            ?: throw TorProcessException("Tor wrote a malformed control-port address")
        if (port !in 1..65535) throw TorProcessException("Tor reported an invalid control port")
        return port
    }

    private fun parseSocksPort(replyValue: String): Int {
        val match = LOOPBACK_LISTENER.find(replyValue)
            ?: throw TorProcessException("Tor did not report a loopback SOCKS listener")
        val port = match.groupValues[1].toIntOrNull()
            ?: throw TorProcessException("Tor reported a malformed SOCKS listener")
        if (port !in 1..65535) throw TorProcessException("Tor reported an invalid SOCKS port")
        return port
    }

    private fun makePrivate(path: Path) {
        try {
            Files.setPosixFilePermissions(path, PRIVATE_PERMISSIONS)
        } catch (_: UnsupportedOperationException) {
            // Windows ACLs inherit from the private app-data directory.
        }
    }

    private data class Ports(val control: Int, val socks: Int)

    private data class RunningProcess(
        val process: Process,
        val controlPortFile: Path,
        val cookieFile: Path,
        val logs: Channel<TorLogLine>,
        var logJob: kotlinx.coroutines.Job? = null,
    )

    private data class TorBinary(
        val command: String,
        val workingDirectory: File,
        val libraryDirectory: File?,
    )

    private fun resolveBinary(explicitPath: String?): TorBinary {
        val userProvided = explicitPath?.takeIf { it.isNotBlank() }
            ?: System.getenv("TOR_PROCESS_BINARY")?.takeIf { it.isNotBlank() }
        if (userProvided != null) return describeBinary(userProvided)

        val bundled = locateBundledDesktopBinary()
        if (bundled != null && Files.isExecutable(bundled.toPath())) return describeBinary(bundled.absolutePath)

        val command = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "tor.exe" else "tor"
        return TorBinary(command, File(System.getProperty("user.dir").orEmpty()), null)
    }

    private fun describeBinary(path: String): TorBinary {
        val executable = File(path).absoluteFile
        val executableDirectory = executable.parentFile ?: File(System.getProperty("user.dir").orEmpty())
        val bundleRoot = executableDirectory.parentFile
        val workingDirectory = if (bundleRoot != null && File(bundleRoot, "data/geoip").isFile) bundleRoot else executableDirectory
        return TorBinary(executable.absolutePath, workingDirectory, executableDirectory)
    }

    private fun nullDevice(): String = if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "NUL" else "/dev/null"

    private fun monotonicMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - originNanos)

    private companion object {
        const val CONTROL_PORT_FILE = "control-port"
        const val COOKIE_FILE = "control_auth_cookie"
        const val TORRC_FILE = "torrc"
        const val COOKIE_SIZE = 32
        const val LOG_BUFFER_LINES = 512
        const val PORT_POLL_INTERVAL_MILLIS = 50L
        val LOOPBACK_LISTENER = Regex("(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})")
        val PRIVATE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val originNanos = System.nanoTime()
    }
}

private fun destroyAndReap(process: Process, timeoutMillis: Long): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    if (process.isAlive) process.destroy()
    val gracefulWait = minOf(GRACEFUL_STOP_MILLIS, remainingMillis(deadline))
    if (process.waitFor(gracefulWait, TimeUnit.MILLISECONDS)) return true
    if (process.isAlive) process.destroyForcibly()
    val remaining = remainingMillis(deadline)
    return process.waitFor(remaining, TimeUnit.MILLISECONDS)
}

private fun remainingMillis(deadlineNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0)).coerceAtLeast(1)

internal fun locateBundledDesktopBinary(): File? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val arch = System.getProperty("os.arch").orEmpty().lowercase()
    val platform = when {
        os.contains("linux") && (arch == "x86_64" || arch == "amd64") -> "linux-x86_64"
        os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> "linux-aarch64"
        os.contains("mac") && (arch == "x86_64" || arch == "amd64") -> "macos-x86_64"
        os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "macos-aarch64"
        os.contains("windows") && (arch == "x86_64" || arch == "amd64") -> "windows-x86_64"
        else -> return null
    }
    val executable = if (platform.startsWith("windows")) "tor.exe" else "tor"
    val start = Paths.get(System.getProperty("user.dir").orEmpty()).toAbsolutePath().normalize()
    return generateSequence(start) { it.parent }
        .map { it.resolve("build/tor/$platform/tor/$executable") }
        .firstOrNull(Files::isExecutable)
        ?.toFile()
}

private const val GRACEFUL_STOP_MILLIS = 1_000L
