package dev.junepark.oniontalkie.ui

import kotlinx.coroutines.flow.StateFlow

/** Observable Tor connectivity for the shared home screen. */
sealed interface AppPresenceState {
    data object Offline : AppPresenceState
    data object Starting : AppPresenceState
    data class Bootstrapping(val progress: Int) : AppPresenceState
    data object TorReady : AppPresenceState
    data object Failed : AppPresenceState
}

/** Platform owner for the Tor lifecycle. Android implements this with a foreground service. */
interface AppPresenceController {
    val state: StateFlow<AppPresenceState>
    fun startTor()
    fun stopTor()
}
