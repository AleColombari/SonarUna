package com.example.sonaruna

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A single stable focus target; the icon is decorative to avoid duplicate TalkBack announcements. Under the button,
 * what the person is saying and the app's last sentence are written, for sighted testers.
 */
@Composable
fun MainScreen(state: MainUiState, onMicrophoneClick: () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(background = Color.White, primary = Color.Black)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The button stays centered; the same height above and below it, the lower part holds the text.
            val diameter = minOf(maxWidth * 0.68f, maxHeight * 0.5f).coerceIn(48.dp, 280.dp)
            val side = ((maxHeight - diameter) / 2).coerceAtLeast(0.dp)
            val microphoneState = stringResource(
                when {
                    state.listening -> R.string.state_listening
                    state.speaking -> R.string.state_speaking
                    else -> R.string.state_ready
                },
            )
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(side))
                Button(
                    onClick = onMicrophoneClick,
                    enabled = !state.busy,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black,
                        contentColor = Color.White,
                        disabledContainerColor = Color.Black,
                        disabledContentColor = Color.White,
                    ),
                    modifier = Modifier.size(diameter).semantics {
                        contentDescription = state.actionDescription
                        stateDescription = microphoneState
                    },
                ) {
                    Icon(
                        painter = painterResource(if (state.listening) R.drawable.ic_stop else R.drawable.ic_microphone),
                        contentDescription = null,
                        modifier = Modifier.size(diameter * 0.45f),
                    )
                }
                // TalkBack already hears the voice, so the written text is hidden from it.
                Column(
                    modifier = Modifier.fillMaxWidth().height(side).padding(top = 24.dp).clearAndSetSemantics {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.heard.isNotEmpty()) {
                        Text(
                            text = state.heard,
                            color = Color.Black,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.said.isNotEmpty()) {
                        Text(
                            text = state.said,
                            color = Color.DarkGray,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
