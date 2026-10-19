package com.example.sonaruna.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomCodeParserTest {
    private fun canonical(text: String) = RoomCodeParser.parse(text)?.canonical

    @Test
    fun `written codes in every common spelling become the same code`() {
        for (text in listOf("A15", "a 15", "A-15", "sala A15", "Sala a 15.", "á 15")) {
            assertEquals("Unexpected result for $text", "A15", canonical(text))
        }
    }

    @Test
    fun `spoken numbers become digits`() {
        assertEquals("A15", canonical("a quinze"))
        assertEquals("A15", canonical("Á quinze"))
        assertEquals("A22", canonical("sala a vinte e dois"))
        assertEquals("A105", canonical("a cento e cinco"))
        assertEquals("B3", canonical("b três"))
        assertEquals("A10", canonical("a dez"))
    }

    @Test
    fun `digits spoken one by one are joined`() {
        assertEquals("A15", canonical("a um cinco"))
        assertEquals("B21", canonical("b dois um"))
    }

    @Test
    fun `block and room may be given separately in either order`() {
        assertEquals("B3", canonical("bloco B sala 3"))
        assertEquals("B3", canonical("sala 3 do bloco b"))
        assertEquals("A15", canonical("bloco á sala quinze"))
    }

    @Test
    fun `a room without a block keeps only its number`() {
        assertEquals("20", canonical("quero ir para a sala 20"))
        assertEquals("10", canonical("estou na sala dez"))
        assertNull(RoomCodeParser.parse("sala 20")?.block)
    }

    @Test
    fun `a conjunction between numbers is not read as a block`() {
        assertEquals("12", canonical("sala 12 e 15"))
        assertEquals("10", canonical("sala dez e quinze"))
    }

    @Test
    fun `block after the number becomes the same letter-first code`() {
        val texts = listOf(
            "30A", "30 A", "30-a", "trinta a", "Trinta Á", "sala 30A", "sala trinta a",
            "quero ir para a sala 30 a", "trinta a por favor", "três zero a",
        )
        for (text in texts) {
            assertEquals("Unexpected result for $text", "A30", canonical(text))
        }
        assertEquals("B12", canonical("doze b"))
        assertEquals("C105", canonical("sala cento e cinco c"))
    }

    @Test
    fun `a letter after the number in the middle of the phrase is not a block`() {
        assertEquals("20", canonical("sala 20 a esquerda"))
        assertNull(RoomCodeParser.parse("30"))
        assertNull(RoomCodeParser.parse("a"))
    }

    @Test
    fun `blocks E and O count at the start, after sala or bloco and at the end, not as words`() {
        val blockE = listOf(
            "E12", "12E", "é doze", "doze e", "sala e 12", "sala 12 e", "bloco e sala 12", "sala 12 do bloco e",
        )
        for (text in blockE) {
            assertEquals("Unexpected result for $text", "E12", canonical(text))
        }
        for (text in listOf("O5", "o cinco", "sala o 5", "cinco o")) {
            assertEquals("Unexpected result for $text", "O5", canonical(text))
        }
        assertEquals("12", canonical("sala 12 e 15"))
        assertEquals("10", canonical("sala dez e quinze"))
        assertNull(RoomCodeParser.parse("quero ir para o 12"))
        assertNull(RoomCodeParser.parse("o banheiro"))
        assertNull(RoomCodeParser.parse("sala de aula"))
    }

    @Test
    fun `letters spoken by name count before or after the number`() {
        val cases = mapOf(
            "bê doze" to "B12", "doze bê" to "B12", "cê 3" to "C3", "dê 4" to "D4", "efe dez" to "F10",
            "gê 1" to "G1", "agá 3" to "H3", "jota 2" to "J2", "ká 8" to "K8", "ele 7" to "L7",
            "eme 9" to "M9", "ene 6" to "N6", "pê 5" to "P5", "quê 11" to "Q11", "erre 20" to "R20",
            "esse 4" to "S4", "sala 15 erre" to "R15", "bloco jota sala 2" to "J2",
        )
        for ((text, code) in cases) {
            assertEquals("Unexpected result for $text", code, canonical(text))
        }
    }

    @Test
    fun `only blocks A to S count`() {
        for (text in listOf("z65", "65 z", "sala t 3", "x 10")) {
            assertNull("Unexpected code for $text", canonical(text))
        }
        assertEquals("S9", canonical("s9"))
    }

    @Test
    fun `places without a room code return nothing`() {
        for (text in listOf("biblioteca", "Estou no laboratório de informática", "secretaria", "", "???")) {
            assertNull("Unexpected code for $text", RoomCodeParser.parse(text))
        }
    }

    @Test
    fun `code exposes block and number`() {
        val code = RoomCodeParser.parse("a quinze")
        assertEquals('A', code?.block)
        assertEquals(15, code?.number)
    }
}
