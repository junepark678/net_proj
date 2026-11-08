package dev.junepark.oniontalkie.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Owns the communication audio mode and speaker route for one application process. */
class AndroidAudioRouteController(context: Context) : AudioRouteController {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callUsers = 0
    private var previousMode: Int? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var previousSpeakerphoneOn: Boolean? = null

    override val supportsSpeakerphone: Boolean = true

    /** Enters MODE_IN_COMMUNICATION on the first acquire; nested call owners are reference-counted. */
    @Synchronized
    override fun beginCommunication() {
        if (callUsers == 0) {
            previousMode = audioManager.mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice = audioManager.communicationDevice
            } else {
                previousSpeakerphoneOn = legacySpeakerphoneOn()
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        callUsers++
    }

    /** Restores the route and mode captured by the first [beginCommunication] call. */
    @Synchronized
    override fun endCommunication() {
        if (callUsers == 0) return
        callUsers--
        if (callUsers != 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previous = previousCommunicationDevice
            if (previous == null) audioManager.clearCommunicationDevice()
            else audioManager.setCommunicationDevice(previous)
            previousCommunicationDevice = null
        } else {
            previousSpeakerphoneOn?.let(::setLegacySpeakerphoneOn)
            previousSpeakerphoneOn = null
        }
        previousMode?.let { audioManager.mode = it }
        previousMode = null
    }

    /** Selects the built-in speaker or returns to the system's default communication device. */
    @Synchronized
    override fun setSpeakerphone(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!enabled) {
                audioManager.clearCommunicationDevice()
                return true
            }
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            } ?: return false
            return audioManager.setCommunicationDevice(speaker)
        }
        setLegacySpeakerphoneOn(enabled)
        return true
    }

    @Synchronized
    override fun isSpeakerphoneSelected(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    } else {
        legacySpeakerphoneOn()
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphoneOn(): Boolean = audioManager.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphoneOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
    }
}
