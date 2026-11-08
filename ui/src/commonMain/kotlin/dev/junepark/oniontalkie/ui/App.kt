package dev.junepark.oniontalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import dev.junepark.oniontalkie.call.CallState
import dev.junepark.oniontalkie.identity.PairingResult
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

private enum class AppPage { Home, Pair, Call, History }

@Composable
fun App(
    controller: OnionDialViewModel,
    pairingQrAction: PairingQrAction? = null,
    batteryOptimizationAction: BatteryOptimizationAction? = null,
) {
    val presence by controller.state.collectAsState()
    val call by controller.callState.collectAsState()
    val contacts by controller.contacts.collectAsState()
    val onion by controller.onionAddress.collectAsState()
    val pairing by controller.pairingText.collectAsState()
    val metrics by controller.metricsStats.collectAsState()
    val metricsFile by controller.metricsCsvPath.collectAsState()
    val callHistory by controller.callHistory.collectAsState()
    val muted by controller.muted.collectAsState()
    val speakerphone by controller.speakerphoneSelected.collectAsState()
    val selectedAudioInput by controller.selectedAudioInput.collectAsState()
    val selectedAudioOutput by controller.selectedAudioOutput.collectAsState()
    val korean = Locale.current.language == "ko"
    val darkTheme = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pairingInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }
    var activePage by remember { mutableStateOf(AppPage.Home) }
    val metricHistory = remember { mutableStateListOf<dev.junepark.oniontalkie.metrics.CallStats>() }

    LaunchedEffect(metrics) {
        metrics?.let { sample ->
            val previous = metricHistory.lastOrNull()
            if (previous != null && sample.elapsedSeconds < previous.elapsedSeconds) metricHistory.clear()
            if (metricHistory.lastOrNull()?.elapsedSeconds != sample.elapsedSeconds) {
                metricHistory += sample
                if (metricHistory.size > 30) metricHistory.removeAt(0)
            }
        }
    }
    LaunchedEffect(controller) { controller.refreshCallHistory() }
    LaunchedEffect(call) {
        when (call) {
            is CallState.Dialing, is CallState.Ringing, is CallState.InCall, is CallState.PeerOffline ->
                activePage = AppPage.Call
            else -> Unit
        }
    }

    val starting = presence is AppPresenceState.Starting || presence is AppPresenceState.Bootstrapping
    val running = presence == AppPresenceState.TorReady || starting
    val status = when (presence) {
        AppPresenceState.Offline -> if (korean) "오프라인" else "Offline"
        AppPresenceState.Starting -> if (korean) "Tor 시작 중…" else "Starting Tor…"
        is AppPresenceState.Bootstrapping -> {
            val progress = (presence as AppPresenceState.Bootstrapping).progress
            if (korean) "Tor 연결 중 · $progress%" else "Connecting to Tor · $progress%"
        }
        AppPresenceState.TorReady -> if (korean) "온라인 · Tor 연결됨" else "Online · Tor connected"
        AppPresenceState.Failed -> if (korean) "연결 실패" else "Connection failed"
    }

    MaterialTheme(
        colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFA99BFF),
                onPrimary = Color(0xFF17151F),
                background = Color(0xFF11131A),
                onBackground = Color(0xFFF4F2FA),
                surface = Color(0xFF191C25),
                onSurface = Color(0xFFF4F2FA),
                surfaceVariant = Color(0xFF20232D),
                onSurfaceVariant = Color(0xFFC8C5D1),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF5144B8),
                onPrimary = Color.White,
                background = Color(0xFFF6F5FA),
                onBackground = Color(0xFF1B1A20),
                surface = Color.White,
                onSurface = Color(0xFF1B1A20),
                surfaceVariant = Color(0xFFECEAF2),
                onSurfaceVariant = Color(0xFF514F59),
            )
        },
    ) {
      Box(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(20.dp),
        contentAlignment = Alignment.TopCenter,
      ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                BasicText("Onion Dial", style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 34.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(3.dp))
                BasicText(status, style = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp))
                Spacer(Modifier.height(4.dp))
                BasicText(
                    if (korean) "통화하려면 두 사람 모두 온라인이어야 합니다. 푸시 알림은 사용하지 않습니다."
                    else "Both people must be online to connect. Onion Dial does not use push notifications.",
                    style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallAction(if (korean) "홈" else "Home", activePage != AppPage.Home) { activePage = AppPage.Home }
                SmallAction(if (korean) "페어링" else "Pair", activePage != AppPage.Pair) { activePage = AppPage.Pair }
                SmallAction(if (korean) "통화" else "Call", activePage != AppPage.Call) { activePage = AppPage.Call }
                SmallAction(if (korean) "기록" else "History", activePage != AppPage.History) { activePage = AppPage.History }
            }

            if (activePage == AppPage.Home) {
                ActionButton(
                    label = when {
                        starting -> if (korean) "Tor 연결 중" else "Connecting…"
                        running -> if (korean) "오프라인으로 전환" else "Go offline"
                        else -> if (korean) "온라인으로 전환" else "Go online"
                    },
                    enabled = !starting,
                    onClick = { if (running) controller.stopTor() else controller.startTor() },
                )
            }

            if (activePage == AppPage.Home && batteryOptimizationAction != null) {
                Panel(title = if (korean) "백그라운드 연결" else "Background availability") {
                    BasicText(
                        if (korean) {
                            "화면이 꺼져 있을 때 통화 대기를 돕도록 배터리 최적화 제외를 선택할 수 있습니다. 설정은 선택 사항입니다."
                        } else {
                            "You can optionally exempt Onion Dial from battery optimization to help it stay reachable while the screen is off."
                        },
                        style = bodyStyle(),
                    )
                    Spacer(Modifier.height(6.dp))
                    ActionButton(
                        label = when {
                            batteryOptimizationAction.isExempt -> if (korean) "배터리 최적화 제외됨" else "Battery optimization exempt"
                            else -> if (korean) "배터리 설정 열기" else "Review battery settings"
                        },
                        enabled = !batteryOptimizationAction.isExempt,
                        onClick = batteryOptimizationAction.launch,
                    )
                }
            }

            if (activePage == AppPage.Pair && onion != null && pairing != null) {
                Panel(title = if (korean) "내 주소" else "Your address") {
                    BasicText(onion.orEmpty(), style = bodyStyle())
                    Spacer(Modifier.height(8.dp))
                    PairingQrCode(
                        text = pairing.orEmpty(),
                        description = if (korean) "페어링 QR 코드" else "Pairing QR code",
                    )
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        text = pairing.orEmpty(),
                        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        label = if (korean) "페어링 코드 복사" else "Copy pairing code",
                        onClick = {
                            clipboard.setText(AnnotatedString(pairing.orEmpty()))
                            feedback = if (korean) "페어링 코드를 복사했습니다." else "Pairing code copied."
                        },
                    )
                }
            }

            if (activePage == AppPage.Pair) Panel(title = if (korean) "연락처 추가" else "Add a contact") {
                BasicText(
                    if (korean) "상대방의 Onion Dial 페어링 코드를 붙여 넣으세요." else "Paste the other person's Onion Dial pairing code.",
                    style = bodyStyle(),
                )
                if (pairingQrAction != null) {
                    Spacer(Modifier.height(8.dp))
                    ActionButton(
                        label = if (korean) pairingQrAction.labelKo else pairingQrAction.label,
                        onClick = {
                            pairingQrAction.launch { decoded ->
                                pairingInput = decoded
                                feedback = if (korean) "QR 코드에서 페어링 코드를 읽었습니다." else "Read pairing code from QR."
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                SmallAction(if (korean) "클립보드에서 붙여넣기" else "Paste from clipboard", enabled = true) {
                    pairingInput = clipboard.getText()?.text.orEmpty()
                }
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = pairingInput,
                    onValueChange = { pairingInput = it },
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                    decorationBox = { inner ->
                        if (pairingInput.isEmpty()) {
                            BasicText(
                                if (korean) "onion-talkie:v1;…" else "onion-talkie:v1;…",
                                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp),
                            )
                        }
                        inner()
                    },
                )
                Spacer(Modifier.height(8.dp))
                ActionButton(
                    label = if (korean) "연락처 저장" else "Save contact",
                    enabled = pairingInput.isNotBlank(),
                    onClick = {
                        scope.launch {
                            try {
                                when (val result = controller.importPairing(pairingInput.trim())) {
                                    is PairingResult.Valid -> {
                                        feedback = if (korean) "${result.payload.displayName} 연락처를 저장했습니다." else "Saved ${result.payload.displayName}."
                                        pairingInput = ""
                                    }
                                    is PairingResult.Invalid -> feedback = if (korean) {
                                        "페어링 코드가 올바르지 않습니다: ${result.reason}"
                                    } else "Invalid pairing code: ${result.reason}"
                                    null -> feedback = if (korean) "먼저 온라인으로 전환하세요." else "Go online first."
                                }
                            } catch (_: Exception) {
                                feedback = if (korean) "연락처를 저장하지 못했습니다." else "Could not save this contact."
                            }
                        }
                    },
                )
            }

            if (activePage == AppPage.Home && contacts.isEmpty()) {
                Panel(title = if (korean) "연락처" else "Contacts") {
                    BasicText(
                        if (korean) "QR 코드나 페어링 코드로 연락처를 추가하세요." else "Add a contact with a QR code or pairing text.",
                        style = bodyStyle(),
                    )
                    SmallAction(if (korean) "페어링 열기" else "Open pairing", enabled = true) { activePage = AppPage.Pair }
                }
            }
            if (activePage == AppPage.Home && contacts.isNotEmpty()) {
                Panel(title = if (korean) "연락처" else "Contacts") {
                    contacts.forEachIndexed { index, contact ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(contact.displayName, style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                                BasicText(contact.onion, style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp))
                            }
                            SmallAction(
                                if (korean) "통화" else "Call",
                                enabled = presence == AppPresenceState.TorReady && call !is CallState.Dialing &&
                                    call !is CallState.Ringing && call !is CallState.InCall,
                            ) {
                                scope.launch {
                                    feedback = try {
                                        if (controller.dial(contact.onion)) "" else if (korean) "통화를 시작할 수 없습니다." else "Call could not start."
                                    } catch (_: Exception) {
                                        if (korean) "상대방에게 연결할 수 없습니다." else "Could not reach this contact."
                                    }
                                }
                            }
                            SmallAction(if (korean) "삭제" else "Remove", enabled = call !is CallState.InCall) {
                                scope.launch { controller.removeContact(contact.onion) }
                            }
                        }
                    }
                }
            }

            if (activePage == AppPage.Call && (controller.audioInputs.isNotEmpty() || controller.audioOutputs.isNotEmpty())) {
                Panel(title = if (korean) "오디오 장치" else "Audio devices") {
                    BasicText(
                        if (korean) "변경 사항은 다음 통화부터 적용됩니다." else "Changes apply to the next call.",
                        style = bodyStyle(),
                    )
                    if (controller.audioInputs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(if (korean) "마이크" else "Microphone", style = bodyStyle())
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SmallAction(if (korean) "시스템 기본값" else "System default", selectedAudioInput == null) {
                                controller.selectAudioInput(null)
                            }
                            controller.audioInputs.forEach { device ->
                                SmallAction(device.label, selectedAudioInput != device.id) {
                                    controller.selectAudioInput(device.id)
                                }
                            }
                        }
                    }
                    if (controller.audioOutputs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(if (korean) "스피커/헤드폰" else "Speaker/headphones", style = bodyStyle())
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SmallAction(if (korean) "시스템 기본값" else "System default", selectedAudioOutput == null) {
                                controller.selectAudioOutput(null)
                            }
                            controller.audioOutputs.forEach { device ->
                                SmallAction(device.label, selectedAudioOutput != device.id) {
                                    controller.selectAudioOutput(device.id)
                                }
                            }
                        }
                    }
                }
            }

            if (activePage == AppPage.Call) CallPanel(
                state = call,
                stats = metrics,
                muted = muted,
                speakerphone = speakerphone,
                controller = controller,
                scope = scope,
                korean = korean,
                setFeedback = { feedback = it },
            )
            if (activePage == AppPage.Call && metrics != null) {
                Panel(title = if (korean) "통화 측정" else "Call metrics") {
                    val current = metrics
                    BasicText(
                        (if (korean) "왕복 " else "RTT ") + (current?.rttMs?.toLong()?.let { "$it ms" } ?: "—") +
                            " · " + (if (korean) "지터 " else "jitter ") + "${current?.jitterMs?.toLong() ?: 0} ms" +
                            " · " + (if (korean) "손실 " else "loss ") + "${current?.intervalLossPct?.toLong() ?: 0}%",
                        style = bodyStyle(),
                    )
                    BasicText(
                        "TX ${current?.txKbps?.toLong() ?: 0} kbps · RX ${current?.rxKbps?.toLong() ?: 0} kbps · " +
                            "buffer ${current?.bufferMs?.toLong() ?: 0} ms",
                        style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp),
                    )
                    MetricsSparkline(metricHistory.toList(), korean)
                    if (metricsFile != null) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(metricsFile.orEmpty(), style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp))
                        SmallAction(if (korean) "CSV 경로 복사" else "Copy CSV path", enabled = true) {
                            clipboard.setText(AnnotatedString(metricsFile.orEmpty()))
                            feedback = if (korean) "측정 CSV 경로를 복사했습니다." else "Metrics CSV path copied."
                        }
                        SmallAction(if (korean) "CSV 공유" else "Share CSV", enabled = true) {
                            controller.shareMetrics()
                        }
                    }
                }
            }
            if (activePage == AppPage.History) Panel(title = if (korean) "통화 기록" else "Call history") {
                SmallAction(if (korean) "기록 새로고침" else "Refresh history", enabled = true) {
                    scope.launch { controller.refreshCallHistory() }
                }
                if (callHistory.isEmpty()) {
                    BasicText(
                        if (korean) "완료된 통화가 없습니다." else "No completed calls yet.",
                        style = bodyStyle(),
                    )
                } else {
                    callHistory.take(20).forEachIndexed { index, entry ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        BasicText(
                            "${entry.peerLabel} · ${formatCallDuration(entry.durationSeconds)} · ${entry.timestampUtc}",
                            style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        )
                        BasicText(
                            (if (korean) "RTT 중앙값 " else "RTT p50 ") + (entry.rttP50Ms?.toLong()?.let { "$it ms" } ?: "—") +
                                " · " + (if (korean) "지터 " else "jitter ") + (entry.jitterP50Ms?.toLong()?.let { "$it ms" } ?: "—") +
                                " · " + (if (korean) "손실 " else "loss ") + (entry.lossP50Pct?.toLong()?.let { "$it%" } ?: "—") +
                                " · " + (if (korean) "버퍼 " else "buffer ") + (entry.bufferP50Ms?.toLong()?.let { "$it ms" } ?: "—"),
                            style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp),
                        )
                        SmallAction(if (korean) "CSV 내보내기" else "Export CSV", enabled = true) {
                            controller.shareMetrics(entry.csvPath)
                        }
                    }
                }
            }
            if (feedback.isNotBlank()) BasicText(feedback, style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp))
            Spacer(Modifier.height(8.dp))
        }
      }
    }
}

@Composable
private fun PairingQrCode(text: String, description: String) {
    val matrix = remember(text) { pairingQrMatrix(text) }
    Canvas(
        modifier = Modifier.size(220.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .semantics { contentDescription = description },
    ) {
        val moduleSize = size.width / matrix.size
        drawRect(Color.White)
        for (row in 0 until matrix.size) {
            for (column in 0 until matrix.size) {
                if (matrix.darkModules[row * matrix.size + column]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(column * moduleSize, row * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun CallPanel(
    state: CallState,
    stats: dev.junepark.oniontalkie.metrics.CallStats?,
    muted: Boolean,
    speakerphone: Boolean,
    controller: OnionDialViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    korean: Boolean,
    setFeedback: (String) -> Unit,
) {
    when (state) {
        CallState.Offline -> Unit
        is CallState.Online -> Panel(title = if (korean) "통화 상태" else "Call status") {
            BasicText(if (korean) "전화 대기 중" else "Ready for calls", style = bodyStyle())
        }
        is CallState.Dialing -> Panel(title = if (korean) "발신 중" else "Calling") {
            BasicText(state.peerOnion, style = bodyStyle())
            Spacer(Modifier.height(8.dp))
            SmallAction(if (korean) "취소" else "Cancel", enabled = true) { scope.launch { controller.hangUp() } }
        }
        is CallState.Ringing -> Panel(title = if (korean) "수신 전화" else "Incoming call") {
            BasicText(state.displayName ?: state.peerOnion, style = bodyStyle())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(if (korean) "받기" else "Answer", enabled = true) {
                    scope.launch { if (!controller.acceptCall()) setFeedback(if (korean) "통화를 받을 수 없습니다." else "Could not answer.") }
                }
                SmallAction(if (korean) "거절" else "Decline", enabled = true) { scope.launch { controller.declineCall() } }
            }
        }
        is CallState.InCall -> Panel(title = if (korean) "통화 중" else "In call") {
            BasicText(state.peerOnion, style = bodyStyle())
            BasicText(
                formatCallDuration(stats?.elapsedSeconds ?: 0.0),
                style = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(if (muted) if (korean) "음소거 해제" else "Unmute" else if (korean) "음소거" else "Mute", enabled = true) {
                    controller.setMuted(!muted)
                }
                if (controller.supportsSpeakerphone) {
                    SmallAction(
                        if (speakerphone) if (korean) "스피커 끄기" else "Speaker off"
                        else if (korean) "스피커 켜기" else "Speaker on",
                        enabled = true,
                    ) {
                        if (!controller.setSpeakerphoneEnabled(!speakerphone)) {
                            setFeedback(if (korean) "스피커 경로를 변경할 수 없습니다." else "Could not change speaker route.")
                        }
                    }
                }
                SmallAction(if (korean) "통화 종료" else "Hang up", enabled = true) {
                    scope.launch { controller.hangUp() }
                }
            }
        }
        is CallState.PeerOffline -> Panel(title = if (korean) "상대방 오프라인" else "Contact is offline") {
            BasicText(state.peerOnion, style = bodyStyle())
        }
    }
}

private fun formatCallDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val minutes = (total / 60).toString().padStart(2, '0')
    val remainder = (total % 60).toString().padStart(2, '0')
    return "$minutes:$remainder"
}

@Composable
private fun MetricsSparkline(samples: List<dev.junepark.oniontalkie.metrics.CallStats>, korean: Boolean) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val series = listOf(
        (if (korean) "왕복" else "RTT") to Color(0xFFE2A63B) to samples.map { it.rttMs },
        (if (korean) "지터" else "Jitter") to Color(0xFF9E8CFF) to samples.map { it.jitterMs },
        (if (korean) "손실" else "Loss") to Color(0xFFE36D79) to samples.map { it.intervalLossPct },
        (if (korean) "버퍼" else "Buffer") to Color(0xFF4FB9A4) to samples.map { it.bufferMs },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        series.forEach { (labelAndColor, _) ->
            BasicText(
                labelAndColor.first,
                style = TextStyle(color = labelAndColor.second, fontSize = 11.sp),
            )
        }
    }
    Canvas(
        modifier = Modifier.fillMaxWidth().height(72.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
    ) {
        val padding = 8.dp.toPx()
        val chartWidth = (size.width - 2 * padding).coerceAtLeast(1f)
        val chartHeight = (size.height - 2 * padding).coerceAtLeast(1f)
        for (line in 0..2) {
            val y = padding + chartHeight * line / 2
            drawLine(
                gridColor,
                Offset(padding, y),
                Offset(size.width - padding, y),
                1.dp.toPx(),
            )
        }
        fun drawSeries(values: List<Double?>, color: Color) {
            val finite = values.map { it?.takeIf(Double::isFinite) }
            val max = (finite.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(1.0)
            val path = Path()
            var started = false
            finite.forEachIndexed { index, value ->
                if (value == null) return@forEachIndexed
                val x = padding + if (finite.size <= 1) chartWidth else chartWidth * index / (finite.size - 1)
                val y = padding + chartHeight * (1.0 - value / max).toFloat()
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else path.lineTo(x, y)
            }
            if (started) drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        series.forEach { (labelAndColor, values) ->
            drawSeries(values, labelAndColor.second)
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun bodyStyle() = TextStyle(
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize = 13.sp,
    textAlign = TextAlign.Start,
)
