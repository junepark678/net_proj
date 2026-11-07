package dev.junepark.oniontalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.intl.Locale

@Composable
fun App(controller: AppPresenceController) {
    val state by controller.state.collectAsState()
    val korean = Locale.current.language == "ko"
    val running = state !is AppPresenceState.Offline && state !is AppPresenceState.Failed
    val status = when (state) {
        AppPresenceState.Offline -> if (korean) "Tor 오프라인" else "Tor is offline"
        AppPresenceState.Starting -> if (korean) "Tor 시작 중…" else "Starting Tor…"
        is AppPresenceState.Bootstrapping -> {
            val progress = (state as AppPresenceState.Bootstrapping).progress
            if (korean) "Tor 네트워크 연결 중 · $progress%" else "Connecting to Tor · $progress%"
        }
        AppPresenceState.TorReady -> if (korean) "Tor 연결됨" else "Tor connected"
        AppPresenceState.Failed -> if (korean) "Tor를 시작하지 못했습니다" else "Tor could not start"
    }
    val buttonLabel = if (running) {
        if (korean) "Tor 중지" else "Stop Tor"
    } else {
        if (korean) "Tor 시작" else "Start Tor"
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF11131A)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = "Onion Dial",
                style = TextStyle(color = Color(0xFFF4F2FA), fontSize = 36.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = status,
                style = TextStyle(color = Color(0xFFC8C5D1), fontSize = 18.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(28.dp))
            Box(
                modifier = Modifier
                    .background(Color(0xFF8B7CFF), RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) {
                        if (running) controller.stopTor() else controller.startTor()
                    }
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = buttonLabel,
                    style = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.height(24.dp))
            BasicText(
                text = if (korean) {
                    "Tor 연결은 아직 통화가 가능하다는 뜻은 아닙니다."
                } else {
                    "A Tor connection alone does not mean calls are ready yet."
                },
                style = TextStyle(color = Color(0xFF9996A4), fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        }
    }
}
