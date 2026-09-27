package com.example.sonaruna

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A single stable focus target; the icon is decorative to avoid duplicate TalkBack announcements. */
@Composable
fun MainScreen(state: MainUiState, onMicrophoneClick: () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(background = Color.White, primary = Color.Black)) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val diameter = (minOf(maxWidth, maxHeight) * 0.68f).coerceIn(48.dp, 280.dp)
            val microphoneState = stringResource(
                when {
                    state.listening -> R.string.state_listening
                    state.speaking -> R.string.state_speaking
                    else -> R.string.state_ready
                },
            )
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
        }
    }
}
