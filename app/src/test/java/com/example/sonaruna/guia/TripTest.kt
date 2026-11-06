package com.example.sonaruna.guia

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same phrases as the splitTrip test of the web version. */
class TripTest {
    @Test
    fun `splits where the person is from where they want to go, in any order`() {
        assertEquals(Trip("estou na 3 a", "a 10 b"), splitTrip("Estou na 3A e quero ir para a 10B"))
        assertEquals(Trip("estou na 3 a", "10 b"), splitTrip("quero ir pra 10B, estou na 3A"))
        assertEquals(Trip("to na entrada principal", "sala 15 b"), splitTrip("eu tô na entrada principal, me leva pra sala 15B"))
        assertEquals(Trip("sim", "o financeiro"), splitTrip("sim, quero ir para o financeiro"))
        assertEquals(Trip(null, "a 10 b"), splitTrip("quero ir para a 10B"))
        assertEquals(null, splitTrip("estou na sala 5A").destino)
    }
}
