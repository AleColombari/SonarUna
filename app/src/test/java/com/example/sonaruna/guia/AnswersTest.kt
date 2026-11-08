package com.example.sonaruna.guia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mirrors the yes/no and command tests of web/test/engine.test.mjs. */
class AnswersTest {
    @Test
    fun `yes and no inside longer phrases`() {
        val yes = listOf(
            "sim", "SIM!", "Sim, isso mesmo.", "está certo", "sim sim", "acho que sim", "é isso aí", "isso mesmo, pode ir",
            "com certeza", "pode ser", "claro que sim", "tá bom", "beleza", "é esse", "quero sim", "uhum", "exatamente",
        )
        for (text in yes) assertEquals(text, YesNo.YES, classifyYesNo(text))
        val no = listOf(
            "não", "Não está correto.", "corrigir", "não não", "acho que não", "não é isso", "está errado",
            "errado, quero outro", "quero trocar", "não, me enganei", "me enganei", "não quero", "nem pensar",
        )
        for (text in no) assertEquals(text, YesNo.NO, classifyYesNo(text))
    }

    @Test
    fun `doubt and contradiction are not answers`() {
        for (text in listOf("talvez", "sim ou não", "não está errado", "simples", "não sei", "sei lá", "banana", "")) {
            assertNull(text, classifyYesNo(text))
        }
    }

    @Test
    fun `voice commands anywhere in the phrase`() {
        assertEquals(Command.NEXT, classifyCommand("já cheguei"))
        assertEquals(Command.REPEAT, classifyCommand("pode repetir"))
        assertEquals(Command.BACK, classifyCommand("voltar"))
        assertEquals(Command.STOP, classifyCommand("quero parar"))
        assertEquals(Command.RESTART, classifyCommand("recomeçar"))
        assertEquals(Command.ASSIST, classifyCommand("preciso de ajuda"))
        assertEquals(Command.HELP, classifyCommand("ajuda"))
        assertNull(classifyCommand("quero ir para a sala A12"))
    }

    @Test
    fun `normalize matches the web version`() {
        assertEquals("a 15 sala", normalize("  Á-15,  Sala!! "))
        assertEquals("a 15", normalize("A15"))
    }

    @Test
    fun `more ways to say yes, no and pronto, without mixing them up`() {
        for (text in listOf("isso mesmo", "com certeza", "sem dúvida", "fechou", "pode crer", "tá certo", "claro que sim")) {
            assertEquals(text, YesNo.YES, classifyYesNo(text))
        }
        for (text in listOf("de jeito nenhum", "nada disso", "nem pensar", "claro que não", "outro lugar")) {
            assertEquals(text, YesNo.NO, classifyYesNo(text))
        }
        for (text in listOf("não tenho certeza", "talvez", "sei lá")) assertNull(text, classifyYesNo(text))
        for (text in listOf("prontinho", "próxima", "e agora?", "terminei", "já fiz", "pode continuar")) {
            assertEquals(text, Command.NEXT, classifyCommand(text))
        }
        // Agreeing is not having walked; prepositions and the app's own words are not commands.
        for (text in listOf("certo", "beleza", "para a 10B", "no cruzamento", "siga em frente")) {
            assertNull(text, classifyCommand(text))
        }
        assertEquals(Command.REPEAT, classifyCommand("hein?"))
        assertEquals(Command.REMAINING, classifyCommand("tá longe?"))
        assertEquals(Command.WHERE, classifyCommand("onde eu tô?"))
        assertEquals(Command.STOP, classifyCommand("tchau"))
    }
}
