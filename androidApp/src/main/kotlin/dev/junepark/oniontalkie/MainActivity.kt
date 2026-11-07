package dev.junepark.oniontalkie

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.junepark.oniontalkie.ui.App
import dev.junepark.oniontalkie.ui.AppPresenceController
import dev.junepark.oniontalkie.ui.AppPresenceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), AppPresenceController {
    private val mutableState = MutableStateFlow<AppPresenceState>(AppPresenceState.Offline)
    override val state: StateFlow<AppPresenceState> = mutableState.asStateFlow()

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var boundService: OnionTalkieService? = null
    private var stateJob: Job? = null
    private var bound = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startTorService() else Toast.makeText(
            this,
            R.string.notification_permission_denied,
            Toast.LENGTH_LONG,
        ).show()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? OnionTalkieService.LocalBinder ?: return
            boundService = local.service()
            stateJob?.cancel()
            stateJob = activityScope.launch {
                boundService?.state?.collect { mutableState.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateJob?.cancel()
            stateJob = null
            boundService = null
            mutableState.value = AppPresenceState.Offline
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(controller = this) }
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
            startTorService()
        }
    }

    override fun stopTor() {
        startService(Intent(this, OnionTalkieService::class.java).setAction(OnionTalkieService.ACTION_STOP_TOR))
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun startTorService() {
        val intent = Intent(this, OnionTalkieService::class.java).setAction(OnionTalkieService.ACTION_START_TOR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private companion object {
        const val ANDROID_13 = 33
    }
}
