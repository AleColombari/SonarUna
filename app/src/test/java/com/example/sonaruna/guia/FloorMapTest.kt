package com.example.sonaruna.guia

import com.example.sonaruna.guia.TestData.map
import com.example.sonaruna.guia.TestData.route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors web/test/unaerp.test.mjs: the same map must produce the same spoken route on Android. */
class FloorMapTest {
    private val places = map.points.values.filter { it.tipo !in setOf("cruzamento", "curva", "patamar", "porta") }

    private fun found(text: String): String? = (map.findPlace(text) as? PlaceMatch.Found)?.place?.id

    @Test
    fun `every place has a single exit and reaches every other`() {
        assertTrue(places.size >= 35)
        for (place in places) {
            assertNotNull("${place.id} needs a single segment", map.exitHeading(place.id))
            assertNotNull("no path to ${place.id}", map.planRoute(places.first().id, place.id))
        }
    }

    @Test
    fun `rooms and services are found by what the person says`() {
        assertEquals("15B", found("sala quinze b"))
        assertEquals("20B", found("vinte b"))
        assertEquals("5A", found("5A"))
        assertEquals("financeiro", found("quero ir no financeiro"))
        assertEquals("cobranca", found("cobrança"))
        assertEquals("professores", found("sala dos professores"))
        assertEquals("informacoes", found("balcão de informações"))
        assertEquals("audiovisual", found("sala de audiovisual"))
        assertEquals("banheiro-f-a", found("banheiro feminino do bloco a"))
        assertEquals("entrada-principal", found("estou na entrada"))
        assertEquals("entrada-b", found("entrada do bloco b"))
        assertEquals("saida-a", found("entrada do bloco a"))
        assertEquals("liape", found("liape"))
        assertEquals("sala-estudos", found("sala dos computadores"))
        assertTrue(map.findPlace("sala 9") is PlaceMatch.Ambiguous)
        assertTrue(map.findPlace("banheiro") is PlaceMatch.Several)
        assertEquals(PlaceMatch.UnknownRoom("A30"), map.findPlace("trinta a"))
        assertEquals(PlaceMatch.None, map.findPlace("cantina"))
    }

    @Test
    fun `from the information desk to 15B climbs the stairs and counts crossings to each turn`() {
        assertEquals(
            listOf(
                "No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil, passando por 1 cruzamento, até a escada. Quando terminar, diga pronto.",
                "Suba a escada. Quando terminar, diga pronto.",
                "No próximo cruzamento, vire à esquerda. Referência: trecho curto, de poucos passos. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.",
                "Continue em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.",
                "No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil até o fim. Você chegará na sala 15B. Quando chegar, diga pronto.",
            ),
            route("informacoes", "15B"),
        )
        assertTrue(route("15B", "protocolo").contains("Desça a escada. Quando terminar, diga pronto."))
    }

    @Test
    fun `from the main entrance the block B door is a reference and not a crossing, 20B left and 17B right`() {
        assertEquals(
            listOf(
                "No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.",
                "Daqui a 2 cruzamentos, vire à esquerda. Referência: porta da entrada do bloco B, sempre aberta. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil até o fim. Você chegará na sala 20B. Quando chegar, diga pronto.",
            ),
            route("entrada-principal", "20B"),
        )
        assertTrue(route("entrada-principal", "17B")[1].startsWith("Daqui a 3 cruzamentos, vire à direita"))
        assertTrue(route("entrada-b", "18B")[0].startsWith("Daqui a 3 cruzamentos, vire à esquerda"))
        assertTrue(route("20B", "entrada-b").last().contains("até a porta. Você chegará na entrada do bloco B"))
    }

    @Test
    fun `the side of a turn depends on the direction of travel, and rooms are not references on the way`() {
        assertEquals(
            listOf(
                "No próximo cruzamento, vire à esquerda. Quando terminar, diga pronto.",
                "Daqui a 3 cruzamentos, vire à esquerda. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil até o fim. Você chegará no banheiro feminino do bloco B. Quando chegar, diga pronto.",
            ),
            route("20B", "banheiro-f-b"),
        )
        assertEquals(
            listOf(
                "Siga em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.",
                "Continue em frente pelo piso tátil por 5 cruzamentos. Quando terminar, diga pronto.",
                "Daqui a 3 cruzamentos, vire à esquerda. Quando terminar, diga pronto.",
                "Siga em frente pelo piso tátil até o fim. Você chegará na sala 4A. Quando chegar, diga pronto.",
            ),
            route("protocolo", "4A").drop(2),
        )
        val roomReference = Regex("sala|na altura")
        for (from in places) {
            for (to in places) {
                if (from == to) continue
                for (text in route(from.id, to.id).dropLast(1)) {
                    assertFalse("${from.id} -> ${to.id}: $text", roomReference.containsMatchIn(text))
                }
            }
        }
    }

    @Test
    fun `no step asks to count more than five crossings`() {
        val tooMany = Regex("(Daqui a|por) ([6-9]|\\d\\d) cruzamentos")
        for (from in places) {
            for (to in places) {
                if (from == to) continue
                for (text in route(from.id, to.id)) assertFalse("${from.id} -> ${to.id}: $text", tooMany.containsMatchIn(text))
            }
        }
    }

    @Test
    fun `nearest of several places is chosen by route weight`() {
        val bathrooms = (map.findPlace("banheiro feminino") as PlaceMatch.Several).places
        assertEquals("banheiro-f-a", bathrooms.minBy { map.routeWeight("5A", it.id) }.id)
        assertEquals("banheiro-f-b", bathrooms.minBy { map.routeWeight("20B", it.id) }.id)
    }

    @Test
    fun `in "para a 10" the "a" is the article, not block A`() {
        assertEquals("10B", found("para a 10"))
        assertEquals("10B", found("quero ir para a dez"))
        // Room 9 exists in both blocks: "a 9" stays 9A.
        assertEquals("9A", found("para a 9"))
    }
}
