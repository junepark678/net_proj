# Android Tor and call service

`OnionTalkieService` owns Tor after the user chooses **Go online**. It exposes bootstrap progress and keeps an ongoing notification visible with an offline action. Android 13 and newer show an explanation before requesting `POST_NOTIFICATIONS`; denying it leaves Tor offline. Microphone access is explained and requested before going online so a call can be answered from its notification while the app is in the background. Audio capture starts only for an active call.

On Android 14 and newer, the service declares `specialUse` for persistent Tor presence and `microphone` for voice calls; the special-use subtype is `onion_dial_tor_presence`. It starts with both types so a notification-answered call can use the microphone without creating a new background service. `dataSync` is unsuitable for an always-available Tor listener because Android 15 limits that type to six hours per app in a rolling 24-hour period for apps targeting API 35. See [Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) and [special-use service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).

The idle notification uses a low-importance channel; an active call uses a default-importance channel and offers **Hang up**. Incoming calls use a high-importance notification with **Answer** and **Decline** actions. A partial wake lock is held only while `CallState.InCall` is active and is released on hang-up, offline, shutdown, or service destruction.

The Home screen offers an optional battery-optimization exemption. It explains the effect first and only opens the Android system request after the user taps the action; the user can decline. This may improve availability on devices with aggressive background restrictions, but it cannot guarantee delivery across every vendor's power-management policy.

The service does not autostart at boot. Tor starts only through the visible app's online action, and stops through the app or its persistent notification.

The service watches both Tor's authenticated control connection and process output. If either ends unexpectedly, it releases the call/Tor resources and reports **Failed**; the online action remains available to retry.
