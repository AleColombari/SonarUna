package com.example.sonaruna.guia

import com.example.sonaruna.guia.TestData.gps
import com.example.sonaruna.guia.TestData.map
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors web/test/localizacao.test.mjs with the real data/unaerp-pontos.geojson (Google My Maps points). */
class GpsPointsTest {
    private val geojson = JSONObject(TestData.read("unaerp-pontos.geojson"))

    private fun at(id: String, precisao: Double = 5.0): Position {
        val features = geojson.getJSONArray("features")
        val feature = (0 until features.length()).map { features.getJSONObject(it) }.first { it.optString("id") == id }
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        return Position(coordinates.getDouble(1), coordinates.getDouble(0), precisao)
    }

    @Test
    fun `three starting places, all inside the campus`() {
        assertEquals(listOf("entrada-b", "entrada-principal", "saida-a"), gps.pontos.map { it.id }.sorted())
        assertEquals(emptyList<String>(), gps.pendentes)
        val campus = gps.campus!!
        for (point in gps.pontos) assertTrue(point.id, distanceMeters(campus.lat, campus.lon, point.lat, point.lon) < campus.raio)
    }

    @Test
    fun `each entrance marked on My Maps suggests itself`() {
        for (id in listOf("entrada-principal", "entrada-b", "saida-a")) {
            assertEquals(id, (gps.suggestOrigin(at(id)) as OriginGuess.Suggestion).place.id)
        }
        // At the fork between the main entrance and block B's, the nearest wins.
        assertEquals("entrada-principal", (gps.suggestOrigin(at("mymaps-bifurcacao-bloco-b-h")) as OriginGuess.Suggestion).place.id)
    }

    @Test
    fun `imprecise, far from every point or outside the campus suggests nothing`() {
        val entrance = at("entrada-principal")
        assertEquals(OriginGuess.Imprecise, gps.suggestOrigin(entrance.copy(precisao = GpsPoints.MAX_ACCURACY_METERS + 1)))
        assertEquals(OriginGuess.Imprecise, gps.suggestOrigin(null))
        assertEquals(OriginGuess.NoMatch, gps.suggestOrigin(entrance.copy(lat = entrance.lat - 120 / 111_320.0, precisao = 10.0)))
        assertEquals(OriginGuess.OutsideCampus, gps.suggestOrigin(Position(-21.17, -47.81, 10.0)))
    }

    @Test
    fun `haversine distance in meters`() {
        val d = distanceMeters(-21.2, -47.78, -21.2 + 100 / 111_320.0, -47.78)
        assertTrue(d.toString(), kotlin.math.abs(d - 100) < 0.5)
    }

    @Test
    fun `a feature pointing to a missing map point fails clearly`() {
        val bad = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},
            "properties":{"ponto":"nao-existe","sugerir":true,"raio":10}}]}"""
        val error = assertThrows(IllegalArgumentException::class.java) { GpsPoints.parse(bad, map) }
        assertTrue(error.message!!.contains("não existe no mapa"))
    }
}
