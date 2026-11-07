package dev.junepark.oniontalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
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

@Composable
fun App(controller: OnionDialController, pairingQrAction: PairingQrAction? = null) {
    val presence by controller.state.collectAsState()
    val call by controller.callState.collectAsState()
    val contacts by controller.contacts.collectAsState()
    val onion by controller.onionAddress.collectAsState()
    val pairing by controller.pairingText.collectAsState()
    val metrics by controller.metricsStats.collectAsState()
    val metricsFile by controller.metricsCsvPath.collectAsState()
    val korean = Locale.current.language == "ko"
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var pairingInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }

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

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF11131A)).padding(20.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                BasicText("Onion Dial", style = TextStyle(color = Color(0xFFF4F2FA), fontSize = 34.sp, fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(3.dp))
                BasicText(status, style = TextStyle(color = Color(0xFFC8C5D1), fontSize = 15.sp))
            }

            ActionButton(
                label = when {
                    starting -> if (korean) "Tor 연결 중" else "Connecting…"
                    running -> if (korean) "오프라인으로 전환" else "Go offline"
                    else -> if (korean) "온라인으로 전환" else "Go online"
                },
                enabled = !starting,
                onClick = { if (running) controller.stopTor() else controller.startTor() },
            )

            if (onion != null && pairing != null) {
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
                        style = TextStyle(color = Color(0xFFAAA7B6), fontSize = 12.sp),
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

            Panel(title = if (korean) "연락처 추가" else "Add a contact") {
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
                        .background(Color(0xFF20232D), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    decorationBox = { inner ->
                        if (pairingInput.isEmpty()) {
                            BasicText(
                                if (korean) "onion-talkie:v1;…" else "onion-talkie:v1;…",
                                style = TextStyle(color = Color(0xFF777481), fontSize = 13.sp),
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

            if (contacts.isNotEmpty()) {
                Panel(title = if (korean) "연락처" else "Contacts") {
                    contacts.forEachIndexed { index, contact ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                BasicText(contact.displayName, style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium))
                                BasicText(contact.onion, style = TextStyle(color = Color(0xFFAAA7B6), fontSize = 11.sp))
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

            CallPanel(call, controller, scope, korean) { feedback = it }
            if (metrics != null) {
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
                        style = TextStyle(color = Color(0xFFAAA7B6), fontSize = 12.sp),
                    )
                    if (metricsFile != null) {
                        Spacer(Modifier.height(6.dp))
                        BasicText(metricsFile.orEmpty(), style = TextStyle(color = Color(0xFF777481), fontSize = 10.sp))
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
            if (feedback.isNotBlank()) BasicText(feedback, style = TextStyle(color = Color(0xFFB8B4C2), fontSize = 13.sp))
            Spacer(Modifier.height(8.dp))
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
    controller: OnionDialController,
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
            Spacer(Modifier.height(8.dp))
            SmallAction(if (korean) "통화 종료" else "Hang up", enabled = true) { scope.launch { controller.hangUp() } }
        }
        is CallState.PeerOffline -> Panel(title = if (korean) "상대방 오프라인" else "Contact is offline") {
            BasicText(state.peerOnion, style = bodyStyle())
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF191C25), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText(title, style = TextStyle(color = Color(0xFFF4F2FA), fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier.background(if (enabled) Color(0xFF6558D3) else Color(0xFF383640), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.background(if (enabled) Color(0xFF343744) else Color(0xFF272933), RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 12.sp))
    }
}

private fun bodyStyle() = TextStyle(color = Color(0xFFC8C5D1), fontSize = 13.sp, textAlign = TextAlign.Start)
