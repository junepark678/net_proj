package dev.junepark.oniontalkie

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.junepark.oniontalkie.ui.App
import dev.junepark.oniontalkie.ui.AppPresenceState
import dev.junepark.oniontalkie.ui.OnionDialController
import dev.junepark.oniontalkie.ui.PairingQrAction
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity(), OnionDialController {
    private val mutableState = MutableStateFlow<AppPresenceState>(AppPresenceState.Offline)
    override val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()
    private val mutableCallState = MutableStateFlow<CallState>(CallState.Offline)
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableOnionAddress = MutableStateFlow<String?>(null)
    private val mutablePairingText = MutableStateFlow<String?>(null)
    private val mutableMetricsStats = MutableStateFlow<CallStats?>(null)
    private val mutableMetricsCsvPath = MutableStateFlow<String?>(null)
    override val callState: StateFlow<CallState> = mutableCallState.asStateFlow()
    override val contacts: StateFlow<List<Contact>> = mutableContacts.asStateFlow()
    override val onionAddress: StateFlow<String?> = mutableOnionAddress.asStateFlow()
    override val pairingText: StateFlow<String?> = mutablePairingText.asStateFlow()
    override val metricsStats: StateFlow<CallStats?> = mutableMetricsStats.asStateFlow()
    override val metricsCsvPath: StateFlow<String?> = mutableMetricsCsvPath.asStateFlow()

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var boundService: OnionTalkieService? = null
    private var stateJob: Job? = null
    private val serviceJobs = mutableListOf<Job>()
    private var bound = false
    private var pendingPairingScan: ((String) -> Unit)? = null

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ensureMicrophonePermission() else Toast.makeText(
            this,
            R.string.notification_permission_denied,
            Toast.LENGTH_LONG,
        ).show()
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startTorService() else Toast.makeText(
            this,
            R.string.microphone_permission_denied,
            Toast.LENGTH_LONG,
        ).show()
    }

    private val pairingCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchPairingScanner() else {
            pendingPairingScan = null
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val pairingScanner = registerForActivityResult(ScanContract()) { result ->
        val callback = pendingPairingScan
        pendingPairingScan = null
        result.contents?.let { callback?.invoke(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? OnionTalkieService.LocalBinder ?: return
            boundService = local.service()
            stateJob?.cancel()
            serviceJobs.forEach(Job::cancel)
            serviceJobs.clear()
            stateJob = activityScope.launch {
                val service = boundService ?: return@launch
                launch { service.state.collect { mutableState.value = it } }
                launch { service.callState.collect { mutableCallState.value = it } }
                launch { service.contacts.collect { mutableContacts.value = it } }
                launch { service.onionAddress.collect { mutableOnionAddress.value = it } }
                launch { service.pairingText.collect { mutablePairingText.value = it } }
                launch { service.metricsStats.collect { mutableMetricsStats.value = it } }
                launch { service.metricsCsvPath.collect { mutableMetricsCsvPath.value = it } }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateJob?.cancel()
            stateJob = null
            serviceJobs.forEach(Job::cancel)
            serviceJobs.clear()
            boundService = null
            mutableState.value = AppPresenceState.Offline
            mutableCallState.value = CallState.Offline
            mutableContacts.value = emptyList()
            mutableOnionAddress.value = null
            mutablePairingText.value = null
            mutableMetricsStats.value = null
            mutableMetricsCsvPath.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            App(
                controller = this,
                pairingQrAction = PairingQrAction(
                    label = "Scan QR code",
                    labelKo = "QR 코드 스캔",
                    launch = ::requestPairingScan,
                ),
            )
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, OnionTalkieService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        stateJob?.cancel()
        stateJob = null
        boundService = null
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onStop()
    }

    override fun startTor() {
        if (Build.VERSION.SDK_INT >= ANDROID_13 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AlertDialog.Builder(this)
                .setTitle(R.string.notification_rationale_title)
                .setMessage(R.string.notification_rationale_message)
                .setPositiveButton(R.string.continue_label) { _, _ ->
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton(R.string.cancel_label, null)
                .show()
        } else {
            ensureMicrophonePermission()
        }
    }

    override fun stopTor() {
        startService(Intent(this, OnionTalkieService::class.java).setAction(OnionTalkieService.ACTION_STOP_TOR))
    }

    override fun onDestroy() {
        stateJob?.cancel()
        serviceJobs.forEach(Job::cancel)
        activityScope.cancel()
        super.onDestroy()
    }

    override suspend fun importPairing(text: String): PairingResult? = boundService?.importPairing(text)
    override suspend fun removeContact(peerOnion: String): Boolean = boundService?.removeContact(peerOnion) ?: false
    override suspend fun dial(peerOnion: String): Boolean = boundService?.dial(peerOnion) ?: false
    override suspend fun acceptCall(): Boolean = boundService?.acceptCall() ?: false
    override suspend fun declineCall(): Boolean = boundService?.declineCall() ?: false
    override suspend fun hangUp(): Boolean = boundService?.hangUp() ?: false
    override fun shareMetrics() {
        val file = metricsCsvPath.value?.let(::File)
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.metrics_file_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.share_metrics_title)))
    }

    private fun startTorService() {
        val intent = Intent(this, OnionTalkieService::class.java).setAction(OnionTalkieService.ACTION_START_TOR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun ensureMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startTorService()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.microphone_permission_title)
            .setMessage(R.string.microphone_permission_message)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            .setNegativeButton(R.string.cancel_label, null)
            .show()
    }

    private fun requestPairingScan(onDecoded: (String) -> Unit) {
        pendingPairingScan = onDecoded
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchPairingScanner()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.camera_permission_title)
            .setMessage(R.string.camera_permission_message)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                pairingCameraPermission.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton(R.string.cancel_label) { _, _ -> pendingPairingScan = null }
            .show()
    }

    private fun launchPairingScanner() {
        pairingScanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_pairing_qr_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private companion object {
        const val ANDROID_13 = 33
    }
}
