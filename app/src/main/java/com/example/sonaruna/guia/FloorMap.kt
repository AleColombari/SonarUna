package com.example.sonaruna.guia

import com.example.sonaruna.conversation.RoomCodeParser
import org.json.JSONArray
import org.json.JSONObject

/** A point of the tactile-paving graph: a crossing, a landing, a door, or a place someone can ask for. */
data class Place(
    val id: String,
    val nome: String,
    val tipo: String,
    val artigo: String? = null,
    val codigo: String? = null,
    val aliases: List<String> = emptyList(),
    val rumoPartida: Double? = null,
) {
    /** The map's article; without one, "o" for crossings and landings ("no cruzamento"), otherwise "a". */
    private val article: String get() = artigo ?: if (tipo == "cruzamento" || tipo == "patamar") "o" else "a"
    val emLugar: String get() = "n$article $nome"
    val paraLugar: String get() = "para $article $nome"
    val ateLugar: String get() = "até $article $nome"
    val deLugar: String get() = "d$article $nome"
}

/** Direction of a staircase, from the start of a segment to its end. */
enum class Stair { UP, DOWN, UNKNOWN;

    fun reversed(): Stair = when (this) {
        UP -> DOWN
        DOWN -> UP
        UNKNOWN -> UNKNOWN
    }
}

data class Edge(val to: String, val rumo: Double, val escada: Stair?, val peso: Double, val referencias: List<String>)

data class Segment(val from: String, val to: String, val rumo: Double, val escada: Stair?, val referencias: List<String>)

sealed interface PlaceMatch {
    data class Found(val place: Place) : PlaceMatch

    /** A room number without block that exists in more than one block: the person has to choose. */
    data class Ambiguous(val places: List<Place>) : PlaceMatch

    /** Several places answer to the same name ("banheiro"): the caller may pick the nearest. */
    data class Several(val places: List<Place>) : PlaceMatch
    data class UnknownRoom(val code: String) : PlaceMatch
    data object None : PlaceMatch
}

/**
 * The map in data/unaerp.json (packaged as an asset), guided by tactile paving: no measurements, "rumo" follows the
 * sketch (0 = up on the sheet, 90 = right) and is only compared from one segment to the next. Pure Kotlin port of
 * web/engine.js for "guia": "piso" maps.
 */
class FloorMap(
    val nome: String?,
    val exemplo: String,
    val exemploOrigem: String,
    val cruzamentosPorEtapa: Int,
    val points: Map<String, Place>,
    val adjacency: Map<String, List<Edge>>,
) {
    fun findPlace(text: String): PlaceMatch {
        val rooms = points.values.filter { it.codigo != null }
        val code = RoomCodeParser.parse(text)
        if (code != null) {
            if (code.block != null) {
                // The map may spell a code "A30" or "30A"; both become the same canonical code.
                val room = rooms.find { RoomCodeParser.parse(it.codigo!!)?.canonical == code.canonical }
                if (room != null) return PlaceMatch.Found(room)
                // "para a 10" read as room A10: the "a" was the article. When only one room has that number, it is that.
                if (code.block == 'A') {
                    rooms.filter { it.codigo!!.filter(Char::isDigit).toIntOrNull() == code.number }
                        .singleOrNull()?.let { return PlaceMatch.Found(it) }
                }
                return PlaceMatch.UnknownRoom(code.canonical)
            }
            val matches = rooms.filter { it.codigo!!.filter(Char::isDigit).toIntOrNull() == code.number }
            return when (matches.size) {
                0 -> PlaceMatch.UnknownRoom(code.number.toString())
                1 -> PlaceMatch.Found(matches[0])
                else -> PlaceMatch.Ambiguous(matches)
            }
        }
        // The longest matching alias wins; a tie between different places returns all of them.
        val query = normalize(text)
        var bestLength = 0
        var best = mutableListOf<Place>()
        for (place in points.values) {
            if (place.codigo != null) continue
            val length = place.aliases.filter { it.isNotEmpty() && containsPhrase(query, it) }.maxOfOrNull { it.length } ?: 0
            if (length == 0 || length < bestLength) continue
            if (length > bestLength) best = mutableListOf()
            bestLength = length
            best += place
        }
        return when (best.size) {
            0 -> PlaceMatch.None
            1 -> PlaceMatch.Found(best[0])
            else -> PlaceMatch.Several(best)
        }
    }

    /** Lightest route by segment weight (Dijkstra): empty when origin is the destination, null without a path. */
    fun planRoute(fromId: String, toId: String): List<Segment>? {
        if (fromId !in points || toId !in points) return null
        val distance = mutableMapOf(fromId to 0.0)
        val previous = mutableMapOf<String, Pair<String, Edge>>()
        val pending = mutableSetOf(fromId)
        val done = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.minBy { distance.getValue(it) }
            pending -= current
            if (current == toId) break
            done += current
            for (edge in adjacency.getValue(current)) {
                if (edge.to in done) continue
                val candidate = distance.getValue(current) + edge.peso
                if (candidate < (distance[edge.to] ?: Double.POSITIVE_INFINITY)) {
                    distance[edge.to] = candidate
                    previous[edge.to] = current to edge
                    pending += edge.to
                }
            }
        }
        if (toId !in distance) return null
        val segments = ArrayDeque<Segment>()
        var at = toId
        while (at != fromId) {
            val (from, edge) = previous.getValue(at)
            segments.addFirst(Segment(from, at, edge.rumo, edge.escada, edge.referencias))
            at = from
        }
        return segments.toList()
    }

    /** Total route weight (infinite without a path), used to pick the nearest of several places. */
    fun routeWeight(fromId: String, toId: String): Double {
        val segments = planRoute(fromId, toId) ?: return Double.POSITIVE_INFINITY
        return segments.sumOf { segment -> adjacency.getValue(segment.from).first { it.to == segment.to }.peso }
    }

    /**
     * Direction of someone leaving a place by its only segment (a room door or the end of the paving), or null.
     * A point in the middle of the paving (a block door) gives the usual direction in "rumoPartida".
     */
    fun exitHeading(pointId: String): Double? {
        points[pointId]?.rumoPartida?.let { return mod360(it) }
        val edges = adjacency[pointId].orEmpty()
        return if (edges.size == 1) edges[0].rumo else null
    }

    companion object {
        /** Validates the map JSON with a clear message, as web/engine.js loadMap does. */
        fun parse(json: String): FloorMap {
            val raw = JSONObject(json)
            require(raw.optString("guia") == "piso") { "O app Android só lê mapas com \"guia\": \"piso\"" }
            val points = linkedMapOf<String, Place>()
            raw.getJSONArray("pontos").objects().forEach { point ->
                val id = point.getString("id")
                require(id !in points) { "Ponto repetido: $id" }
                points[id] = Place(
                    id = id,
                    nome = point.getString("nome"),
                    tipo = point.optString("tipo"),
                    artigo = point.optStringOrNull("artigo"),
                    codigo = point.optStringOrNull("codigo")?.uppercase(),
                    aliases = point.optJSONArray("aliases")?.strings()?.map(::normalize).orEmpty(),
                    rumoPartida = if (point.has("rumoPartida")) point.getDouble("rumoPartida") else null,
                )
            }
            val adjacency = points.keys.associateWith { mutableListOf<Edge>() }
            raw.getJSONArray("trechos").objects().forEach { edge ->
                val from = edge.getString("de")
                val to = edge.getString("para")
                for (end in listOf(from, to)) require(end in points) { "Trecho usa ponto inexistente: $end" }
                require(edge.has("rumo")) { "Trecho $from -> $to precisa de rumo" }
                val peso = edge.optDouble("peso", 1.0)
                require(peso > 0) { "Trecho $from -> $to precisa de peso maior que zero" }
                val escada = edge.optStringOrNull("escada")?.let {
                    when (it) {
                        "sobe" -> Stair.UP
                        "desce" -> Stair.DOWN
                        "indefinida" -> Stair.UNKNOWN
                        else -> throw IllegalArgumentException("Trecho $from -> $to: escada deve ser sobe, desce ou indefinida")
                    }
                }
                val rumo = edge.getDouble("rumo")
                val referencias = edge.optJSONArray("referencias")?.strings().orEmpty()
                adjacency.getValue(from) += Edge(to, mod360(rumo), escada, peso, referencias)
                adjacency.getValue(to) += Edge(from, mod360(rumo + 180), escada?.reversed(), peso, referencias)
            }
            return FloorMap(
                nome = raw.optStringOrNull("nome"),
                exemplo = raw.optStringOrNull("exemplo") ?: "sala 15B",
                exemploOrigem = raw.optStringOrNull("exemploOrigem") ?: "balcão de informações",
                cruzamentosPorEtapa = raw.optInt("cruzamentosPorEtapa", 5),
                points = points,
                adjacency = adjacency,
            )
        }
    }
}

internal fun mod360(degrees: Double): Double = ((degrees % 360) + 360) % 360

/** Signed turn between -180 and 180; positive turns right. */
internal fun turnAngle(from: Double, to: Double): Double = mod360(to - from + 180) - 180

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
