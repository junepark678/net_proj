package dev.junepark.oniontalkie

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.junepark.oniontalkie.audio.AndroidAudioRouteController
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.call.JvmOnionCallRuntime
import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.PairingResult
import dev.junepark.oniontalkie.metrics.CallStats
import dev.junepark.oniontalkie.metrics.CallHistoryEntry
import dev.junepark.oniontalkie.metrics.loadCallHistory
import dev.junepark.oniontalkie.tor.JvmControlTransport
import dev.junepark.oniontalkie.tor.TorConfig
import dev.junepark.oniontalkie.tor.TorControl
import dev.junepark.oniontalkie.tor.TorProcess
import dev.junepark.oniontalkie.tor.forAndroid
import dev.junepark.oniontalkie.ui.AppPresenceState
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns Tor while the user has explicitly chosen to be available for Onion Dial calls. */
class OnionTalkieService : Service() {
    private val binder = LocalBinder()
    private val mutableState = MutableStateFlow<AppPresenceState>(AppPresenceState.Offline)
    private lateinit var serviceScope: CoroutineScope
    private var operationJob: Job? = null
    private var torProcess: TorProcess? = null
    private var torControl: TorControl? = null
    private var callRuntime: JvmOnionCallRuntime? = null
    private var callWakeLock: PowerManager.WakeLock? = null
    private val runtimeJobs = mutableListOf<Job>()
    private var foreground = false

    val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()
    private val mutableCallState = MutableStateFlow<CallState>(CallState.Offline)
    private val mutableContacts = MutableStateFlow<List<Contact>>(emptyList())
    private val mutableOnionAddress = MutableStateFlow<String?>(null)
    private val mutablePairingText = MutableStateFlow<String?>(null)
    private val mutableMetricsStats = MutableStateFlow<CallStats?>(null)
    private val mutableMetricsCsvPath = MutableStateFlow<String?>(null)
    private val mutableCallHistory = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
    private val mutableMuted = MutableStateFlow(false)
    private val mutableSpeakerphoneSelected = MutableStateFlow(false)
    val callState: StateFlow<CallState> = mutableCallState.asStateFlow()
    val contacts: StateFlow<List<Contact>> = mutableContacts.asStateFlow()
    val onionAddress: StateFlow<String?> = mutableOnionAddress.asStateFlow()
    val pairingText: StateFlow<String?> = mutablePairingText.asStateFlow()
    val metricsStats: StateFlow<CallStats?> = mutableMetricsStats.asStateFlow()
    val metricsCsvPath: StateFlow<String?> = mutableMetricsCsvPath.asStateFlow()
    val callHistory: StateFlow<List<CallHistoryEntry>> = mutableCallHistory.asStateFlow()
    val muted: StateFlow<Boolean> = mutableMuted.asStateFlow()
    val speakerphoneSelected: StateFlow<Boolean> = mutableSpeakerphoneSelected.asStateFlow()
    val supportsSpeakerphone: Boolean get() = callRuntime?.supportsSpeakerphone ?: true

    inner class LocalBinder : Binder() {
        fun service(): OnionTalkieService = this@OnionTalkieService
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        createNotificationChannel()
        serviceScope.launch {
            mutableCallHistory.value = withContext(Dispatchers.IO) {
                loadCallHistory(Paths.get(filesDir.absolutePath, "tor").toString())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TOR -> startTorInForeground()
            ACTION_STOP_TOR -> stopTor(startId)
            ACTION_ACCEPT_CALL -> serviceScope.launch { callRuntime?.accept() }
            ACTION_DECLINE_CALL -> serviceScope.launch { callRuntime?.decline() }
            ACTION_HANG_UP -> serviceScope.launch { callRuntime?.hangUp() }
            else -> stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTorInForeground() {
        if (operationJob?.isActive == true) return
        mutableState.value = AppPresenceState.Starting
        promoteToForeground(buildNotification(mutableState.value))
        operationJob = serviceScope.launch { startAndObserveTor() }
    }

    private suspend fun startAndObserveTor() {
        try {
            val config = TorConfig.forAndroid(applicationContext)
            val process = TorProcess(config)
            torProcess = process
            process.start()

            val transport = withTimeout(CONTROL_CONNECT_TIMEOUT_MILLIS) {
                JvmControlTransport.connect(port = process.controlPort)
            }
            val control = TorControl(transport, serviceScope)
            torControl = control
            val cookiePath = Paths.get(config.dataDir, CONTROL_COOKIE_FILE)
            val cookie = withContext(Dispatchers.IO) { Files.readAllBytes(cookiePath) }
            control.authenticate(cookie)
            control.bootstrap.collect { progress ->
                if (progress >= BOOTSTRAP_COMPLETE && callRuntime == null) {
                    val runtime = JvmOnionCallRuntime.open(
                        control = control,
                        socksPort = process.socksPort,
                        dataDirectory = config.dataDir,
                        displayName = getString(R.string.default_display_name),
                        scope = serviceScope,
                        audioRouteController = AndroidAudioRouteController(applicationContext),
                    )
                    callRuntime = runtime
                    runtimeJobs += serviceScope.launch {
                        runtime.callState.collect { current ->
                            mutableCallState.value = current
                            updateCallWakeLock(current is CallState.InCall)
                            updateIncomingCallNotification(current)
                            updateForegroundType(current is CallState.InCall)
                            refreshNotification()
                        }
                    }
                    runtimeJobs += serviceScope.launch { runtime.contacts.collect { mutableContacts.value = it } }
                    runtimeJobs += serviceScope.launch { runtime.onionAddress.collect { mutableOnionAddress.value = it } }
                    runtimeJobs += serviceScope.launch { runtime.pairingText.collect { mutablePairingText.value = it } }
                    runtimeJobs += serviceScope.launch { runtime.metricsStats.collect { mutableMetricsStats.value = it } }
                    runtimeJobs += serviceScope.launch { runtime.metricsCsvPath.collect { mutableMetricsCsvPath.value = it } }
                    runtimeJobs += serviceScope.launch { runtime.muted.collect { mutableMuted.value = it } }
                    runtimeJobs += serviceScope.launch {
                        runtime.speakerphoneSelected.collect { mutableSpeakerphoneSelected.value = it }
                    }
                    runtimeJobs += serviceScope.launch {
                        runtime.callHistory.collect { mutableCallHistory.value = it }
                    }
                    runtime.goOnline()
                }
                mutableState.value = if (progress >= BOOTSTRAP_COMPLETE) AppPresenceState.TorReady
                else AppPresenceState.Bootstrapping(progress)
                refreshNotification()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = AppPresenceState.Failed
            refreshNotification()
            releaseTor()
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            stopSelf()
        }
    }

    private fun stopTor(startId: Int) {
        val starting = operationJob
        serviceScope.launch {
            starting?.cancelAndJoin()
            withContext(NonCancellable) { releaseTor() }
            operationJob = null
            mutableState.value = AppPresenceState.Offline
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            stopSelfResult(startId)
        }
    }

    private suspend fun releaseTor() {
        try {
            callRuntime?.shutdown()
        } catch (_: Exception) {
            // Tor and the service must still be released if a call resource fails to close.
        }
        callRuntime = null
        runtimeJobs.forEach(Job::cancel)
        runtimeJobs.clear()
        mutableCallState.value = CallState.Offline
        updateCallWakeLock(false)
        mutableContacts.value = emptyList()
        mutableOnionAddress.value = null
        mutablePairingText.value = null
        mutableMetricsStats.value = null
        mutableMetricsCsvPath.value = null
        mutableMuted.value = false
        mutableSpeakerphoneSelected.value = false
        updateIncomingCallNotification(CallState.Offline)
        torControl?.close()
        torControl = null
        val process = torProcess ?: return
        try {
            process.stop()
            torProcess = null
        } catch (_: Exception) {
            // TorProcess already tried graceful and forced termination within its configured bound.
        }
    }

    private fun promoteToForeground(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= ANDROID_14 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            Build.VERSION.SDK_INT >= ANDROID_10 -> startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun updateForegroundType(@Suppress("UNUSED_PARAMETER") inCall: Boolean) {
        if (!foreground) return
        promoteToForeground(buildNotification(mutableState.value))
    }

    private fun updateCallWakeLock(inCall: Boolean) {
        val powerManager = getSystemService(PowerManager::class.java)
        if (inCall) {
            val lock = callWakeLock ?: powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:OnionDialCall",
            ).apply { setReferenceCounted(false) }.also { callWakeLock = it }
            if (!lock.isHeld) lock.acquire()
        } else {
            callWakeLock?.let { lock ->
                if (lock.isHeld) lock.release()
            }
        }
    }

    suspend fun importPairing(text: String): PairingResult? = callRuntime?.importPairing(text)
    suspend fun removeContact(peerOnion: String): Boolean = callRuntime?.removeContact(peerOnion) ?: false
    suspend fun dial(peerOnion: String): Boolean = callRuntime?.dial(peerOnion) ?: false
    suspend fun acceptCall(): Boolean = callRuntime?.accept() ?: false
    suspend fun declineCall(): Boolean = callRuntime?.decline() ?: false
    suspend fun hangUp(): Boolean = callRuntime?.hangUp() ?: false
    fun setMuted(muted: Boolean) {
        callRuntime?.setMuted(muted)
    }
    fun setSpeakerphoneEnabled(enabled: Boolean): Boolean = callRuntime?.setSpeakerphone(enabled) ?: false
    suspend fun refreshCallHistory() {
        val runtime = callRuntime
        if (runtime != null) {
            runtime.refreshCallHistory()
            mutableCallHistory.value = runtime.callHistory.value
        } else {
            mutableCallHistory.value = withContext(Dispatchers.IO) {
                loadCallHistory(Paths.get(filesDir.absolutePath, "tor").toString())
            }
        }
    }

    private fun refreshNotification() {
        if (!foreground) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(mutableState.value),
        )
    }

    private fun buildNotification(current: AppPresenceState): Notification {
        val activeCall = mutableCallState.value as? CallState.InCall
        val message = if (activeCall != null) {
            val name = mutableContacts.value.firstOrNull { it.onion == activeCall.peerOnion }?.displayName
                ?: activeCall.peerOnion
            getString(R.string.call_in_progress, name)
        } else when (current) {
            AppPresenceState.Offline -> getString(R.string.tor_offline)
            AppPresenceState.Starting -> getString(R.string.tor_starting)
            is AppPresenceState.Bootstrapping -> getString(R.string.tor_bootstrapping, current.progress)
            AppPresenceState.TorReady -> getString(R.string.tor_connected)
            AppPresenceState.Failed -> getString(R.string.tor_failed)
        }
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP_TOR,
            Intent(this, OnionTalkieService::class.java).setAction(ACTION_STOP_TOR),
            pendingIntentFlags(),
        )
        val hangUp = PendingIntent.getService(
            this,
            REQUEST_HANG_UP,
            Intent(this, OnionTalkieService::class.java).setAction(ACTION_HANG_UP),
            pendingIntentFlags(),
        )
        val channel = if (activeCall != null) IN_CALL_CHANNEL_ID else ONLINE_CHANNEL_ID
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (activeCall != null) {
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.hang_up),
                    hangUp,
                ).build(),
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    ),
                    getString(R.string.go_offline),
                    stop,
                ).build(),
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ONLINE_CHANNEL_ID,
            getString(R.string.online_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.online_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                IN_CALL_CHANNEL_ID,
                getString(R.string.in_call_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.in_call_channel_description)
                setShowBadge(false)
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                INCOMING_CHANNEL_ID,
                getString(R.string.incoming_call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setShowBadge(false) },
        )
    }

    private fun updateIncomingCallNotification(current: CallState) {
        val manager = getSystemService(NotificationManager::class.java)
        val ringing = current as? CallState.Ringing
        if (ringing == null) {
            manager.cancel(INCOMING_NOTIFICATION_ID)
            return
        }
        val answer = PendingIntent.getService(
            this,
            REQUEST_ACCEPT_CALL,
            Intent(this, OnionTalkieService::class.java).setAction(ACTION_ACCEPT_CALL),
            pendingIntentFlags(),
        )
        val decline = PendingIntent.getService(
            this,
            REQUEST_DECLINE_CALL,
            Intent(this, OnionTalkieService::class.java).setAction(ACTION_DECLINE_CALL),
            pendingIntentFlags(),
        )
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )
        val caller = ringing.displayName ?: ringing.peerOnion
        manager.notify(
            INCOMING_NOTIFICATION_ID,
            Notification.Builder(this, INCOMING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(getString(R.string.incoming_call_title))
                .setContentText(getString(R.string.incoming_call_from, caller))
                .setContentIntent(openApp)
                .setCategory(Notification.CATEGORY_CALL)
                .setAutoCancel(false)
                .setTimeoutAfter(INCOMING_NOTIFICATION_TIMEOUT_MILLIS)
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.sym_action_call),
                        getString(R.string.answer_call),
                        answer,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.decline_call),
                        decline,
                    ).build(),
                )
                .build(),
        )
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        updateCallWakeLock(false)
        operationJob?.cancel()
        torControl?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_TOR = "dev.junepark.oniontalkie.action.START_TOR"
        const val ACTION_STOP_TOR = "dev.junepark.oniontalkie.action.STOP_TOR"
        const val ACTION_ACCEPT_CALL = "dev.junepark.oniontalkie.action.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "dev.junepark.oniontalkie.action.DECLINE_CALL"
        const val ACTION_HANG_UP = "dev.junepark.oniontalkie.action.HANG_UP"

        private const val ONLINE_CHANNEL_ID = "onion_dial_online"
        private const val NOTIFICATION_ID = 7301
        private const val REQUEST_OPEN_APP = 7302
        private const val REQUEST_STOP_TOR = 7303
        private const val INCOMING_NOTIFICATION_ID = 7304
        private const val REQUEST_ACCEPT_CALL = 7305
        private const val REQUEST_DECLINE_CALL = 7306
        private const val REQUEST_HANG_UP = 7307
        private const val IN_CALL_CHANNEL_ID = "onion_dial_in_call"
        private const val INCOMING_CHANNEL_ID = "onion_dial_calls"
        private const val INCOMING_NOTIFICATION_TIMEOUT_MILLIS = 30_000L
        private const val CONTROL_COOKIE_FILE = "control_auth_cookie"
        private const val CONTROL_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val BOOTSTRAP_COMPLETE = 100
        private const val ANDROID_10 = 29
        private const val ANDROID_14 = 34
    }
}
