package com.example.sonaruna.guia

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same as the forSpeech test in web/test/engine.test.mjs. */
class TextTest {
    @Test
    fun `the voice reads the block by the letter's name, not 5 billion`() {
        assertEquals("Indo para a sala 5 bê, em 3 etapas.", forSpeech("Indo para a sala 5B, em 3 etapas."))
        assertEquals("Da sala 20 bê para a sala 9 á.", forSpeech("Da sala 20B para a sala 9A."))
        assertEquals("Etapa 1 de 3. Etapas 1 e 2. Sala A12.", forSpeech("Etapa 1 de 3. Etapas 1 e 2. Sala A12."))
    }

    @Test
    fun `a phrase cut at a pause is noticed, a finished one is not`() {
        for (text in listOf("estou na", "quero ir para a", "Estou na sala", "vou pro", "bloco")) {
            assertEquals(text, true, endsMidPhrase(text))
        }
        for (text in listOf("estou na 3A", "sala 10B", "onde estou", "sim, estou", "pronto", "", "para a 10", "estou no bloco a", "sala 5 e")) {
            assertEquals(text, false, endsMidPhrase(text))
        }
    }

    @Test
    fun `acronyms are read as words, not spelled`() {
        assertEquals("Você não parece estar no campus da Unaerp.", forSpeech("Você não parece estar no campus da UNAERP."))
        assertEquals("Indo para o Liape, bloco B.", forSpeech("Indo para o LIAPE, bloco B."))
    }
}
