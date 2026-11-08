package dev.junepark.oniontalkie

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the user initiated foreground-service lifecycle without capturing the UI. */
@RunWith(AndroidJUnit4::class)
class OnionTalkieServiceTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test(timeout = 45_000)
    fun goingOnlinePostsPersistentNotificationAndGoingOfflineRemovesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val notifications = context.getSystemService(NotificationManager::class.java)

        try {
            assertEquals(
                NotificationManager.IMPORTANCE_LOW,
                notifications.getNotificationChannel(ONLINE_CHANNEL_ID)?.importance,
            )
            assertEquals(
                NotificationManager.IMPORTANCE_DEFAULT,
                notifications.getNotificationChannel(IN_CALL_CHANNEL_ID)?.importance,
            )
            assertEquals(
                NotificationManager.IMPORTANCE_HIGH,
                notifications.getNotificationChannel(INCOMING_CHANNEL_ID)?.importance,
            )
            instrumentation.runOnMainSync { activity.startTor() }

            assertTrue(
                "Go online should post the persistent Tor notification",
                waitUntil(FOREGROUND_START_TIMEOUT_MILLIS) {
                    notifications.activeNotifications.any { statusNotification(it.notification) }
                },
            )
            val active = notifications.activeNotifications.first { statusNotification(it.notification) }.notification
            assertTrue("The online notification must remain ongoing", active.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)

            instrumentation.runOnMainSync { activity.stopTor() }
            assertTrue(
                "Go offline should remove the Tor notification",
                waitUntil(SERVICE_STOP_TIMEOUT_MILLIS) {
                    notifications.activeNotifications.none { statusNotification(it.notification) }
                },
            )
        } finally {
            instrumentation.runOnMainSync { activity.stopTor() }
            waitUntil(SERVICE_STOP_TIMEOUT_MILLIS) {
                notifications.activeNotifications.none { statusNotification(it.notification) }
            }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun statusNotification(notification: android.app.Notification): Boolean =
        notification.channelId == ONLINE_CHANNEL_ID || notification.channelId == IN_CALL_CHANNEL_ID

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return predicate()
    }

    private companion object {
        const val ONLINE_CHANNEL_ID = "onion_dial_online"
        const val IN_CALL_CHANNEL_ID = "onion_dial_in_call"
        const val INCOMING_CHANNEL_ID = "onion_dial_calls"
        const val FOREGROUND_START_TIMEOUT_MILLIS = 15_000L
        const val SERVICE_STOP_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
