package com.example.sonaruna.guia

import kotlin.math.abs

/** One spoken step of a route; [cruzamentos] is how many crossings it passes before the stop. */
data class Instruction(val texto: String, val cruzamentos: Int)

private const val STRAIGHT = "Siga em frente"
private val NOT_A_CROSSING = setOf("curva", "patamar", "porta", "marco")
private val NOT_A_PLACE = setOf("cruzamento", "curva", "patamar")

private fun turnPhrase(angle: Double): String = when {
    abs(angle) < 30 -> STRAIGHT
    abs(angle) > 150 -> "Dê meia-volta"
    angle > 0 -> "Vire à direita"
    else -> "Vire à esquerda"
}

private class Group(val virar: String, val escada: Stair?, val trechos: MutableList<Segment>)

private class Chunk(val trechos: List<Segment>, val passados: Int, val pausa: Boolean)

/**
 * Tactile-paving guidance without measurements, ported from web/engine.js: each step goes to a turn, a staircase,
 * the destination or, on a long straight, a crossing halfway, and says which crossing to stop at. Every point in
 * the middle of a straight counts as a crossing (except curves, landings, doors and markers), because on the
 * paving map the points are the junctions the person feels with the cane.
 * [heading] is the direction the person faces at the start; null means unknown (no turn is announced).
 */
fun FloorMap.buildInstructions(heading: Double?, segments: List<Segment>): List<Instruction> {
    val groups = mutableListOf<Group>()
    var facing = heading ?: segments.firstOrNull()?.rumo ?: 0.0
    for (segment in segments) {
        val angle = turnAngle(facing, segment.rumo)
        val last = groups.lastOrNull()
        if (last != null && last.escada == null && segment.escada == null && abs(angle) < 30) {
            last.trechos += segment
        } else {
            groups += Group(turnPhrase(angle), segment.escada, mutableListOf(segment))
        }
        facing = segment.rumo
    }
    val steps = mutableListOf<Instruction>()
    groups.forEachIndexed { index, group ->
        val isLast = index == groups.lastIndex
        val next = groups.getOrNull(index + 1)
        if (group.escada != null) {
            val turn = if (group.virar == STRAIGHT) "" else "${group.virar}. "
            val text = when (group.escada) {
                Stair.UP -> "${turn}Suba a escada."
                Stair.DOWN -> "${turn}Desça a escada."
                Stair.UNKNOWN -> "${turn}Atenção, escada. Use o corrimão."
            }
            steps += step(group.trechos, text, arrives = isLast, passados = 0)
            return@forEachIndexed
        }
        splitLongStraight(group.trechos, endsWithoutTurn = isLast || next?.escada != null).forEachIndexed { i, chunk ->
            val go = when {
                i > 0 -> "Continue em frente pelo piso tátil"
                group.virar == STRAIGHT -> "Siga em frente pelo piso tátil"
                else -> "${group.virar} e siga o piso tátil"
            }
            val end = points.getValue(chunk.trechos.last().to)
            val passing = if (chunk.passados > 0) ", passando por ${plural(chunk.passados, "cruzamento", "cruzamentos")}," else ""
            val text = when {
                chunk.pausa -> "$go até o ${ordinal(chunk.passados + 1)} cruzamento${landmark(end.id)}."
                isLast && end.tipo == "porta" -> "$go$passing até a porta."
                isLast -> "$go$passing até o fim."
                next?.escada != null -> "$go$passing até a escada."
                end.tipo == "curva" -> "$go$passing até a curva do piso."
                else -> "$go até o ${ordinal(chunk.passados + 1)} cruzamento."
            }
            steps += step(chunk.trechos, text, arrives = isLast && !chunk.pausa, passados = chunk.passados)
        }
    }
    return steps
}

private fun FloorMap.isCrossing(id: String): Boolean = points.getValue(id).tipo !in NOT_A_CROSSING

/**
 * Cuts a straight into pieces of at most [FloorMap.cruzamentosPorEtapa] crossings to count. Middle pieces end at a
 * crossing (pausa); the last keeps the straight's real end. When the straight ends at the crossing where the person
 * turns, that crossing counts too, because the person has to count it to stop.
 */
private fun FloorMap.splitLongStraight(trechos: List<Segment>, endsWithoutTurn: Boolean): List<Chunk> {
    val chunks = mutableListOf<Chunk>()
    var current = mutableListOf<Segment>()
    var counted = 0
    trechos.forEachIndexed { i, segment ->
        current += segment
        if (i == trechos.lastIndex || !isCrossing(segment.to)) return@forEachIndexed
        counted++
        val remaining = trechos.subList(i + 1, trechos.lastIndex).count { isCrossing(it.to) } + if (endsWithoutTurn) 0 else 1
        if (counted == cruzamentosPorEtapa && remaining > 0) {
            chunks += Chunk(current, counted - 1, pausa = true)
            current = mutableListOf()
            counted = 0
        }
    }
    chunks += Chunk(current, counted, pausa = false)
    return chunks
}

/** ", na altura da sala 14B": the first named place joined to the crossing, so the person can check where they stopped. */
private fun FloorMap.landmark(crossingId: String): String {
    val named = adjacency.getValue(crossingId).map { points.getValue(it.to) }.firstOrNull { it.tipo !in NOT_A_PLACE }
    return if (named != null) ", na altura ${named.deLugar}" else ""
}

private fun FloorMap.step(trechos: List<Segment>, text: String, arrives: Boolean, passados: Int): Instruction {
    val parts = mutableListOf(text)
    val referencias = trechos.flatMap { it.referencias }.distinct()
    if (referencias.isNotEmpty()) parts += "Referência: ${referencias.joinToString(", ")}."
    val end = points.getValue(trechos.last().to)
    parts += if (arrives) "Você chegará ${end.emLugar}. Quando chegar, diga pronto." else "Quando terminar, diga pronto."
    return Instruction(parts.joinToString(" "), passados)
}
