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
    private var foreground = false

    val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): OnionTalkieService = this@OnionTalkieService
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TOR -> startTorInForeground()
            ACTION_STOP_TOR -> stopTor(startId)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            Build.VERSION.SDK_INT >= ANDROID_10 -> startForeground(NOTIFICATION_ID, notification, 0)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun refreshNotification() {
        if (!foreground) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(mutableState.value),
        )
    }

    private fun buildNotification(current: AppPresenceState): Notification {
        val message = when (current) {
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
        return Notification.Builder(this, ONLINE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    ),
                    getString(R.string.go_offline),
                    stop,
                ).build(),
            )
            .build()
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
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    override fun onDestroy() {
        operationJob?.cancel()
        torControl?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_TOR = "dev.junepark.oniontalkie.action.START_TOR"
        const val ACTION_STOP_TOR = "dev.junepark.oniontalkie.action.STOP_TOR"

        private const val ONLINE_CHANNEL_ID = "onion_dial_online"
        private const val NOTIFICATION_ID = 7301
        private const val REQUEST_OPEN_APP = 7302
        private const val REQUEST_STOP_TOR = 7303
        private const val CONTROL_COOKIE_FILE = "control_auth_cookie"
        private const val CONTROL_CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val BOOTSTRAP_COMPLETE = 100
        private const val ANDROID_10 = 29
        private const val ANDROID_14 = 34
    }
}
