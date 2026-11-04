package com.example.sonaruna.guia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Mirrors web/test/piso.test.mjs with small synthetic maps. */
class InstructionsTest {
    /** A straight corridor north with [n] crossings ending at room Z, straight ahead or after a right turn. */
    private fun corridor(n: Int, turn: Boolean = false, limit: Int? = null): List<String> {
        val ids = (1..n).map { "c$it" }
        val pontos = listOf("""{"id":"inicio","nome":"início","tipo":"sala"}""") +
            ids.map { """{"id":"$it","nome":"$it","tipo":"cruzamento"}""" } +
            """{"id":"Z","nome":"sala Z","tipo":"sala"}"""
        val chain = listOf("inicio") + ids
        val trechos = chain.zipWithNext { a, b -> """{"de":"$a","para":"$b","rumo":0}""" } +
            """{"de":"${ids.last()}","para":"Z","rumo":${if (turn) 90 else 0}}"""
        val extra = limit?.let { ""","cruzamentosPorEtapa":$it""" } ?: ""
        val map = FloorMap.parse("""{"guia":"piso"$extra,"pontos":[${pontos.joinToString()}],"trechos":[${trechos.joinToString()}]}""")
        return map.buildInstructions(0.0, map.planRoute("inicio", "Z")!!).map { it.texto.substringBefore(". ") }
    }

    @Test
    fun `a long straight to the end becomes steps of at most five crossings`() {
        assertEquals(
            listOf(
                "Siga em frente pelo piso tátil até o quinto cruzamento",
                "Continue em frente pelo piso tátil até o quinto cruzamento",
                "Continue em frente pelo piso tátil, passando por 1 cruzamento, até o fim",
            ),
            corridor(11),
        )
        assertEquals(listOf("Siga em frente pelo piso tátil, passando por 5 cruzamentos, até o fim"), corridor(5))
    }

    @Test
    fun `the crossing where the person turns counts toward the limit`() {
        assertEquals(
            listOf("Siga em frente pelo piso tátil até o quinto cruzamento", "Vire à direita e siga o piso tátil até o fim"),
            corridor(5, turn = true),
        )
        assertEquals(
            listOf(
                "Siga em frente pelo piso tátil até o quinto cruzamento",
                "Continue em frente pelo piso tátil até o primeiro cruzamento",
                "Vire à direita e siga o piso tátil até o fim",
            ),
            corridor(6, turn = true),
        )
    }

    @Test
    fun `the map can change the limit`() {
        assertEquals(
            listOf(
                "Siga em frente pelo piso tátil até o terceiro cruzamento",
                "Continue em frente pelo piso tátil até o terceiro cruzamento",
                "Continue em frente pelo piso tátil, passando por 1 cruzamento, até o fim",
            ),
            corridor(7, limit = 3),
        )
    }

    @Test
    fun `stairs are their own step and reverse on the way back`() {
        val map = FloorMap.parse(
            """{"guia":"piso","pontos":[
              {"id":"a","nome":"sala A","tipo":"sala"},{"id":"x","nome":"x","tipo":"cruzamento"},
              {"id":"topo","nome":"topo","tipo":"patamar"},{"id":"b","nome":"sala B","tipo":"sala"}],
              "trechos":[{"de":"a","para":"x","rumo":0},{"de":"x","para":"topo","rumo":0,"escada":"sobe"},
              {"de":"topo","para":"b","rumo":270}]}""",
        )
        assertEquals(
            listOf(
                "Siga em frente pelo piso tátil até a escada. Quando terminar, diga pronto.",
                "Suba a escada. Quando terminar, diga pronto.",
                "Vire à esquerda e siga o piso tátil até o fim. Você chegará na sala B. Quando chegar, diga pronto.",
            ),
            map.buildInstructions(0.0, map.planRoute("a", "b")!!).map { it.texto },
        )
        assertEquals(Stair.DOWN, map.planRoute("b", "a")!!.first { it.escada != null }.escada)
    }

    @Test
    fun `invalid maps fail with a clear message`() {
        assertThrows(IllegalArgumentException::class.java) { FloorMap.parse("""{"guia":"passos","pontos":[],"trechos":[]}""") }
        val missing = assertThrows(IllegalArgumentException::class.java) {
            FloorMap.parse("""{"guia":"piso","pontos":[{"id":"a","nome":"a"}],"trechos":[{"de":"a","para":"x","rumo":0}]}""")
        }
        assertEquals("Trecho usa ponto inexistente: x", missing.message)
    }
}
