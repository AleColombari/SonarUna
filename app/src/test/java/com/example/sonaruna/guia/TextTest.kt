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
}
