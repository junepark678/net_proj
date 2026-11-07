package dev.junepark.oniontalkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.junepark.oniontalkie.platformName

@Composable
fun App() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF191724)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = "Onion Dial\nScaffold ready on ${platformName()}",
            style = TextStyle(color = Color.White, fontSize = 24.sp, textAlign = TextAlign.Center),
        )
    }
}
