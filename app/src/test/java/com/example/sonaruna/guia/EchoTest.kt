package com.example.sonaruna.guia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTest {
    private val spoken = Echo.words("Indo para a sala 10B, em 8 etapas. Etapa 1 de 8. No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.")

    @Test
    fun `the app's own words, even misheard a little, do not interrupt the voice`() {
        assertFalse(Echo.interrupts("indo para a sala 10 b", spoken, needed = 2))
        assertFalse(Echo.interrupts("no proximo cruzamento vira a esquerda", spoken, needed = 2))
        assertFalse(Echo.interrupts("quando terminar diga pronto", spoken, needed = 1))
        assertFalse(Echo.interrupts("diga pronto", spoken, needed = 1))
    }

    @Test
    fun `the person's new words interrupt, even after a stretch of echo`() {
        assertTrue(Echo.interrupts("quero ir para o banheiro", spoken, needed = 2))
        assertTrue(Echo.interrupts("no proximo cruzamento quero ir para o financeiro", spoken, needed = 2))
        assertTrue(Echo.interrupts("parar", spoken, needed = 1))
        // One new word in a phrase still open may be noise.
        assertFalse(Echo.interrupts("banheiro", spoken, needed = 2))
    }

    @Test
    fun `after the speech, the tail of the app's voice is dropped and only the person's part is left`() {
        assertEquals("", Echo.answerAfterSpeech("diga pronto", spoken))
        assertEquals("", Echo.answerAfterSpeech("quando terminar diga pronto", spoken))
        assertEquals("sim", Echo.answerAfterSpeech("diga pronto sim", spoken))
        assertEquals("pronto", Echo.answerAfterSpeech("diga pronto pronto", spoken))
        // A single word is the person's: "pronto" right after the app is the most common answer.
        assertEquals("pronto", Echo.answerAfterSpeech("pronto", spoken))
        // "quero ir" is not "quer ir": the person's answer stays whole.
        assertEquals("quero ir para a 5 a", Echo.answerAfterSpeech("quero ir para a 5A", Echo.words("Você está na sala 3B. Para onde você quer ir?")))
    }

    @Test
    fun `close words match, short words and numbers must be exact`() {
        assertFalse(Echo.sameWord("parar", "para"))
        assertTrue(Echo.sameWord("vire", "vira"))
        assertFalse(Echo.sameWord("10", "11"))
        assertFalse(Echo.sameWord("sim", "sem"))
    }
}
