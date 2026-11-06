package com.example.sonaruna

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyOneButtonHasActionAndAccessibleName() {
        var clicks = 0
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Começar orientação", busy = false)) { clicks++ }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
        compose.onNodeWithContentDescription("Começar orientação").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun listeningButtonStaysEnabled() {
        var clicks = 0
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Ouvindo. Pode falar", listening = true, busy = false)) { clicks++ }
        }
        compose.onNodeWithContentDescription("Ouvindo. Pode falar").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    /** A tap while the app speaks interrupts it and listens: the person's answer has priority. */
    @Test fun speakingButtonCanInterrupt() {
        var clicks = 0
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Falando. Toque para responder agora", speaking = true, busy = false)) { clicks++ }
        }
        compose.onNodeWithContentDescription("Falando. Toque para responder agora").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun buttonWaitsWhileLocating() {
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Procurando sua localização. Aguarde", busy = true)) {}
        }
        compose.onNodeWithContentDescription("Procurando sua localização. Aguarde").assertIsNotEnabled()
    }
}
