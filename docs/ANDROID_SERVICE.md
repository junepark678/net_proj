# Android Tor service

`OnionTalkieService` owns the Tor process after the user taps **Start Tor**. It starts in the foreground, exposes bootstrap progress to the UI, and shows an ongoing notification with a stop action. `POST_NOTIFICATIONS` is explained and requested before starting on Android 13 and newer; denying it leaves Tor offline.

The idle service uses Android's `specialUse` foreground-service type with the manifest subtype `onion_dial_tor_presence`. The persistent Tor listener needs to stay available for incoming calls, so `dataSync` would be the wrong type: Android 15 limits that type to six hours per app in a rolling 24-hour period for apps targeting API 35. See [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) and [special-use service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

When call lifecycle integration is added, the running service must add the `microphone` type only while a call is active and after `RECORD_AUDIO` is granted. Android checks that while-in-use permission when the microphone service starts, so the transition must follow an explicit user action while the app is visible. The idle Tor process must not request microphone access.

This service does not autostart at boot. It starts only through the visible app action or an action on its own notification.
