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
        assertEquals("12", canonical("sala 12 e"))
        assertEquals("12", canonical("sala 12 o"))
        assertNull(RoomCodeParser.parse("30"))
        assertNull(RoomCodeParser.parse("a"))
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
