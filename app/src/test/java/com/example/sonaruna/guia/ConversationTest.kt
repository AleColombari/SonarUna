package com.example.sonaruna.guia

import com.example.sonaruna.guia.TestData.gps
import com.example.sonaruna.guia.TestData.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the conversation tests of the web version with the UNAERP map. */
class ConversationTest {
    private val entrance = gps.pontos.first { it.id == "entrada-principal" }
    private val atEntrance = gps.suggestOrigin(Position(entrance.lat, entrance.lon, 5.0))

    @Test
    fun `without GPS it asks where the person is`() {
        val hello = Conversation(map).start()
        assertEquals(Phase.ORIGIN, hello.phase)
        assertEquals(
            "Onde você está agora? Diga a sala ou o lugar, por exemplo, entrada principal, balcão de informações ou sala 5A.",
            hello.say.last(),
        )
        assertTrue(hello.listen)
    }

    @Test
    fun `GPS suggests, the person confirms with yes`() {
        val session = Conversation(map)
        val hello = session.start(atEntrance)
        assertEquals(Phase.CONFIRM_ORIGIN, hello.phase)
        assertEquals("Pela sua localização, parece que você está na entrada principal. Está certo? Diga sim ou não.", hello.say.last())
        val ok = session.hear(listOf("sim, estou"))
        assertEquals(Phase.DESTINATION, ok.phase)
        assertTrue(ok.say[0].startsWith("Você está na entrada principal."))
    }

    @Test
    fun `no asks again, and naming another place corrects directly`() {
        val first = Conversation(map)
        first.start(atEntrance)
        val no = first.hear(listOf("não"))
        assertEquals(Phase.ORIGIN, no.phase)
        assertTrue(no.say[0].startsWith("Tudo bem. Onde você está agora?"))
        assertEquals(Phase.DESTINATION, first.hear(listOf("sala 20B")).phase)

        val second = Conversation(map)
        second.start(atEntrance)
        val fixed = second.hear(listOf("não, estou no balcão de informações"))
        assertEquals(Phase.DESTINATION, fixed.phase)
        assertTrue(fixed.say[0].startsWith("Você está no balcão de informações."))
    }

    @Test
    fun `outside the campus it warns and asks`() {
        val outside = Conversation(map).start(OriginGuess.OutsideCampus)
        assertEquals(Phase.ORIGIN, outside.phase)
        assertTrue(outside.say.joinToString(" ").contains("você não parece estar no campus da UNAERP. Onde você está agora?"))
    }

    @Test
    fun `nearest bathroom, switch to the women's, then the full route to arrival`() {
        val session = Conversation(map)
        session.start()
        session.hear(listOf("estou na sala 5A"))
        assertTrue(session.hear(listOf("banheiro")).say[0].contains("banheiro masculino do bloco A"))
        assertTrue(session.hear(listOf("não, feminino")).say[0].contains("banheiro feminino do bloco A"))
        val go = session.hear(listOf("isso"))
        assertEquals(Phase.NAVIGATING, go.phase)
        assertEquals("Rota até o banheiro feminino do bloco A, em 3 etapas, seguindo o piso tátil.", go.say[0])
        assertTrue(go.say[1].startsWith("Etapa 1 de 3. "))
        assertTrue(session.hear(listOf("pronto")).say[0].startsWith("Etapa 2 de 3. "))
        assertTrue(session.hear(listOf("voltar")).say[0].startsWith("Etapa 1 de 3. "))
        session.hear(listOf("pronto"))
        session.hear(listOf("pronto"))
        val arrived = session.hear(listOf("cheguei"))
        assertEquals(Phase.IDLE, arrived.phase)
        assertFalse(arrived.listen)
        assertEquals("Você chegou no banheiro feminino do bloco A. Toque no botão para uma nova orientação.", arrived.say[0])
    }

    @Test
    fun `confirmation that names another place changes the destination`() {
        val session = Conversation(map)
        session.start()
        session.hear(listOf("informações"))
        session.hear(listOf("sala 15B"))
        val changed = session.hear(listOf("não, quero ir para o financeiro"))
        assertEquals(Phase.CONFIRM, changed.phase)
        assertTrue(changed.say[0].contains("financeiro"))
        assertTrue(session.hear(listOf("quero ir para a sala 30")).say[0].startsWith("Não encontrei a sala 30"))
    }

    @Test
    fun `alternatives from the recognizer are all tried`() {
        val session = Conversation(map)
        session.start()
        assertEquals(Phase.DESTINATION, session.hear(listOf("xis", "sala cinco a")).phase)
    }

    @Test
    fun `silence on a question asks again twice, then keeps listening quietly without a tap`() {
        val session = Conversation(map)
        session.start()
        assertEquals("Não ouvi. Pode repetir?", session.silence().say[0])
        session.silence()
        repeat(3) {
            val quiet = session.silence()
            assertEquals(Phase.ORIGIN, quiet.phase)
            assertTrue(quiet.listen)
            assertEquals(emptyList<String>(), quiet.say)
        }
        assertEquals("Retomando.", session.resume().say[0])
    }

    private fun navigating(): Conversation = Conversation(map).also {
        it.start()
        it.hear(listOf("informações"))
        it.hear(listOf("sala 15B"))
        it.hear(listOf("sim"))
    }

    @Test
    fun `on a route, silence is the person walking - keep listening quietly`() {
        val session = navigating()
        session.hear(listOf("pronto"))
        repeat(5) {
            val quiet = session.silence()
            assertEquals(Phase.NAVIGATING, quiet.phase)
            assertTrue(quiet.listen)
            assertEquals(emptyList<String>(), quiet.say)
        }
        assertTrue(session.resume().say[0].startsWith("Retomando a etapa 2 de 8. Diga pronto para a próxima"))
        assertTrue(session.hear(listOf("pronto")).say[0].startsWith("Etapa 3 de 8. Suba a escada"))
    }

    @Test
    fun `another destination keeps the origin, halfway it starts where the last finished step ended`() {
        val confirm = Conversation(map)
        confirm.start()
        confirm.hear(listOf("informações"))
        confirm.hear(listOf("sala 15B"))
        val other = confirm.hear(listOf("não, outro destino"))
        assertEquals(Phase.DESTINATION, other.phase)
        assertEquals("Certo. Para onde você quer ir?", other.say[0])

        val midway = navigating()
        repeat(3) { midway.hear(listOf("pronto")) } // steps 1 to 3 done: the person is at the top of the stairs
        assertEquals(Phase.DESTINATION, midway.hear(listOf("novo destino")).phase)
        midway.hear(listOf("sala 20B"))
        val route = midway.hear(listOf("sim"))
        assertTrue(route.say[0].startsWith("Rota até a sala 20B, em 6 etapas"))
        assertFalse(route.say.joinToString(" ").contains("escada"))
    }

    @Test
    fun `naming a place halfway changes the destination`() {
        val changed = navigating().hear(listOf("quero ir para o protocolo"))
        assertEquals(Phase.CONFIRM, changed.phase)
        assertTrue(changed.say[0].contains("protocolo"))
    }

    @Test
    fun `exit and quero sair go to the nearest exit without ending the guidance`() {
        fun destination(from: String, text: String): String {
            val session = Conversation(map)
            session.start()
            session.hear(listOf(from))
            return session.hear(listOf(text)).say[0]
        }
        assertTrue(destination("sala 20B", "saída").contains("entrada do bloco B"))
        assertTrue(destination("sala 5A", "saída").contains("entrada do bloco A"))
        assertTrue(destination("sala 5A", "quero sair").contains("entrada do bloco A"))
        assertTrue(destination("informações", "saída principal").contains("entrada principal"))
    }

    @Test
    fun `commands work in any phase`() {
        val session = Conversation(map)
        session.start()
        session.hear(listOf("sala 5A"))
        assertTrue(session.hear(listOf("repetir")).say[0].startsWith("Para onde você quer ir?"))
        assertEquals(Phase.ORIGIN, session.hear(listOf("voltar")).phase)
        assertTrue(session.hear(listOf("ajuda")).say.last().startsWith("Você pode dizer a qualquer momento"))
        assertTrue(session.hear(listOf("preciso de ajuda")).say[0].startsWith("O pedido de ajuda por ligação ainda não está neste aplicativo."))
        assertEquals(Phase.IDLE, session.hear(listOf("parar")).phase)
    }

    @Test
    fun `a repeated name as origin asks for the full name`() {
        val session = Conversation(map)
        session.start()
        assertTrue(session.hear(listOf("estou no banheiro")).say[0].startsWith("Encontrei mais de um lugar"))
        assertEquals(Phase.ORIGIN, session.phase)
    }
}
