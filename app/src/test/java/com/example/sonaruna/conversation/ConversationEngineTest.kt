package com.example.sonaruna.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationEngineTest {
    private val location = ApproximateLocation(
        latitude = -21.1795,
        longitude = -47.7812,
        accuracyMeters = 35f,
        timestampMillis = 1_000_000L,
    )

    @Test
    fun `initialization requires permissions and approximate location before listening`() {
        val engine = ConversationEngine()
        assertEquals(ConversationPhase.INITIALIZING, engine.state.phase)
        assertPrompt(engine.awaitPermissions(), ConversationPhase.PERMISSIONS, PromptKey.PERMISSIONS_EXPLANATION)
        assertPrompt(engine.locating(), ConversationPhase.LOCATING, PromptKey.LOCATING)
        assertPrompt(engine.locationObtained(location), ConversationPhase.ORIGIN, PromptKey.ORIGIN_REQUEST)
        assertEquals(location, engine.state.location)
    }

    @Test
    fun `complete flow keeps location and both spoken places in memory`() {
        val engine = readyEngine()
        val origin = "Estou no laboratório de informática"
        val destination = "Quero ir para a sala 20"
        assertEquals(
            Prompt(PromptKey.ORIGIN_CONFIRMATION, listOf(origin)),
            engine.recognize(origin).prompt,
        )
        assertPrompt(engine.recognize("sim"), ConversationPhase.DESTINATION, PromptKey.DESTINATION_REQUEST)
        assertEquals(
            Prompt(PromptKey.DESTINATION_CONFIRMATION, listOf(destination)),
            engine.recognize(destination).prompt,
        )
        val complete = engine.recognize("confirmo")
        assertPrompt(complete, ConversationPhase.COMPLETE, PromptKey.COMPLETE)
        assertEquals(listOf(origin, destination), complete.prompt?.arguments)
        assertEquals(location, complete.state.location)
        assertEquals(origin, complete.state.origin)
        assertEquals(destination, complete.state.destination)
        assertEquals(complete.state, engine.state)
    }

    @Test
    fun `spoken content retains accents case and punctuation while removing excess spaces`() {
        val engine = readyEngine()
        val origin = engine.recognize(" \t Estou\n  no   Bloco Á,\u00a0Sala 12!  ")
        assertEquals("Estou no Bloco Á, Sala 12!", origin.state.origin)
        assertEquals(listOf("Estou no Bloco Á, Sala 12!"), origin.prompt?.arguments)
        engine.recognize("sim")
        val destination = engine.recognize(" \n  Biblioteca  Central  ")
        assertEquals("Biblioteca Central", destination.state.destination)
        assertEquals(listOf("Biblioteca Central"), destination.prompt?.arguments)
    }

    @Test
    fun `all supported positive confirmations accept origin and destination`() {
        val answers = listOf("sim", "correto", "isso", "está certo", "confirmo", "SIM!", "  ÉSTA CERTO  ", "Sim, isso mesmo.")
        for (answer in answers) {
            val engine = readyEngine()
            engine.recognize("Sala 12")
            assertPrompt(engine.recognize(answer), ConversationPhase.DESTINATION, PromptKey.DESTINATION_REQUEST)
            engine.recognize("Biblioteca")
            assertPrompt(engine.recognize(answer), ConversationPhase.COMPLETE, PromptKey.COMPLETE)
        }
    }

    @Test
    fun `negative confirmation clears the origin and asks for it again`() {
        val answers = listOf("não", "errado", "não está correto", "corrigir", "NÃO!", "  Não está correto.  ")
        for (answer in answers) {
            val engine = readyEngine()
            engine.recognize("Sala 12")
            val result = engine.recognize(answer)
            assertPrompt(result, ConversationPhase.ORIGIN, PromptKey.ORIGIN_RETRY)
            assertNull(result.state.origin)
            assertNull(result.state.destination)
            assertEquals(location, result.state.location)
        }
    }

    @Test
    fun `negative confirmation clears only the destination and permits correcting it`() {
        val engine = atDestinationConfirmation()
        val rejected = engine.recognize("não está correto")
        assertPrompt(rejected, ConversationPhase.DESTINATION, PromptKey.DESTINATION_RETRY)
        assertEquals("Sala 12", rejected.state.origin)
        assertNull(rejected.state.destination)
        assertEquals(location, rejected.state.location)
        engine.recognize("Secretaria")
        val complete = engine.recognize("está certo")
        assertEquals(ConversationPhase.COMPLETE, complete.state.phase)
        assertEquals("Secretaria", complete.state.destination)
    }

    @Test
    fun `unknown ambiguous and negated positive responses never confirm by substring`() {
        val answers = listOf(
            "talvez", "não sei", "sim ou não", "sim, não", "sim mas não está correto",
            "não está errado", "não errado", "incorretamente", "simples", "correto ou errado", "???",
        )
        for (answer in answers) {
            for (phase in listOf(ConversationPhase.CONFIRM_ORIGIN, ConversationPhase.CONFIRM_DESTINATION)) {
                val engine = ConversationEngine(ConversationState(phase, location, "Sala 12", "Biblioteca"))
                val previous = engine.state
                val result = engine.recognize(answer)
                assertEquals("Unexpected state change for $answer", previous, result.state)
                assertEquals(PromptKey.CONFIRMATION_UNCLEAR, result.prompt?.key)
            }
        }
    }

    @Test
    fun `empty recognition leaves each listening phase intact and offers retry`() {
        val phases = listOf(
            ConversationPhase.ORIGIN, ConversationPhase.CONFIRM_ORIGIN,
            ConversationPhase.DESTINATION, ConversationPhase.CONFIRM_DESTINATION,
        )
        for (phase in phases) {
            val engine = ConversationEngine(ConversationState(phase, location, "Sala 12", "Biblioteca"))
            val previous = engine.state
            for (answer in listOf("", " \n\t ", "\u00a0\u2003")) {
                val result = engine.recognize(answer)
                assertEquals(previous, result.state)
                assertEquals(PromptKey.RECOGNITION_EMPTY, result.prompt?.key)
            }
        }
    }

    @Test
    fun `recognition results outside a listening phase have no effect`() {
        for (phase in listOf(
            ConversationPhase.INITIALIZING, ConversationPhase.PERMISSIONS,
            ConversationPhase.LOCATING, ConversationPhase.COMPLETE, ConversationPhase.ERROR,
        )) {
            val engine = ConversationEngine(ConversationState(phase))
            val previous = engine.state
            for (answer in listOf("Sala 12", "sim", "")) {
                val result = engine.recognize(answer)
                assertEquals(previous, result.state)
                assertNull(result.prompt)
            }
        }
    }

    @Test
    fun `late location results do not overwrite the active conversation`() {
        for (phase in ConversationPhase.values().filter { it != ConversationPhase.LOCATING }) {
            val engine = ConversationEngine(ConversationState(phase, location, "Sala 12", "Biblioteca"))
            val previous = engine.state
            val result = engine.locationObtained(location.copy(latitude = 0.0))
            assertEquals(previous, result.state)
            assertNull(result.prompt)
        }
    }

    @Test
    fun `restart clears both places and reuses a recent reading`() {
        val engine = completedEngine()
        val result = engine.restart(location.timestampMillis + 60_000L)
        assertPrompt(result, ConversationPhase.ORIGIN, PromptKey.ORIGIN_REQUEST)
        assertEquals(location, result.state.location)
        assertNull(result.state.origin)
        assertNull(result.state.destination)
    }

    @Test
    fun `restart accepts a reading exactly two minutes old`() {
        val engine = completedEngine()
        assertEquals(ConversationPhase.ORIGIN, engine.restart(location.timestampMillis + 120_000L).state.phase)
        assertEquals(location, engine.state.location)
    }

    @Test
    fun `restart requests location again when the reading is old or in the future`() {
        for (nowMillis in listOf(location.timestampMillis + 120_001L, location.timestampMillis - 1L)) {
            val engine = completedEngine()
            val result = engine.restart(nowMillis)
            assertPrompt(result, ConversationPhase.LOCATING, PromptKey.LOCATING)
            assertNull(result.state.location)
            assertNull(result.state.origin)
            assertNull(result.state.destination)
        }
    }

    @Test
    fun `restart requests a reading when none exists`() {
        val result = ConversationEngine().restart(location.timestampMillis)
        assertPrompt(result, ConversationPhase.LOCATING, PromptKey.LOCATING)
        assertNull(result.state.location)
    }

    private fun readyEngine() = ConversationEngine().apply {
        locating()
        locationObtained(location)
    }

    private fun atDestinationConfirmation() = readyEngine().apply {
        recognize("Sala 12")
        recognize("sim")
        recognize("Biblioteca")
    }

    private fun completedEngine() = atDestinationConfirmation().apply {
        recognize("sim")
    }

    private fun assertPrompt(result: Transition, phase: ConversationPhase, prompt: PromptKey) {
        assertEquals(phase, result.state.phase)
        assertEquals(prompt, result.prompt?.key)
    }
}
