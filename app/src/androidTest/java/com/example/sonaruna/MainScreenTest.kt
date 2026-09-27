package com.example.sonaruna

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
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
            MainScreen(MainUiState(actionDescription = "Ativar microfone para informar o local atual", busy = false)) { clicks++ }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
        compose.onNodeWithContentDescription("Ativar microfone para informar o local atual").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun listeningButtonCanCancel() {
        var clicks = 0
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Ouvindo. Toque para cancelar", listening = true, busy = false)) { clicks++ }
        }
        compose.onNodeWithContentDescription("Ouvindo. Toque para cancelar").assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    @Test fun importantSpeechCompletesBeforeButtonBecomesAvailable() {
        compose.setContent {
            MainScreen(MainUiState(actionDescription = "Reproduzindo instrução por voz. Aguarde", speaking = true, busy = true)) {}
        }
        compose.onNodeWithContentDescription("Reproduzindo instrução por voz. Aguarde").assertIsNotEnabled()
    }
}
