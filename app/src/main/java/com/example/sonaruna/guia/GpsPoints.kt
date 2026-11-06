package com.example.sonaruna.guia

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS reading; [precisao] is the accuracy radius in meters. */
data class Position(val lat: Double, val lon: Double, val precisao: Double)

data class GpsPoint(val id: String, val lat: Double, val lon: Double, val raio: Double, val place: Place)

data class GpsArea(val lat: Double, val lon: Double, val raio: Double)

/** What the GPS suggests about where the person is. It only ever becomes a question, never a decision. */
sealed interface OriginGuess {
    data class Suggestion(val place: Place, val distancia: Double) : OriginGuess
    data object Imprecise : OriginGuess
    data object OutsideCampus : OriginGuess
    data object NoMatch : OriginGuess
}

/**
 * The GPS points of data/unaerp-pontos.geojson (RFC 7946, coordinates as [longitude, latitude]), ported from
 * web/localizacao.js. Only features with "ponto", coordinates and "sugerir": true are starting places that can be
 * suggested (the entrances); other linked features only have their point checked.
 */
class GpsPoints(val campus: GpsArea?, val pontos: List<GpsPoint>, val pendentes: List<String>) {

    fun suggestOrigin(position: Position?): OriginGuess {
        if (position == null || !(position.precisao <= MAX_ACCURACY_METERS)) return OriginGuess.Imprecise
        if (campus != null && distanceMeters(campus.lat, campus.lon, position.lat, position.lon) > campus.raio + position.precisao) {
            return OriginGuess.OutsideCampus
        }
        val best = pontos
            .map { it to distanceMeters(it.lat, it.lon, position.lat, position.lon) }
            .filter { (point, distance) -> distance <= point.raio + position.precisao }
            .minByOrNull { it.second }
        return if (best != null) OriginGuess.Suggestion(best.first.place, best.second) else OriginGuess.NoMatch
    }

    companion object {
        /**
         * Above this accuracy (meters) a reading cannot tell one entrance from another, so nothing is suggested. The
         * closest entrances (main and block B) are about 26 m apart: with a 15 m radius and at most 10 m of error,
         * they are never confused.
         */
        const val MAX_ACCURACY_METERS = 10.0

        fun parse(json: String, map: FloorMap): GpsPoints {
            val raw = JSONObject(json)
            require(raw.optString("type") == "FeatureCollection") { "O arquivo de pontos precisa ser um FeatureCollection do GeoJSON" }
            var campus: GpsArea? = null
            val pontos = mutableListOf<GpsPoint>()
            val pendentes = mutableListOf<String>()
            for (feature in raw.getJSONArray("features").objects()) {
                val props = feature.optJSONObject("properties") ?: JSONObject()
                val name = feature.optStringOrNull("id") ?: props.optStringOrNull("nome") ?: "?"
                val ponto = props.optStringOrNull("ponto")
                val geometry = feature.optJSONObject("geometry")
                if (geometry == null) {
                    if (ponto != null) pendentes += ponto
                    continue
                }
                require(geometry.optString("type") == "Point") { "Feição $name: use geometria Point" }
                val coordinates = geometry.getJSONArray("coordinates")
                val lon = coordinates.getDouble(0)
                val lat = coordinates.getDouble(1)
                require(kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180) { "Feição $name: coordenadas inválidas" }
                if (props.optString("tipo") == "campus") {
                    campus = GpsArea(lat, lon, props.optDouble("raio", 400.0))
                    continue
                }
                if (ponto == null) continue
                val place = requireNotNull(map.points[ponto]) { "Feição $name aponta para um ponto que não existe no mapa: $ponto" }
                if (!props.optBoolean("sugerir", false)) continue
                val raio = props.optDouble("raio", 0.0)
                require(raio > 0) { "Feição $name precisa de raio maior que zero" }
                pontos += GpsPoint(ponto, lat, lon, raio, place)
            }
            return GpsPoints(campus, pontos, pendentes)
        }
    }
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

/** Haversine distance in meters. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
}
