package dev.junepark.oniontalkie

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Covers AOSP-compatible manual pairing entry without opening or capturing a camera preview. */
@RunWith(AndroidJUnit4::class)
class PairingUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pairingPageOffersCameraAndUsableManualEntryFallback() {
        compose.onNodeWithText("Pair").performClick()

        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
        compose.onNodeWithText("Paste the other person's Onion Dial pairing code.").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("not-a-pairing-code")
        compose.onNodeWithText("Save contact").assertIsDisplayed().assertIsEnabled()
    }
}
